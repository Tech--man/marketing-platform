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

/* 桌面顶部导航：文字入口，不放图标。
   消费者网站的桌面导航（Apple / 京东 / 淘宝 / Nike）都是文字横排 + 右侧账号，
   而"图标 + 文字 + 分组标题"的竖排列表是后台工具的形。侧栏那一版就是照抄了
   marketing-admin-ui 的 AppLayout，所以看着像给商店装了个控制台。
   wallet 只在桌面出现（移动端从领券中心的"我的卡包"与账户页进），别在重构时弄丢。 */
const links = [
  { key: "home", to: "/home", label: "首页" },
  { key: "coupons", to: "/coupons", label: "领券中心" },
  { key: "seckill", to: "/seckill", label: "限时秒杀" },
  { key: "cart", to: "/cart", label: "购物车", badge: true },
  { key: "wallet", to: "/wallet", label: "我的卡包" },
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
  <!-- 桌面顶部导航（<1024px 不渲染） -->
  <header class="topnav">
    <div class="topnav__inner">
      <RouterLink to="/home" class="topnav__brand">
        <span class="topnav__mark"><Icon name="sparkles" :size="15" /></span>
        <span class="topnav__brand-t">营销中心</span>
      </RouterLink>

      <nav class="topnav__links" aria-label="主导航">
        <RouterLink
          v-for="l in links"
          :key="l.to"
          :to="l.to"
          class="topnav__link"
          :class="{ 'is-active': active() === l.key }"
          :aria-current="active() === l.key ? 'page' : undefined"
        >
          <span>{{ l.label }}</span>
          <span v-if="l.badge && badge > 0" class="topnav__badge">{{ badge > 99 ? "99+" : badge }}</span>
        </RouterLink>
      </nav>

      <div class="topnav__side">
        <RouterLink v-if="session.isLoggedIn" to="/me" class="topnav__user"
                    :class="{ 'is-active': active() === 'me' }">
          <MAvatar :hue="session.avatarHue" :label="session.display" :size="26" />
          <span class="truncate">{{ session.display }}</span>
        </RouterLink>
        <RouterLink v-else to="/login" class="topnav__user">
          <span class="topnav__anon"><Icon name="person" :size="14" /></span>
          <span>登录</span>
        </RouterLink>
        <button class="icon-btn" :aria-label="theme === 'dark' ? '切换到浅色' : '切换到深色'" @click="flip">
          <Icon :name="theme === 'dark' ? 'sun' : 'moon'" :size="17" />
        </button>
      </div>
    </div>
  </header>

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
/* ---------- 桌面顶部导航 ---------- */
.topnav {
  display: none;
  position: sticky;
  top: 0;
  z-index: calc(var(--z-sticky) + 1);
  height: var(--topbar-h);
  background: var(--material-bg);
  backdrop-filter: var(--material-filter);
  -webkit-backdrop-filter: var(--material-filter);
  border-bottom: 1px solid var(--hairline);
}
@media (min-width: 1024px) {
  .topnav { display: block; }
}
.topnav__inner {
  display: flex;
  align-items: center;
  gap: var(--space-6);
  height: 100%;
  max-width: var(--shell-wide);
  margin: 0 auto;
  padding: 0 var(--space-7);
}
.topnav__brand {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--c-text);
  flex: none;
}
.topnav__mark {
  width: 24px;
  height: 24px;
  border-radius: 7px;
  display: grid;
  place-items: center;
  background: var(--c-text);
  color: var(--c-surface);
}
.topnav__brand-t {
  font-weight: 600;
  font-size: var(--fs-base);
  letter-spacing: var(--tracking-head);
}
.topnav__links {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex: 1;
  min-width: 0;
}
.topnav__link {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px var(--space-3);
  border-radius: var(--radius-pill);
  color: var(--c-text-2);
  font-size: var(--fs-sm);
  font-weight: 500;
  white-space: nowrap;
  transition: background-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.topnav__link:hover { background: var(--c-surface-3); color: var(--c-text); text-decoration: none; }
.topnav__link.is-active { color: var(--c-text); font-weight: 600; }
/* 选中态靠字重与一条下划线，不靠整块底色：顶部导航里塞满蓝色药丸会立刻变回工具味 */
.topnav__link.is-active::after {
  content: "";
  position: absolute;
  left: var(--space-3);
  right: var(--space-3);
  bottom: -1px;
  height: 2px;
  border-radius: 2px;
  background: var(--c-brand);
}
.topnav__badge {
  min-width: 17px;
  height: 17px;
  padding: 0 5px;
  border-radius: var(--radius-pill);
  background: var(--c-danger);
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  display: grid;
  place-items: center;
  line-height: 1;
}
.topnav__side {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex: none;
}
.topnav__user {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  max-width: 168px;
  padding: 4px var(--space-2) 4px 4px;
  border-radius: var(--radius-pill);
  color: var(--c-text);
  font-size: var(--fs-sm);
  font-weight: 500;
}
.topnav__user:hover { background: var(--c-surface-3); text-decoration: none; }
.topnav__user.is-active { font-weight: 600; }
/* 未登录时没有头像可用：给一个同尺寸的中性占位圆，账号区不因此跳动 */
.topnav__anon {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  flex: none;
  display: grid;
  place-items: center;
  background: var(--c-surface-3);
  color: var(--c-text-muted);
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
