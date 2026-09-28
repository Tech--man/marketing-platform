import { defineStore } from "pinia";
import { ref, computed } from "vue";
import { bindAuthBridge } from "@/api/client";

/**
 * 消费者登录态。真实账号体系（marketing-account /api/auth/**）上线后，这里取代
 * 原来那把"人人共用的 demo token + 手填 userId"。
 *
 * 三条约束：
 * 1) 持久化走 localStorage：C 端是 hash 路由，刷新即整页重载，sessionStorage 之外的
 *    内存态活不过这一次 reload，而"逛到一半刷新就被踢回登录页"是不可接受的。
 *    token 只待在这里，**绝不进 URL**（历史记录 / 分享链接 / 网关 access log 都会留底）。
 * 2) accessToken 短寿命、每次请求都带；refreshToken 长寿命、只被 client.js 送去
 *    /api/auth/refresh。绝不把 refreshToken 塞进业务请求的头。
 * 3) client 与 store 不成环：store 单向把读写能力交给 client（bindAuthBridge），
 *    client 不 import 任何 store。
 */
const KEY = "mkt.h5.session";

function readSaved() {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const s = JSON.parse(raw);
    if (!s || typeof s !== "object" || !s.accessToken || !s.refreshToken) return null;
    return s;
  } catch {
    return null;
  }
}

export const useSession = defineStore("session", () => {
  const saved = readSaved();
  const accessToken = ref(saved?.accessToken || "");
  const refreshToken = ref(saved?.refreshToken || "");
  const uid = ref(Number(saved?.uid) || 0);
  const identifier = ref(saved?.identifier || "");
  const nickname = ref(saved?.nickname || "");
  const status = ref(saved?.status || "");
  /** access token 的到期时刻（毫秒）。只用于展示：到期不改变"是否已登录"的判定 */
  const expiresAt = ref(Number(saved?.expiresAt) || 0);

  function persist() {
    if (!accessToken.value) {
      localStorage.removeItem(KEY);
      return;
    }
    localStorage.setItem(
      KEY,
      JSON.stringify({
        accessToken: accessToken.value,
        refreshToken: refreshToken.value,
        uid: uid.value,
        identifier: identifier.value,
        nickname: nickname.value,
        status: status.value,
        expiresAt: expiresAt.value,
      })
    );
  }

  /** 登录/注册/刷新三个端点回的都是 TokenPair，口径同一份代码收口 */
  function applyTokenPair(pair) {
    if (!pair || !pair.accessToken) return false;
    accessToken.value = String(pair.accessToken);
    refreshToken.value = String(pair.refreshToken || refreshToken.value);
    uid.value = Number(pair.uid) || uid.value;
    if (pair.identifier) identifier.value = String(pair.identifier);
    if (pair.nickname != null) nickname.value = String(pair.nickname);
    expiresAt.value = Date.now() + Math.max(0, Number(pair.expiresInSeconds) || 0) * 1000;
    persist();
    return true;
  }

  /** /api/auth/me 回来的是权威身份，用它覆盖本地缓存的展示字段 */
  function applyMe(me) {
    if (!me) return;
    if (me.uid) uid.value = Number(me.uid);
    if (me.identifier) identifier.value = String(me.identifier);
    if (me.nickname != null) nickname.value = String(me.nickname);
    if (me.status) status.value = String(me.status);
    persist();
  }

  function clear() {
    accessToken.value = "";
    refreshToken.value = "";
    uid.value = 0;
    identifier.value = "";
    nickname.value = "";
    status.value = "";
    expiresAt.value = 0;
    localStorage.removeItem(KEY);
  }

  /**
   * "已登录"只看有没有凭证。access token 过期**不**算登出——client.js 会拿 refreshToken
   * 静默换一枚再重放原请求；在这里判 false 会把一次正常的过期变成"被踢回登录页"。
   */
  const isLoggedIn = computed(() => !!accessToken.value && !!refreshToken.value);
  const display = computed(() => nickname.value || identifier.value || "已登录用户");
  /** 稳定头像色：按 uid 散列到一个柔和色相 */
  const avatarHue = computed(() => Math.abs(uid.value || 0) % 360);

  bindAuthBridge({
    access: () => accessToken.value || null,
    refresh: () => refreshToken.value || null,
    apply: (pair) => applyTokenPair(pair),
    clear: () => clear(),
  });

  return {
    accessToken,
    refreshToken,
    uid,
    identifier,
    nickname,
    status,
    expiresAt,
    isLoggedIn,
    display,
    avatarHue,
    applyTokenPair,
    applyMe,
    clear,
    persist,
  };
});
