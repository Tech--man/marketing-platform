import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  api,
  ApiError,
  E,
  poll,
  grantDone,
  grabDone,
  messageFor,
  noteThrottle,
  needsLogin,
  throttleState,
  clearThrottle,
  bindAuthBridge,
  onSessionCleared,
} from "@/api/client";

function jsonResponse(body, status = 200) {
  return { status, json: async () => body, headers: new Headers() };
}

/** 统一信封：业务码在 body.code，HTTP 恒定 200（网关/业务两侧都这样） */
function env(code, data = null, message = "") {
  return jsonResponse({ code, message, data, success: code === 0 });
}

/**
 * 一个可观察的假 bridge：client.js 只通过它读写登录态，
 * 所以这些测试不需要 Pinia，也不需要真浏览器存储。
 */
function fakeSession(initial = { accessToken: "AT-1", refreshToken: "RT-1" }) {
  const s = {
    access: initial.accessToken || "",
    refresh: initial.refreshToken || "",
    cleared: 0,
    applied: [],
  };
  bindAuthBridge({
    access: () => s.access || null,
    refresh: () => s.refresh || null,
    apply: (pair) => {
      s.applied.push(pair);
      if (pair.accessToken) s.access = pair.accessToken;
      if (pair.refreshToken) s.refresh = pair.refreshToken;
      return true;
    },
    clear: () => {
      s.access = "";
      s.refresh = "";
      s.cleared += 1;
      return true;
    },
  });
  return s;
}

/** 按 URL 分派的 fetch 桩；每个 URL 的记录可以是被消费掉的数组，或是函数 */
function stubFetch(routes) {
  const calls = [];
  const counters = {};
  const fetch = vi.fn(async (url, opts = {}) => {
    const n = (counters[url] = (counters[url] || 0) + 1);
    calls.push({ url, method: opts.method || "GET", headers: opts.headers || {}, body: opts.body });
    const hit = routes[url] || routes["*"];
    if (hit === undefined) return env(E.NOT_FOUND, null, "no handler");
    if (typeof hit === "function") return hit({ n, call: calls[calls.length - 1] });
    if (Array.isArray(hit)) return hit[n - 1] || hit[hit.length - 1];
    return hit;
  });
  vi.stubGlobal("fetch", fetch);
  return { fetch, calls };
}

beforeEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
  clearThrottle();
  bindAuthBridge(null); // 默认：没有 store 注册过 bridge = 游客
});
afterEach(() => {
  vi.unstubAllGlobals();
  bindAuthBridge(null);
});

describe("api.request 看 body.code 而非 HTTP status", () => {
  it("code:0 时直接返回 data，并带上会话里的 Bearer 头", async () => {
    const s = fakeSession({ accessToken: "AT-abc", refreshToken: "RT-abc" });
    const { fetch } = stubFetch({ "/api/x": env(0, { hi: 1 }) });
    const data = await api.get("/api/x");
    expect(data).toEqual({ hi: 1 });
    expect(fetch).toHaveBeenCalledWith("/api/x", expect.objectContaining({ method: "GET" }));
    expect(fetch.mock.calls[0][1].headers.Authorization).toBe("Bearer AT-abc");
    expect(s.cleared).toBe(0);
  });

  it("未登录就是游客：请求根本不该带 Authorization", async () => {
    fakeSession({ accessToken: "", refreshToken: "" });
    const { calls } = stubFetch({ "/api/seckill/activities": env(0, []) });
    await api.get("/api/seckill/activities");
    expect(calls[0].headers.Authorization).toBeUndefined();
  });

  it("HTTP 200 但 code=41002（券领光）也当失败抛 ApiError", async () => {
    fakeSession();
    stubFetch({ "/api/coupon/grant": env(E.STOCK, null, "sold out") });
    await expect(api.post("/api/coupon/grant", {})).rejects.toBeInstanceOf(ApiError);
    stubFetch({ "/api/coupon/grant": env(E.STOCK, null, "sold out") });
    await expect(api.post("/api/coupon/grant", {})).rejects.toMatchObject({ code: E.STOCK });
  });

  it("无响应体（502）时用 HTTP status 兜底出错误码", async () => {
    fakeSession();
    stubFetch({
      "/api/x": { status: 502, json: async () => { throw new Error("no body"); }, headers: new Headers() },
    });
    await expect(api.get("/api/x")).rejects.toMatchObject({ code: 50200 });
  });
});

