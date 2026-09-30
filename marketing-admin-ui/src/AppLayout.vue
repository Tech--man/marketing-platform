<script setup>
import { computed, onMounted, onUnmounted, ref } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import { useSession } from "@/stores/session";
import { E } from "@/api/client";
import Button from "@/components/Button.vue";
import { getTheme, toggleTheme } from "@/theme";
import {
  Odometer,
  Refresh,
  Tools,
  Calendar,
  Tickets,
  PriceTag,
  Stopwatch,
  User,
  Connection,
  Document,
  Sunny,
  Moon,
  Menu,
} from "@element-plus/icons-vue";

const s = useSession();
const route = useRoute();
const router = useRouter();

/**
 * 导航表。`to` 同时是路由 name 的来源（去掉前导斜杠），因此只列已注册的路由：
 * 下面用 `router.hasRoute` 过滤，没做的页面不会露出来。分组只是视觉归类，
 * 渲染出的仍是同一批 <RouterLink>（routes.spec 断言 aside nav a 恰好 10 个）。
 */
const NAV = [
  { to: "/ops", label: "运维大盘", icon: Odometer, group: "运维" },
  { to: "/cache", label: "缓存重预热", icon: Refresh, group: "运维", operate: true },
  { to: "/config", label: "在线配置", icon: Tools, group: "配置" },
  { to: "/activities", label: "活动", icon: Calendar, group: "业务" },
  { to: "/coupons", label: "券模板", icon: Tickets, group: "业务" },
  { to: "/rules", label: "优惠规则", icon: PriceTag, group: "业务" },
  { to: "/seckill", label: "秒杀活动", icon: Stopwatch, group: "业务" },
  { to: "/users", label: "账号", icon: User, group: "系统" },
  { to: "/sessions", label: "在线会话", icon: Connection, group: "系统" },
  { to: "/audits", label: "审计", icon: Document, group: "系统" },
];

function visible(n) {
  return (
    router.hasRoute(n.to.slice(1)) &&
    (n.write ? s.canWrite : n.operate ? s.canOperate : true)
  );
}

// 分组保持 NAV 里的出现顺序；整组都不可见时不渲染组标题。
const groups = computed(() => {
  const out = [];
  for (const n of NAV) {
    if (!visible(n)) continue;
    let g = out.find((x) => x.name === n.group);
    if (!g) out.push((g = { name: n.group, items: [] }));
    g.items.push(n);
  }
  return out;
});

const pageTitle = computed(() => {
  const hit = NAV.find((n) => n.to === route.path);
  return hit ? hit.label : "营销平台后台";
});

// 窄屏抽屉：同一批 <a> 用 CSS 位移隐藏/显示，绝不复制第二组（否则 aside nav a 变 20）。
const navOpen = ref(false);
function closeNav() {
  navOpen.value = false;
}

const left = ref(s.secondsLeft());
let ticker = null
onMounted(() => {
  ticker = setInterval(() => {
    left.value = s.secondsLeft();
    // 归零前一分钟提示一次，别等表单填了一半才被 40101 打断
    // W4（2026-09-30 第二轮复审）：清会话后直接回登录页——只清不跳的话用户停在
    // 原页，下一发请求才以 40101 被弹走（填了一半的表单被吞，正是注释想避免的）
    if (s.authed && left.value === 0) {
      s.clear();
      router.replace({ name: 'login' });
    }
  }, 1000);
});
onUnmounted(() => clearInterval(ticker));

const theme = ref(getTheme());
function flipTheme() {
  theme.value = toggleTheme();
}

async function signOut() {
  try {
    await s.logout();
  } catch (e) {
    if (e.code !== E.REVOKED) throw e;
  }
  router.replace({ name: "login" });
}
</script>

