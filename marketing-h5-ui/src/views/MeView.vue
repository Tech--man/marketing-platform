<script setup>
import { computed, onMounted, ref } from "vue";
import { RouterLink, useRouter } from "vue-router";
import MAvatar from "@/components/MAvatar.vue";
import MButton from "@/components/MButton.vue";
import MSheet from "@/components/MSheet.vue";
import Icon from "@/components/Icon.vue";
import { authApi } from "@/api";
import { ApiError, messageFor, needsLogin, noteThrottle } from "@/api/client";
import { formatDateTime } from "@/utils/format";
import { useSession } from "@/stores/session";
import { useToast } from "@/stores/toast";
import { getTheme, toggleTheme } from "@/theme";
import { signOut } from "@/utils/auth";

const session = useSession();
const toast = useToast();
const router = useRouter();

const theme = ref(getTheme());
const meLoading = ref(true);
const signingOut = ref(false);
const confirmOut = ref(false);

const STATUS = {
  ACTIVE: "正常",
  DISABLED: "已停用",
};

/**
 * /api/auth/me 是权威的"我是谁"：本地那份是登录时抄下来的，
 * 改名/停用这类变化只有这里能看到。失败不打扰——凭证死了的话路由会处理。
 */
async function loadMe() {
  meLoading.value = true;
  try {
    session.applyMe(await authApi.me());
  } catch (e) {
    if (e instanceof ApiError) {
      noteThrottle(e);
      // 凭证死了不用 toast 剧透：client 已清登录态，路由会把人送去登录页
      if (!needsLogin(e)) toast.error(messageFor(e));
    }
  } finally {
    meLoading.value = false;
  }
}
onMounted(loadMe);

const statusLabel = computed(() => STATUS[session.status] || session.status || "—");
const expiresText = computed(() =>
  session.expiresAt ? formatDateTime(session.expiresAt) : "未知"
);

function flip() {
  theme.value = toggleTheme();
}

async function doSignOut() {
  confirmOut.value = false;
  if (signingOut.value) return;
  signingOut.value = true;
  const confirmed = await signOut();
  signingOut.value = false;
  toast[confirmed ? "success" : "info"](confirmed ? "已退出登录" : "已清除本机登录态");
  router.replace({ name: "home" });
}

const groups = [
  {
    label: "我的",
    rows: [
      { to: "/wallet", icon: "wallet", label: "我的卡包", desc: "可使用的优惠券" },
      { to: "/coupons", icon: "ticket", label: "领券中心", desc: "去领新的券" },
    ],
  },
  {
    label: "购物",
    rows: [
      { to: "/seckill", icon: "bolt", label: "限时秒杀", desc: "整点开抢" },
      { to: "/cart", icon: "cart", label: "购物车", desc: "凑单与优惠试算" },
    ],
  },
  {
    label: "账号",
    rows: [
      { to: "/sessions", icon: "device", label: "登录设备与会话", desc: "谁在用它，什么时候到期" },
    ],
  },
];
</script>

