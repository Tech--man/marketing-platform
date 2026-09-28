<script setup>
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import AppNav from "@/components/AppNav.vue";
import AppToast from "@/components/AppToast.vue";
import ThrottleBanner from "@/components/ThrottleBanner.vue";
import Icon from "@/components/Icon.vue";
import { useCart } from "@/stores/cart";

const route = useRoute();
const router = useRouter();
const cart = useCart();

// 沉浸页（秒杀详情、结算）在移动端不挂底部 Tab，把竖向空间让给内容。
// 桌面顶部导航始终保留——它就是导航，不该因为进了详情页就消失。
const hideTab = computed(() => route.name === "seckill-detail" || route.name === "checkout");
// 首页在**桌面**不再叠一条页面标题栏：顶部导航左边已经是品牌"营销中心"，首页的路由标题
// 也是"营销中心"，两条栏上下摆着一模一样的四个字，是换成顶部导航才暴露出来的重复。
// 只在桌面收：移动端没有顶部导航，这条栏是它唯一的品牌位，v-if 会把品牌整个弄丢。
const redundantToolbar = computed(() => route.name === "home");
const showBack = computed(() => !!route.meta?.back);
const title = computed(() => route.meta?.title || "营销中心");

function goBack() {
  if (window.history.length > 1) router.back();
  else router.push("/home");
}
</script>

<template>
  <div class="app-shell" :class="{ 'app-shell--bare': hideTab }">
    <AppNav :badge="cart.totalQuantity" />

    <div class="app-body">
      <header class="app-toolbar" :class="{ 'app-toolbar--redundant': redundantToolbar }">
        <button v-if="showBack" class="icon-btn app-toolbar__back" aria-label="返回" @click="goBack">
          <Icon name="back" :size="20" />
        </button>
        <div class="app-toolbar__title truncate">{{ title }}</div>
        <div id="toolbar-actions" class="app-toolbar__actions"></div>
      </header>

      <ThrottleBanner />

      <main class="app-main">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" :key="route.name" />
          </transition>
        </router-view>
      </main>
    </div>

    <AppToast />
  </div>
</template>

<style scoped>
.app-toolbar__back { margin-left: -8px; }
/* 首页：桌面顶部导航已经带了品牌，这条重复的标题栏收掉；移动端保留（它是唯一品牌位）。
   用 CSS 而不是 v-if，是为了让 #toolbar-actions 这个 teleport 目标在两份骨架里都稳定存在。 */
@media (min-width: 1024px) {
  .app-toolbar--redundant { display: none; }
  .app-toolbar__back { margin-left: -4px; }
}
</style>
