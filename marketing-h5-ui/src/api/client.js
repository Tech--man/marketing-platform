import { reactive } from "vue";

/**
 * C 端 API 客户端。三条口径：
 *
 * 1) **身份**：真实消费者登录态。access token 由 session store 持有，本文件通过一个
 *    极薄的 bridge 读写它（见 bindAuthBridge）——client 不 import store，store 不 import
 *    client，两者不成环。没登录就是游客：请求不带 Authorization，游客可看的目录读数
 *    （活动详情 / 秒杀场次 / 余量 / 券库存）照旧能拿。绝不再手填 userId。
 *
 * 2) **成败看 body.code，不看 HTTP status**：BizException/校验失败都是 HTTP 200 + 4xxxx，
 *    只有系统错/网络错才是 4xx/5xx。
 *
 * 3) **40101 只刷一次、只重试一次**，且并发的 40101 共用同一次刷新（refreshOnce）。
 *    refreshToken 是轮换的：一次刷新作废上一枚。两个请求各自去刷 → 第二个必然拿已被
 *    轮换掉的凭证 → 触发重放检测，整条会话被吊销（40102）。所以这里把并发刷新串行化
 *    到一个 in-flight promise 上，而不是各自 fetch。
 *    40100（凭证无效/缺少凭证）与 40102（会话已失效）**不刷新**：会话已经死了，
 *    刷它也活不过来，直接清登录态并把人送去登录。
 */

/** 刷新端点：免 access token（它多半正是过期了才要刷），凭证在请求体里 */
export const AUTH_REFRESH_PATH = "/api/auth/refresh";

/** 与 marketing-common ErrorCode 对齐（只列会改变界面动作的码） */
export const E = {
  SUCCESS: 0,
  BAD_REQUEST: 40000,
  UNAUTHORIZED: 40100, // 凭证无效 / 缺少登录凭证
  TOKEN_EXPIRED: 40101, // 登录已过期：唯一可以静默刷新的码
  SESSION_REVOKED: 40102, // 会话已失效（登出 / 被吊销 / 整号作废）
  FORBIDDEN: 40300, // 无权执行该操作（含"这单不是你的"）
  NOT_FOUND: 40400,
  BIZ: 41000, // 超单人限领 / 通用业务
  STOCK: 41002, // 券被领空
  BUDGET: 41003, // 活动预算耗尽
  DUPLICATE: 41005, // 幂等重放 / 抢锁失败
  RISK: 41006, // 风控拒绝
  NOT_ONLINE: 41007, // 活动/秒杀未上线
  RATE: 42900, // 网关限流（带 queueCode）
  SYSTEM: 50000,
  CALC_TIMEOUT: 50001,
};

/** 把业务码翻译成给人看的一句话；未知码回落到服务端 message */
export function messageFor(err) {
  switch (err.code) {
    case E.UNAUTHORIZED:
      return "请先登录后继续";
    case E.TOKEN_EXPIRED:
      return "登录已过期，请重新登录";
    case E.SESSION_REVOKED:
      return "会话已失效，请重新登录";
    case E.FORBIDDEN:
      return "没有权限执行该操作";
    case E.STOCK:
      return "来晚一步，这张券已被领光了";
    case E.BUDGET:
      return "本场补贴已发完，明天早点来";
    case E.RISK:
      return "活动太火爆，操作被风控拦下，稍后再试";
    case E.NOT_ONLINE:
      return "活动还没开始或已下线";
    case E.DUPLICATE:
      return "操作处理中，请勿重复提交";
    case E.RATE:
      return "前方排队人数较多，正在为你排队";
    case E.BAD_REQUEST:
      return err.message || "请检查填写的内容";
    default:
      return err.message || "出了点小状况，请稍后重试";
  }
}

export class ApiError extends Error {
  constructor(code, message, payload) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.payload = payload;
    // 网关限流时 data 里带 queueCode，供排队 UI 使用
    this.queueCode = payload?.data?.queueCode || null;
  }
  get ok() {
    return this.code === E.SUCCESS;
  }
}

/** 这类错意味着"要登录"，视图据此把用户带去登录页并记住来路 */
export function needsLogin(err) {
  const c = err?.code;
  return c === E.UNAUTHORIZED || c === E.TOKEN_EXPIRED || c === E.SESSION_REVOKED;
}

/* ------------------------------------------------------------------ *
 * 登录态桥：store 在 setup 里把自己注册进来。
 * { access(), refresh(), apply(pair), clear() }
 * ------------------------------------------------------------------ */
let bridge = null;
export function bindAuthBridge(b) {
  bridge = b || null;
}
export function authBridge() {
  return bridge;
}

/** 被动登出（凭证被判定死了）时的订阅者：路由层用它跳登录页 */
const clearedHandlers = new Set();
export function onSessionCleared(fn) {
  clearedHandlers.add(fn);
  return () => clearedHandlers.delete(fn);
}

/**
 * 清登录态并通知。只在"本来有会话"时才广播——否则一个未登录用户点到需要登录的读数
 * 也会触发一次跳转，登录页就能被自己刷成死循环。
 */
function forceSignOut() {
  const had = !!(bridge?.access?.() || bridge?.refresh?.());
  try {
    bridge?.clear?.();
  } finally {
    if (had) clearedHandlers.forEach((fn) => { try { fn(); } catch { /* 订阅者自己崩了不该吞掉登出 */ } });
  }
}

/**
 * 一次裸请求：发出去、拆开信封，把结论结构化返回（不抛业务错）。
 * withAuth=false 用于刷新端点——它恰恰是"access 已经过期"时要打的。
 */
