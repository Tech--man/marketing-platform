/**
 * 金额/时间格式化。后端有两套口径，别混：
 * - 券面额 / 门槛 / 折扣计算 / 秒杀价 = BigDecimal「元」，Jackson 序列化成 JSON number；
 * - 活动预算 deduct / remain = Long「分」。
 * 这里对二者分别处理，且都容忍 number / string / null 三种入参。
 */

const NUM = (v) => {
  if (v == null || v === "") return 0;
  const n = typeof v === "number" ? v : Number(v);
  return Number.isFinite(n) ? n : 0;
};

/** 拆成整数位与小数位（两位），供价格组件分别放大/缩小。读不到（null/空串/非数）
 *  给 "—" 段而不是 0/00——调用方应先过 hasAmt，这里的哨兵是给漏网调用兜底，
 *  保证"不知道"永远不会被渲染成"免费"（2026-10-01 审计 P2-7 复审 N-5）。 */
export function splitYuan(v) {
  if (!hasAmt(v)) return { int: "—", dec: "" };
  const fixed = NUM(v).toFixed(2);
  const [int, dec] = fixed.split(".");
  return { int, dec };
}

/** 「有没有一笔真金额」：null/undefined/空串都算"读不到"，与后台 TriState 同一口径。
 *  钱的读数宁可显示 —（读不到）也不能折叠成 0——"免费的"与"不知道"是两件事，
 *  后者会把加载态/降级态伪装成定价（2026-10-01 审计 P2-7）。 */
export function hasAmt(v) {
  if (v == null || v === "") return false;
  const n = typeof v === "number" ? v : Number(v);
  return Number.isFinite(n);
}

/** "¥12.00" —— 完整金额文本；读不到（null/空串）给 "—" 而不是 ¥0.00 */
export function yuan(v, { sign = true } = {}) {
  if (!hasAmt(v)) return "—";
  const fixed = NUM(v).toFixed(2);
  const [int, dec] = fixed.split(".");
  const grouped = int.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return `${sign ? "¥" : ""}${grouped}.${dec}`;
}

/** 优惠扣减额（"-12.00"）——视图层的"活动优惠/优惠券"行统一走这里：
 *  读不到时给裸 "—"，而不是 "-¥0.00"（伪装成真优惠）或 "-—"（负号挂空值）。
 *  与 yuan() 同一判定（N-5：塌缩点必须收在工具层，视图只做呈现）。 */
export function cutYuan(v) {
  return hasAmt(v) ? `-${yuan(v, { sign: false })}` : "—";
}

/** 应付合计（原价 − 券抵扣，夹 0 下限）：试算没读到 → null（上层显示 "--"）；
 *  券面值没读到 → 不抵扣、原样返回。Cart/Checkout 两处共用同一语义，
 *  视图不再各自 `?? 0`（N-5：那是把"不知道"折成"免费"的塌缩点）。 */
export function netPayable(payable, cut) {
  if (!hasAmt(payable)) return null;
  if (!hasAmt(cut)) return Number(payable);
  return Math.max(0, Number(payable) - Number(cut));
}

/** 分 → 元（预算用）；null 视作「未知」而非 0，交由上层降级渲染 */
export function cents(c) {
  if (c == null) return null;
  return NUM(c) / 100;
}

/** 百分比：0..1 或 0..100 都能给个像样的读数 */
export function pct(part, whole) {
  if (!whole) return 0;
  return Math.max(0, Math.min(100, Math.round((NUM(part) / NUM(whole)) * 100)));
}

/** 把后端 LocalDateTime 序列（"2026-09-24T15:04:05" 或带毫秒）解析成 Date。
 *  W4（2026-09-30 第二轮复审）：无时区后缀的 ISO 串按 ES 规范走浏览器本地时区，
 *  而服务端口径是 Asia/Shanghai（serverTimezone=+08:00）——浏览器时区不对时，
 *  秒杀 phase/倒计时/时间显示全部平移。解析时显式拼 +08:00，再交给 Date 转
 *  本地显示：一处收口，全站统一。 */
export function toDate(s) {
  if (!s) return null;
  if (s instanceof Date) return Number.isNaN(s.getTime()) ? null : s;
  let str = typeof s === "number" ? null : String(s).replace(" ", "T");
  if (str != null && !/[Zz]|[+-]\d{2}:?\d{2}$/.test(str)) {
    str += "+08:00"; // 后端 LocalDateTime 无时区：按服务端时区补全
  }
  const d = new Date(typeof s === "number" ? s : str);
  return Number.isNaN(d.getTime()) ? null : d;
}

export function formatDateTime(s) {
  const d = toDate(s);
  if (!d) return "—";
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getMonth() + 1}月${d.getDate()}日 ${p(d.getHours())}:${p(d.getMinutes())}`;
}

export function formatClock(s) {
  const d = toDate(s);
  if (!d) return "—";
  const p = (n) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}`;
}

/**
 * 剩余时间分解。target 可以是 Date 或可解析字符串；now 可注入便于测试。
 * 返回 {d,h,m,s,total,ended}，ended 为 true 表示已过期/已开抢。
 */
export function countdown(target, now = Date.now()) {
  const end = toDate(target);
  if (!end) return { d: 0, h: 0, m: 0, s: 0, total: 0, ended: true };
  const total = end.getTime() - now;
  if (total <= 0) return { d: 0, h: 0, m: 0, s: 0, total: 0, ended: true };
  const s = Math.floor(total / 1000);
  return {
    d: Math.floor(s / 86400),
    h: Math.floor((s % 86400) / 3600),
    m: Math.floor((s % 3600) / 60),
    s: s % 60,
    total,
    ended: false,
  };
}

const pad = (n) => String(n).padStart(2, "0");

/** 把 countdown 结果渲染成文案：>1天用「X天」，否则 HH:MM:SS */
export function countdownText(c) {
  if (c.ended) return "";
  if (c.d > 0) return `${c.d}天${pad(c.h)}:${pad(c.m)}:${pad(c.s)}`;
  return `${pad(c.h)}:${pad(c.m)}:${pad(c.s)}`;
}

/** 秒杀 result 串解析：SUCCESS:orderNo / FAIL:reason / ACCEPTED / NOT_FOUND */
export function parseGrabResult(raw) {
  const v = raw == null ? "NOT_FOUND" : String(raw);
  if (v.startsWith("SUCCESS:")) return { state: "success", orderNo: v.slice(8) };
  if (v.startsWith("FAIL:")) return { state: "fail", reason: v.slice(5) };
  if (v === "ACCEPTED") return { state: "accepted" };
  return { state: "not_found" };
}

/** 全链路幂等键 / 抢购 requestId */
export function uuid() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return "r-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 10);
}
