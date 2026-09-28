<script setup>
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import MState from "@/components/MState.vue";
import MStatusPill from "@/components/MStatusPill.vue";
import MButton from "@/components/MButton.vue";
import Icon from "@/components/Icon.vue";
import { authApi } from "@/api";
import { ApiError, messageFor, noteThrottle } from "@/api/client";
import { formatDateTime } from "@/utils/format";
import { useSession } from "@/stores/session";
import { useToast } from "@/stores/toast";
import { signOut } from "@/utils/auth";

const session = useSession();
const toast = useToast();
const router = useRouter();

const loading = ref(true);
const error = ref("");
const items = ref([]);
const revoking = ref(false);

/**
 * 会话列表只回 ip + userAgent，"这是什么设备"是拼出来的读数，不是后端的真相；
 * 认不出来就照实说认不出来，不猜。
 */
function clientLabel(ua) {
  const s = String(ua || "");
  if (!s || s === "null") return "未知客户端";
  const os = /iPhone|iPad|iPod/i.test(s)
    ? "iOS"
    : /Android/i.test(s)
      ? "Android"
      : /Mac OS X|Macintosh/i.test(s)
        ? "macOS"
        : /Windows/i.test(s)
          ? "Windows"
          : /Linux/i.test(s)
            ? "Linux"
            : null;
  const br = /Edg\//i.test(s)
    ? "Edge"
    : /OPR\/|Opera/i.test(s)
      ? "Opera"
      : /Firefox\//i.test(s)
        ? "Firefox"
        : /MicroMessenger/i.test(s)
          ? "微信内置浏览器"
          : /Chrome\//i.test(s)
            ? "Chrome"
            : /Safari\//i.test(s)
              ? "Safari"
              : null;
  return [br, os].filter(Boolean).join(" · ") || "浏览器";
}

const current = computed(() => items.value.find((x) => x.current) || null);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const data = await authApi.sessions();
    items.value = Array.isArray(data) ? data : [];
  } catch (e) {
    if (e instanceof ApiError) noteThrottle(e);
    error.value = e instanceof ApiError ? messageFor(e) : "加载失败";
  } finally {
    loading.value = false;
  }
}
onMounted(load);

async function revokeCurrent() {
  if (revoking.value) return;
  revoking.value = true;
  const ok = await signOut();
  revoking.value = false;
  toast[ok ? "success" : "info"](ok ? "已登出本机" : "服务端未确认，但本机登录态已清除");
  router.replace({ name: "login" });
}
</script>

<template>
  <div class="ss split split--wide">
    <Teleport to="#toolbar-actions">
      <button class="icon-btn" aria-label="刷新" @click="load">
        <Icon name="refresh" :size="18" :class="{ 'is-spinning': loading }" />
      </button>
    </Teleport>

    <div class="ss__main">
      <p class="ss__lede muted">
        每一条会话对应一台设备上的登录态。它们各自持有独立的凭证，互不共用。
      </p>

      <MState v-if="loading" variant="loading" title="加载中…" />
      <MState v-else-if="error" variant="error" title="加载失败" :hint="error">
        <button class="btn btn--ghost btn--sm" @click="load">重试</button>
      </MState>
      <MState v-else-if="!items.length" icon="shield" title="没有进行中的会话" hint="这说明凭证都已过期或被登出" />

      <section v-else class="card ss__list">
        <div v-for="(s, i) in items" :key="s.jti" class="ss__row" :class="{ 'ss__row--div': i }">
          <span class="ss__ico"><Icon name="device" :size="18" /></span>
          <div class="grow">
            <div class="ss__rowHead">
              <span class="ss__client">{{ clientLabel(s.userAgent) }}</span>
              <MStatusPill v-if="s.current" tone="brand">当前设备</MStatusPill>
            </div>
            <div class="ss__meta tiny muted">
              <span class="num">{{ s.ip || "未知地址" }}</span>
              <span>· 登录于 {{ formatDateTime(s.createTime) }}</span>
              <span>· 到期 {{ formatDateTime(s.expireAt) }}</span>
            </div>
            <div v-if="s.userAgent" class="ss__ua truncate faint tiny">{{ s.userAgent }}</div>
          </div>
        </div>
      </section>
    </div>

    <aside class="split__aside">
      <section class="card card--pad">
        <h3 class="ss__sideTitle">本机</h3>
        <div class="ss__kv"><span class="muted small">账号</span><span class="small strong truncate">{{ session.display }}</span></div>
        <div class="ss__kv"><span class="muted small">UID</span><span class="num small">{{ session.uid }}</span></div>
        <div class="ss__kv"><span class="muted small">会话标识</span><code class="tiny truncate">{{ current?.jti || "—" }}</code></div>
        <p class="ss__sideNote tiny muted">
          登出只吊销这一台设备的会话；其他设备会各自到期，或改口令让该账号此前的全部会话一起作废。
        </p>
        <MButton variant="danger" block :loading="revoking" @click="revokeCurrent">登出本机</MButton>
      </section>
    </aside>
  </div>
</template>

<style scoped>
.ss__lede { font-size: var(--fs-sm); line-height: 1.6; margin-bottom: var(--space-5); max-width: 46rem; }
.ss__list { overflow: hidden; }
.ss__row { display: flex; align-items: flex-start; gap: var(--space-3); padding: var(--space-4) var(--space-5); }
.ss__row--div { border-top: 1px solid var(--hairline); }
.ss__ico {
  width: 34px; height: 34px; border-radius: var(--radius-sm); flex: none;
  display: grid; place-items: center; background: var(--c-surface-3); color: var(--c-text-2);
}
.ss__rowHead { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
.ss__client { font-weight: 600; font-size: var(--fs-base); }
.ss__meta { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 3px; }
.ss__ua { margin-top: 4px; max-width: 46rem; }
.ss__sideTitle { font-size: var(--fs-base); margin-bottom: var(--space-4); }
.ss__kv { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-3); padding: 5px 0; }
.ss__sideNote { line-height: 1.6; margin: var(--space-4) 0 var(--space-5); }
.is-spinning { animation: spin 0.9s linear infinite; }
</style>