async function send(method, path, body, withAuth) {
  const headers = {};
  const token = withAuth ? bridge?.access?.() : null;
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";

  let res;
  try {
    res = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    // 断网 / 网关不可达：不能当成"没数据"
    throw new ApiError(E.SYSTEM, "网络连接失败，请检查网络后重试", null);
  }
  let payload = null;
  try {
    payload = await res.json();
  } catch {
    /* 空/非 JSON（如 502） */
  }
  if (payload && payload.code === 0) {
    return { code: E.SUCCESS, data: payload.data, message: payload.message, payload };
  }
  const code = payload ? payload.code : res.status * 100;
  return {
    code,
    data: payload?.data,
    message: (payload && payload.message) || `请求失败（HTTP ${res.status}）`,
    payload,
  };
}

/* ------------------------------------------------------------------ *
 * 刷新：单飞（single flight）
 * ------------------------------------------------------------------ */

/** 用 refreshToken 换一对新凭证。公开导出：authApi.refresh 走的就是它，口径只有一处 */
export function refreshSession(refreshToken) {
  return send("POST", AUTH_REFRESH_PATH, { refreshToken }, false).then((r) => {
    if (r.code === E.SUCCESS && r.data?.accessToken) return r.data;
    throw new ApiError(r.code || E.UNAUTHORIZED, r.message || "登录已过期，请重新登录", r.payload);
  });
}

let inflight = null;

/**
 * 并发 40101 全部等同一个 promise：只有一次真正的 /api/auth/refresh 出网。
 * 结算后立刻清空，下一次过期照旧能刷（不是一次性缓存）。
 */
export function refreshOnce() {
  if (inflight) return inflight;
  const rt = bridge?.refresh?.();
  if (!rt) {
    forceSignOut();
    return Promise.reject(new ApiError(E.UNAUTHORIZED, "请先登录后继续", null));
  }
  inflight = refreshSession(rt)
    .then((pair) => {
      bridge?.apply?.(pair);
      return pair;
    })
    .catch((err) => {
      forceSignOut(); // 刷不动 = 会话已死（重放检测/被吊销/账号停用）
      throw err;
    })
    .finally(() => {
      inflight = null;
    });
  return inflight;
}

/** 测试用：当前是否有一次刷新在飞 */
export function refreshing() {
  return !!inflight;
}

/**
 * 这三个口本身不需要登录态，它们回 40100 是"账号或口令错了"，
 * 不是"你的会话死了"——不能顺手把已登录的会话清掉。
 */
const PUBLIC_AUTH_PATHS = ["/api/auth/login", "/api/auth/register", AUTH_REFRESH_PATH];
function isPublicAuthPath(path) {
  return PUBLIC_AUTH_PATHS.some((p) => path.startsWith(p));
}

async function request(method, path, body, retried) {
  const r = await send(method, path, body, true);
  if (r.code === E.SUCCESS) return r.data;

  // 唯一可以静默恢复的码：access 过期。刷一次、重试一次，不循环。
  const canRefresh = r.code === E.TOKEN_EXPIRED && !retried && !!bridge?.refresh?.();
  if (canRefresh) {
    await refreshOnce(); // 失败已在内部登出，这里把错抛给调用方
    return request(method, path, body, true);
  }

  if (!isPublicAuthPath(path)) {
    // 40100 缺少/无效凭证、40102 会话已失效：都不刷新。
    // 走到这里的 40101 只剩两种可能：已经重放过一次，或本地根本没有 refreshToken 可刷
    // ——两种都等同于"这条会话救不回来了"。
    if (r.code === E.UNAUTHORIZED || r.code === E.SESSION_REVOKED || r.code === E.TOKEN_EXPIRED) {
      forceSignOut();
    }
  }
  throw new ApiError(r.code, r.message, r.payload);
}

export const api = {
  get: (p) => request("GET", p, undefined, false),
  post: (p, b) => request("POST", p, b ?? {}, false),
  put: (p, b) => request("PUT", p, b ?? {}, false),
};

/**
 * 轮询到终态。后端两处「提交→轮询」（领券 / 抢购）共用这套节拍。
 * isDone(value) 判终态后 resolve；超过 timeoutMs 抛一个 DUPLICATE 语义的"超时"。
 */
export async function poll(fetcher, { isDone = () => false, interval = 900, timeout = 20000, signal } = {}) {
  const started = Date.now();
  // eslint-disable-next-line no-constant-condition
  while (true) {
    if (signal?.aborted) throw new ApiError(E.SYSTEM, "已取消", null);
    const value = await fetcher();
    if (isDone(value)) return value;
    if (Date.now() - started > timeout) {
      throw new ApiError(E.DUPLICATE, "处理中，请稍后在结果页查看", null);
    }
    await new Promise((r) => setTimeout(r, interval));
  }
}

/** 领券结果：SUCCESS / FAILED 为终态，PROCESSING 继续轮 */
export function grantDone(r) {
  return !!r && (r.status === "SUCCESS" || r.status === "FAILED");
}

/** 抢购结果：以 result 串前缀判终态 */
export function grabDone(r) {
  if (!r) return false;
  const v = String(r.result ?? r ?? "");
  return (
    v.startsWith("SUCCESS:") ||
    v.startsWith("FAIL:") ||
    v === "NOT_FOUND" ||
    v === "SUCCESS" ||
    v === "FAIL" ||
    v === "PAID"
  );
}

/** 全局排队状态：任一请求命中 42900 时置位，供顶部横幅 / 拦截 */
export const throttleState = reactive({ active: false, queueCode: null, since: 0 });

export function noteThrottle(err) {
  if (err && err.code === E.RATE) {
    throttleState.active = true;
    throttleState.queueCode = err.queueCode;
    throttleState.since = Date.now();
  }
}
export function clearThrottle() {
  throttleState.active = false;
  throttleState.queueCode = null;
}