describe("40101 → 刷新一次 → 重放原请求", () => {
  it("原请求带旧凭证撞 40101：刷一次、换新凭证、把原请求重放一次并成功", async () => {
    const s = fakeSession({ accessToken: "AT-old", refreshToken: "RT-1" });
    const { calls } = stubFetch({
      "/api/coupon/usable": [env(E.TOKEN_EXPIRED, null, "登录已过期"), env(0, [{ couponCode: "C-1" }])],
      "/api/auth/refresh": env(0, {
        accessToken: "AT-new",
        refreshToken: "RT-2",
        expiresInSeconds: 900,
        uid: 90001,
      }),
    });

    const data = await api.get("/api/coupon/usable");

    expect(data).toEqual([{ couponCode: "C-1" }]);
    expect(calls.map((c) => c.url)).toEqual(["/api/coupon/usable", "/api/auth/refresh", "/api/coupon/usable"]);
    // 刷新用 refreshToken、且不把已过期的 access 头带上
    expect(JSON.parse(calls[1].body)).toEqual({ refreshToken: "RT-1" });
    expect(calls[1].headers.Authorization).toBeUndefined();
    // 重放带的是新凭证；轮换后的 refreshToken 也落了盘
    expect(calls[2].headers.Authorization).toBe("Bearer AT-new");
    expect(s.access).toBe("AT-new");
    expect(s.refresh).toBe("RT-2");
    expect(s.cleared).toBe(0);
  });

  it("并发的 40101 只刷一次：刷新是轮换的，各刷各的会踩重放检测把整条会话打死", async () => {
    const s = fakeSession({ accessToken: "AT-old", refreshToken: "RT-1" });
    let refreshCalls = 0;
    const { calls } = stubFetch({
      "/api/a": () => (calls.filter((c) => c.url === "/api/a").length <= 1 ? env(E.TOKEN_EXPIRED) : env(0, "A")),
      "/api/b": () => (calls.filter((c) => c.url === "/api/b").length <= 1 ? env(E.TOKEN_EXPIRED) : env(0, "B")),
      "/api/auth/refresh": () => {
        refreshCalls += 1;
        return env(0, { accessToken: "AT-new", refreshToken: "RT-2", expiresInSeconds: 900, uid: 9 });
      },
    });

    const [a, b] = await Promise.all([api.get("/api/a"), api.get("/api/b")]);

    expect(a).toBe("A");
    expect(b).toBe("B");
    expect(refreshCalls, "刷新只允许出网一次").toBe(1);
    expect(s.applied.length).toBe(1);
    // 两个重放都带新凭证
    const replays = calls.filter((c) => c.headers.Authorization === "Bearer AT-new");
    expect(replays.length).toBe(2);
  });

  it("刷新失败 = 会话已死：清登录态、不重放、把错抛给调用方", async () => {
    const s = fakeSession({ accessToken: "AT-old", refreshToken: "RT-1" });
    let notified = 0;
    const off = onSessionCleared(() => (notified += 1));
    const { calls } = stubFetch({
      "/api/a": env(E.TOKEN_EXPIRED),
      "/api/auth/refresh": env(E.SESSION_REVOKED, null, "会话已失效"),
    });

    await expect(api.get("/api/a")).rejects.toMatchObject({ code: E.SESSION_REVOKED });
    expect(calls.map((c) => c.url)).toEqual(["/api/a", "/api/auth/refresh"]);
    expect(s.cleared).toBe(1);
    expect(notified).toBe(1);
    off();
  });

  it("重放仍然 40101：不再刷第二次（否则会跟一个坏时钟死循环），直接登出", async () => {
    const s = fakeSession({ accessToken: "AT-old", refreshToken: "RT-1" });
    let refreshCalls = 0;
    const { calls } = stubFetch({
      "/api/a": env(E.TOKEN_EXPIRED),
      "/api/auth/refresh": () => {
        refreshCalls += 1;
        return env(0, { accessToken: "AT-" + refreshCalls, refreshToken: "RT-x", expiresInSeconds: 900 });
      },
    });

    await expect(api.get("/api/a")).rejects.toMatchObject({ code: E.TOKEN_EXPIRED });
    expect(refreshCalls).toBe(1);
    expect(calls.filter((c) => c.url === "/api/a").length).toBe(2);
    expect(s.cleared).toBe(1);
  });

  it("只有 access 没有 refresh（不该发生）时不刷，直接登出", async () => {
    const s = fakeSession({ accessToken: "AT-only", refreshToken: "" });
    const { calls } = stubFetch({ "/api/a": env(E.TOKEN_EXPIRED) });
    await expect(api.get("/api/a")).rejects.toMatchObject({ code: E.TOKEN_EXPIRED });
    expect(calls.some((c) => c.url === "/api/auth/refresh")).toBe(false);
    expect(s.cleared).toBe(1);
  });
});