<template>
  <div class="shell">
    <aside class="side" :class="{ 'side--open': navOpen }">
      <RouterLink class="brand" to="/">
        <span class="brand__mark"><el-icon><Odometer /></el-icon></span>
        <span class="brand__text">营销平台后台</span>
      </RouterLink>
      <nav class="side__nav">
        <template v-for="g in groups" :key="g.name">
          <p class="side__group">{{ g.name }}</p>
          <RouterLink
            v-for="n in g.items"
            :key="n.to"
            :to="n.to"
            class="side__link"
            :class="{ 'is-active': route.path === n.to }"
            @click="closeNav"
          >
            <el-icon class="ico"><component :is="n.icon" /></el-icon>
            <span>{{ n.label }}</span>
          </RouterLink>
        </template>
      </nav>
    </aside>

    <div class="main">
      <header class="topbar">
        <button
          class="btn btn-ghost btn-icon topbar__menu"
          aria-label="切换导航"
          @click="navOpen = !navOpen"
        >
          <el-icon class="ico-lg"><Menu /></el-icon>
        </button>
        <h1 class="topbar__title">{{ pageTitle }}</h1>
        <div class="topbar__actions">
          <span class="topbar__role" data-testid="role">
            <span class="topbar__role-name">{{ s.me?.role || "未登录" }}</span>
            <span class="topbar__sep">·</span>
            <span class="topbar__count">剩余 {{ left }}s</span>
          </span>
          <Button
            class="btn-icon"
            variant="ghost"
            :aria-label="theme === 'dark' ? '切换亮色' : '切换暗色'"
            @click="flipTheme"
          >
            <el-icon class="ico-lg"><component :is="theme === 'dark' ? Moon : Sunny" /></el-icon>
          </Button>
          <Button data-act="logout" variant="ghost" size="sm" @click="signOut">退出</Button>
        </div>
      </header>
      <div class="scrim" :class="{ 'scrim--on': navOpen }" @click="closeNav"></div>
      <main class="content"><RouterView /></main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  min-height: 100vh;
}

/* —— 侧栏 —— */
.side {
  width: var(--sidebar-w);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--c-surface);
  border-right: 1px solid var(--c-border);
}
.brand {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  height: var(--header-h);
  padding: 0 var(--space-4);
  font-weight: 600;
  font-size: var(--fs-md);
  color: var(--c-text);
  border-bottom: 1px solid var(--c-border);
  white-space: nowrap;
}
.brand__mark {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: var(--radius-sm);
  background: var(--c-accent);
  color: var(--c-accent-contrast);
  font-size: 15px;
}
.side__nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--space-3) var(--space-2) var(--space-4);
  overflow-y: auto;
}
.side__group {
  padding: var(--space-3) var(--space-3) var(--space-1);
  font-size: var(--fs-xs);
  font-weight: 600;
  letter-spacing: 0.05em;
  color: var(--c-text-faint);
}
.side__link {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0 var(--space-3);
  height: 38px;
  border-radius: var(--radius-md);
  color: var(--c-text-2);
  font-size: var(--fs-base);
  font-weight: 500;
  cursor: pointer;
  transition: background-color var(--dur) var(--ease), color var(--dur) var(--ease);
}
.side__link .ico {
  color: var(--c-text-muted);
}
.side__link:hover {
  background: var(--c-surface-3);
  color: var(--c-text);
}
.side__link.is-active {
  background: var(--c-accent-soft);
  color: var(--c-accent);
}
.side__link.is-active .ico {
  color: var(--c-accent);
}

/* —— 主区 + 顶栏 —— */
.main {
  position: relative;
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.topbar {
  position: sticky;
  top: 0;
  z-index: var(--z-header);
  display: flex;
  align-items: center;
  gap: var(--space-3);
  height: var(--header-h);
  padding: 0 var(--space-5);
  background: color-mix(in srgb, var(--c-surface) 88%, transparent);
  backdrop-filter: saturate(1.4) blur(8px);
  border-bottom: 1px solid var(--c-border);
}
.topbar__title {
  font-size: var(--fs-md);
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.topbar__actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.topbar__role {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  height: 26px;
  padding: 0 var(--space-2);
  font-size: var(--fs-xs);
  color: var(--c-text-muted);
  background: var(--c-surface-2);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-pill);
}
.topbar__role-name {
  color: var(--c-text-2);
  font-weight: 500;
}
.topbar__count {
  font-variant-numeric: tabular-nums;
}
.topbar__menu {
  display: none;
}
.content {
  flex: 1;
  padding: var(--space-5);
  overflow-x: auto;
}

/* —— 窄屏：侧栏变抽屉，抽屉复用同一批 <a> —— */
.scrim {
  display: none;
}
@media (max-width: 860px) {
  .side {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    z-index: calc(var(--z-modal) + 1);
    transform: translateX(-100%);
    transition: transform var(--dur) var(--ease);
    box-shadow: var(--shadow-2);
  }
  .side--open {
    transform: translateX(0);
  }
  .topbar__menu {
    display: inline-flex;
  }
  .content {
    padding: var(--space-4);
  }
  .scrim--on {
    display: block;
    position: fixed;
    inset: 0;
    z-index: var(--z-modal);
    background: rgba(2, 6, 23, 0.45);
  }
}
</style>
