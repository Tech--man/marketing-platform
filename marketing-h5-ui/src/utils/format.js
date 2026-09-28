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

/** 拆成整数位与小数位（两位），供价格组件分别放大/缩小 */
export function splitYuan(v) {
  const fixed = NUM(v).toFixed(2);
  const [int, dec] = fixed.split(".");
  return { int, dec };
}

/** "¥12.00" —— 完整金额文本 */
export function yuan(v, { sign = true } = {}) {
  const fixed = NUM(v).toFixed(2);
  const [int, dec] = fixed.split(".");
  const grouped = int.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return `${sign ? "¥" : ""}${grouped}.${dec}`;
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

/** 把后端 LocalDateTime 序列（"2026-09-24T15:04:05" 或带毫秒）解析成 Date */
export function toDate(s) {
  if (!s) return null;
  if (s instanceof Date) return Number.isNaN(s.getTime()) ? null : s;
  const d = new Date(typeof s === "number" ? s : String(s).replace(" ", "T"));
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
