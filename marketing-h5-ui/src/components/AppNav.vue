<script setup>
import { RouterLink, useRoute } from "vue-router";
import Icon from "@/components/Icon.vue";
import MAvatar from "@/components/MAvatar.vue";
import { useSession } from "@/stores/session";
import { getTheme, toggleTheme } from "@/theme";
import { ref } from "vue";

defineProps({ badge: { type: Number, default: 0 } });
const route = useRoute();
const session = useSession();
const theme = ref(getTheme());

const groups = [
  {
    label: "逛一逛",
    items: [
      { key: "home", to: "/home", label: "首页", icon: "house" },
      { key: "coupons", to: "/coupons", label: "领券中心", icon: "ticket" },
      { key: "seckill", to: "/seckill", label: "限时秒杀", icon: "bolt" },
    ],
  },
  {
    label: "我的",
    items: [
      { key: "cart", to: "/cart", label: "购物车", icon: "cart", badge: true },
      { key: "wallet", to: "/wallet", label: "我的卡包", icon: "wallet" },
      { key: "me", to: "/me", label: "账户", icon: "person" },
    ],
  },
];

/* 移动端底部栏只放 5 个主入口 */
const tabs = [
  { key: "home", to: "/home", label: "首页", icon: "house" },
  { key: "coupons", to: "/coupons", label: "领券", icon: "ticket" },
  { key: "seckill", to: "/seckill", label: "秒杀", icon: "bolt" },
  { key: "cart", to: "/cart", label: "购物车", icon: "cart", badge: true },
  { key: "me", to: "/me", label: "我的", icon: "person" },
];

const active = () => route.meta?.tab;
function flip() {
  theme.value = toggleTheme();
}
</script>

<template>
  <!-- 桌面侧栏 -->
  <aside class="side" aria-label="主导航">
    <RouterLink to="/home" class="side__brand">
      <span class="side__mark"><Icon name="sparkles" :size="17" /></span>
      <span class="side__brand-t">营销中心</span>
    </RouterLink>

    <nav class="side__nav">
      <div v-for="g in groups" :key="g.label" class="side__group">
        <div class="side__label">{{ g.label }}</div>
        <RouterLink
          v-for="it in g.items"
          :key="it.to"
          :to="it.to"
          class="side__item"
          :class="{ 'is-active': active() === it.key }"
          :aria-current="active() === it.key ? 'page' : undefined"
        >
          <Icon :name="it.icon" :size="19" :fill="active() === it.key" />
          <span class="grow">{{ it.label }}</span>
          <span v-if="it.badge && badge > 0" class="side__badge">{{ badge > 99 ? "99+" : badge }}</span>
        </RouterLink>
      </div>
    </nav>

    <div class="side__foot">
      <RouterLink v-if="session.isLoggedIn" to="/me" class="side__user">
        <MAvatar :hue="session.avatarHue" :label="session.display" :size="30" />
        <span class="grow truncate small">{{ session.display }}</span>
      </RouterLink>
      <RouterLink v-else to="/login" class="side__user">
        <span class="side__anon"><Icon name="person" :size="16" /></span>
        <span class="grow truncate small">登录</span>
      </RouterLink>
      <button class="icon-btn side__theme" :aria-label="theme === 'dark' ? '切换到浅色' : '切换到深色'" @click="flip">
        <Icon :name="theme === 'dark' ? 'sun' : 'moon'" :size="18" />
      </button>
    </div>
  </aside>

  <!-- 移动端底部 Tab -->
  <nav class="tabbar" aria-label="主导航">
    <RouterLink
      v-for="t in tabs"
      :key="t.to"
      :to="t.to"
      class="tabbar__item"
      :class="{ 'is-active': active() === t.key }"
      :aria-current="active() === t.key ? 'page' : undefined"
    >
      <span class="tabbar__ico">
        <Icon :name="t.icon" :size="22" :fill="active() === t.key" />
        <span v-if="t.badge && badge > 0" class="tabbar__badge">{{ badge > 99 ? "99+" : badge }}</span>
      </span>
      <span class="tabbar__label">{{ t.label }}</span>
    </RouterLink>
  </nav>
</template>

<style scoped>
/* ---------- 桌面侧栏 ---------- */
.side {
  display: none;
  position: sticky;
  top: 0;
  height: 100vh;
  height: 100dvh;
  width: var(--sidebar-w);
  flex: none;
  flex-direction: column;
  padding: var(--space-5) var(--space-3) var(--space-4);
  background: var(--c-surface-2);
  border-right: 1px solid var(--hairline);
}
@media (min-width: 1024px) {
  .side { display: flex; }
}
.side__brand {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 0 var(--space-3) var(--space-5);
  color: var(--c-text);
}
.side__mark {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  background: var(--c-brand);
  color: #fff;
}
.side__brand-t { font-weight: 600; font-size: var(--fs-base); letter-spacing: var(--tracking-head); }
.side__nav { flex: 1; overflow-y: auto; }
.side__group + .side__group { margin-top: var(--space-5); }
.side__label {
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--c-text-faint);
  letter-spacing: 0.02em;
  padding: 0 var(--space-3) var(--space-2);
}
.side__item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 8px var(--space-3);
  border-radius: var(--radius-sm);
  color: var(--c-text-2);
  font-size: var(--fs-base);
  font-weight: 500;
  transition: background-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.side__item:hover { background: var(--c-surface-3); color: var(--c-text); text-decoration: none; }
.side__item.is-active { background: var(--c-surface-3); color: var(--c-brand); font-weight: 600; }
.side__badge {
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: var(--radius-pill);
  background: var(--c-brand);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  display: grid;
  place-items: center;
}
.side__foot {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding-top: var(--space-4);
  margin-top: var(--space-4);
  border-top: 1px solid var(--hairline);
}
.side__user { display: flex; align-items: center; gap: var(--space-2); flex: 1; min-width: 0; color: var(--c-text); }
.side__user:hover { text-decoration: none; }
/* 未登录时没有头像可用：给一个同尺寸的中性占位圆，脚部布局不因此跳动 */
.side__anon {
  width: 30px; height: 30px; border-radius: 50%; flex: none;
  display: grid; place-items: center;
  background: var(--c-surface-3); color: var(--c-text-muted);
}

/* ---------- 移动端底部 Tab ---------- */
.tabbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: var(--z-nav);
  display: flex;
  height: calc(var(--nav-h) + var(--safe-b));
  padding-bottom: var(--safe-b);
  background: var(--material-bg);
  backdrop-filter: var(--material-filter);
  -webkit-backdrop-filter: var(--material-filter);
  border-top: 1px solid var(--hairline);
}
@media (min-width: 1024px) {
  .tabbar { display: none; }
}
.tabbar__item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  color: var(--c-text-muted);
  transition: color var(--dur-fast) var(--ease);
}
.tabbar__item:hover { text-decoration: none; }
.tabbar__ico { position: relative; display: block; }
.tabbar__label { font-size: 10px; font-weight: 500; }
.tabbar__item.is-active { color: var(--c-brand); }
.tabbar__badge {
  position: absolute;
  top: -5px;
  right: -9px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: var(--radius-pill);
  background: var(--c-danger);
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  display: grid;
  place-items: center;
  line-height: 1;
}
</style>