describe("40100 / 40102 不刷新：会话死了，刷也活不过来", () => {
  it("40102（被吊销/整号作废）→ 零次刷新 + 清登录态", async () => {
    const s = fakeSession();
    const { calls } = stubFetch({ "/api/seckill/pay/O1": env(E.SESSION_REVOKED, null, "会话已失效") });
    await expect(api.post("/api/seckill/pay/O1")).rejects.toMatchObject({ code: E.SESSION_REVOKED });
    expect(calls.some((c) => c.url === "/api/auth/refresh")).toBe(false);
    expect(s.cleared).toBe(1);
  });

  it("40100（缺少/无效凭证）→ 零次刷新 + 清登录态；游客不会被广播踢去登录页", async () => {
    const s = fakeSession({ accessToken: "AT-1", refreshToken: "" });
    let notified = 0;
    const off = onSessionCleared(() => (notified += 1));
    const { calls } = stubFetch({ "/api/coupon/usable": env(E.UNAUTHORIZED, null, "缺少登录凭证") });
    await expect(api.get("/api/coupon/usable")).rejects.toMatchObject({ code: E.UNAUTHORIZED });
    expect(calls.some((c) => c.url === "/api/auth/refresh")).toBe(false);
    expect(s.cleared).toBe(1);
    expect(notified, "本来有凭证才广播，否则登录页会自己刷自己").toBe(1);
    off();
  });

  it("登录/注册端点自己回 40100 是「账号或口令错」，不该顺手清掉别人的会话", async () => {
    const s = fakeSession({ accessToken: "AT-keep", refreshToken: "RT-keep" });
    stubFetch({ "/api/auth/login": env(E.UNAUTHORIZED, null, "账号或口令不正确") });
    await expect(api.post("/api/auth/login", { identifier: "a", password: "b" })).rejects.toMatchObject({
      code: E.UNAUTHORIZED,
    });
    expect(s.cleared).toBe(0);
    expect(s.access).toBe("AT-keep");
  });

  it("needsLogin 只认这三个码，视图据此决定要不要跳登录", () => {
    expect(needsLogin(new ApiError(E.UNAUTHORIZED, "", null))).toBe(true);
    expect(needsLogin(new ApiError(E.TOKEN_EXPIRED, "", null))).toBe(true);
    expect(needsLogin(new ApiError(E.SESSION_REVOKED, "", null))).toBe(true);
    expect(needsLogin(new ApiError(E.FORBIDDEN, "", null))).toBe(false);
    expect(needsLogin(null)).toBe(false);
  });
});

describe("限流排队信号", () => {
  it("42900 会置位 throttleState 并带出 queueCode", async () => {
    fakeSession();
    stubFetch({ "/api/seckill/activities": env(E.RATE, { queueCode: "Q123" }, "too many") });
    await expect(api.get("/api/seckill/activities")).rejects.toMatchObject({ code: E.RATE });
    const err = new ApiError(42900, "x", { data: { queueCode: "Q999" } });
    noteThrottle(err);
    expect(throttleState.active).toBe(true);
    expect(throttleState.queueCode).toBe("Q999");
    clearThrottle();
    expect(throttleState.active).toBe(false);
  });
});

describe("messageFor 业务码翻译", () => {
  it("覆盖消费者能感知的几种终态", () => {
    expect(messageFor(new ApiError(E.STOCK, "", null))).toContain("领光");
    expect(messageFor(new ApiError(E.BUDGET, "", null))).toContain("补贴");
    expect(messageFor(new ApiError(E.RISK, "", null))).toContain("风控");
    expect(messageFor(new ApiError(E.TOKEN_EXPIRED, "", null))).toContain("重新登录");
    expect(messageFor(new ApiError(E.SESSION_REVOKED, "", null))).toContain("重新登录");
    expect(messageFor(new ApiError(99999, "自定义", null))).toBe("自定义");
  });
});

describe("poll 轮询到终态", () => {
  it("isDone 判 true 即返回", async () => {
    let n = 0;
    const r = await poll(async () => { n++; return n >= 3 ? "done" : "pending"; }, { isDone: (v) => v === "done", interval: 1, timeout: 1000 });
    expect(r).toBe("done");
  });
  it("超时抛出（DUPLICATE 语义）", async () => {
    await expect(poll(async () => "pending", { interval: 5, timeout: 20 })).rejects.toMatchObject({ code: E.DUPLICATE });
  });
});

describe("领券/抢购终态判定", () => {
  it("grantDone 只认 SUCCESS/FAILED", () => {
    expect(grantDone({ status: "SUCCESS", couponCode: "C1" })).toBe(true);
    expect(grantDone({ status: "PROCESSING" })).toBe(false);
    expect(grantDone(null)).toBe(false);
  });
  it("grabDone 认 SUCCESS:/FAIL:/NOT_FOUND，ACCEPTED 继续", () => {
    expect(grabDone({ result: "ACCEPTED" })).toBe(false);
    expect(grabDone({ result: "SUCCESS:O1" })).toBe(true);
    expect(grabDone({ result: "FAIL:没了" })).toBe(true);
  });
});