<template>
  <div class="me">
    <!-- 账号卡：真实登录态，没有"换一个 userId 看看"这回事 -->
    <section class="card card--pad me__id">
      <MAvatar :hue="session.avatarHue" :label="session.display" :size="52" />
      <div class="grow">
        <div class="me__nick">{{ session.display }}</div>
        <div class="muted tiny num">
          {{ session.identifier || "未知登录名" }} · UID {{ session.uid }}
        </div>
      </div>
      <button class="btn btn--ghost btn--sm" @click="confirmOut = true">退出登录</button>
    </section>

    <p class="me__note tiny muted">
      账号、卡包与订单都由服务端按登录态判定：本机不再能"填一个 ID 换个身份"。
      凭证存在这台设备的浏览器里，短期凭证过期时会自动续期，退出登录会吊销服务端当前会话。
    </p>

    <div class="me__cols">
      <!-- iOS 设置风格的分组列表 -->
      <div class="me__groups">
        <section v-for="g in groups" :key="g.label" class="me__group">
          <div class="me__groupLabel">{{ g.label }}</div>
          <div class="me__list">
            <RouterLink v-for="r in g.rows" :key="r.to" :to="r.to" class="me__row">
              <span class="me__rowIco"><Icon :name="r.icon" :size="17" /></span>
              <span class="grow">
                <span class="me__rowLabel">{{ r.label }}</span>
                <span class="me__rowDesc">{{ r.desc }}</span>
              </span>
              <Icon name="chevron" :size="15" class="me__rowArrow" />
            </RouterLink>
          </div>
        </section>

        <section class="me__group">
          <div class="me__groupLabel">通用</div>
          <div class="me__list">
            <div class="me__row">
              <span class="me__rowIco"><Icon :name="theme === 'dark' ? 'moon' : 'sun'" :size="17" /></span>
              <span class="grow">
                <span class="me__rowLabel">外观</span>
                <span class="me__rowDesc">{{ theme === 'dark' ? '深色' : '浅色' }} · 默认跟随系统</span>
              </span>
              <button class="switch" :class="{ 'is-on': theme === 'dark' }" role="switch" :aria-checked="theme === 'dark'" aria-label="切换深浅色" @click="flip">
                <span class="switch__knob" />
              </button>
            </div>
          </div>
        </section>
      </div>

      <!-- 桌面右栏：这台设备上的登录态到底是什么 -->
      <aside class="me__side">
        <section class="card card--pad">
          <h3 class="me__sideTitle">登录状态</h3>
          <div class="me__kv"><span class="muted small">请求网关</span><code class="tiny">/api/**</code></div>
          <div class="me__kv"><span class="muted small">鉴权方式</span><span class="small">Bearer 短期凭证</span></div>
          <div class="me__kv"><span class="muted small">账号状态</span><span class="small">{{ meLoading ? '读取中…' : statusLabel }}</span></div>
          <div class="me__kv"><span class="muted small">短期凭证至</span><span class="small num">{{ expiresText }}</span></div>
          <p class="tiny muted me__sideNote">
            业务请求不再携带 userId：网关验过凭证后注入身份，业务侧再验一次签名。
            你能看到的卡包与订单，只有这一个账号的。
          </p>
        </section>

        <RouterLink to="/activity/ACT2026001" class="card card--pad me__sideLink">
          <span class="me__rowIco"><Icon name="sparkles" :size="17" /></span>
          <span class="grow">
            <span class="me__rowLabel">进行中的活动</span>
            <span class="me__rowDesc">2026 秋季大促 · 补贴与灰度</span>
          </span>
          <Icon name="chevron" :size="15" class="me__rowArrow" />
        </RouterLink>
      </aside>
    </div>

    <MSheet :open="confirmOut" title="退出登录" @close="confirmOut = false">
      <p class="me__outText">
        退出后这台设备上的 <span class="strong">{{ session.display }}</span> 登录态会被清除，
        服务端同时吊销当前会话。卡包与订单还在，重新登录就在。
      </p>
      <p class="muted tiny" style="margin-bottom: var(--space-5)">
        其他设备的会话不受影响，可在「登录设备与会话」里查看。
      </p>
      <div class="row" style="gap: var(--space-3)">
        <MButton variant="ghost" style="flex:1" @click="confirmOut = false">再想想</MButton>
        <MButton variant="danger" style="flex:1" :loading="signingOut" @click="doSignOut">退出</MButton>
      </div>
    </MSheet>
  </div>
</template>

<style scoped>
.me { max-width: 720px; }
.me__id { display: flex; align-items: center; gap: var(--space-4); }
.me__nick { font-weight: 600; font-size: var(--fs-md); letter-spacing: var(--tracking-body); }
.me__note { margin: var(--space-4) var(--space-1) var(--space-6); line-height: 1.6; }
.me__groups { display: flex; flex-direction: column; gap: var(--space-6); }
.me__groupLabel {
  font-size: var(--fs-xs); font-weight: 600; color: var(--c-text-muted);
  letter-spacing: 0.02em; padding: 0 var(--space-4) var(--space-2);
}
.me__list { background: var(--c-surface); border: 1px solid var(--hairline); border-radius: var(--radius-lg); overflow: hidden; }
.me__row {
  display: flex; align-items: center; gap: var(--space-3);
  padding: var(--space-4) var(--space-4); color: var(--c-text);
  transition: background-color var(--dur-fast) var(--ease);
}
.me__row:not(:last-child) { border-bottom: 1px solid var(--hairline); }
RouterLink.me__row:hover { background: var(--c-surface-2); text-decoration: none; }
.me__rowIco {
  width: 30px; height: 30px; border-radius: 8px; display: grid; place-items: center;
  background: var(--c-surface-3); color: var(--c-text-2); flex: none;
}
.me__rowLabel { display: block; font-size: var(--fs-base); font-weight: 500; }
.me__rowDesc { display: block; font-size: var(--fs-xs); color: var(--c-text-muted); margin-top: 1px; }
.me__rowArrow { color: var(--c-text-faint); flex: none; }

.switch { width: 46px; height: 28px; border-radius: var(--radius-pill); border: 0; background: var(--c-surface-3); position: relative; transition: background-color var(--dur) var(--ease); padding: 0; flex: none; }
.switch.is-on { background: var(--c-success); }
.switch__knob { position: absolute; top: 3px; left: 3px; width: 22px; height: 22px; border-radius: 50%; background: #fff; box-shadow: var(--shadow-1); transition: transform var(--dur) var(--ease); }
.switch.is-on .switch__knob { transform: translateX(18px); }

@media (min-width: 1024px) {
  .me { max-width: 1000px; }
  .me__cols { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: var(--space-7); align-items: start; }
  .me__side { display: flex; flex-direction: column; gap: var(--space-4); position: sticky; top: calc(var(--chrome-h) + var(--space-5)); }
}
@media (max-width: 1023px) {
  .me__side { display: flex; flex-direction: column; gap: var(--space-4); margin-top: var(--space-6); }
}
.me__sideTitle { font-size: var(--fs-base); margin-bottom: var(--space-4); }
.me__kv { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-3); padding: 5px 0; }
.me__sideNote { line-height: 1.6; margin-top: var(--space-4); }
.me__sideLink { display: flex; align-items: center; gap: var(--space-3); color: var(--c-text); }
.me__sideLink:hover { background: var(--c-surface-2); text-decoration: none; }
.me__outText { font-size: var(--fs-base); line-height: 1.6; margin-bottom: var(--space-3); }
</style>
