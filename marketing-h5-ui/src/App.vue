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

// 沉浸页（秒杀详情、结算）在移动端不挂底部 Tab，把竖向空间让给内容；
// 桌面侧栏始终保留——PC 上侧栏就是导航，不该因为进了详情页就消失。
const hideTab = computed(() => route.name === "seckill-detail" || route.name === "checkout");
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
      <header class="app-toolbar">
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
@media (min-width: 1024px) {
  .app-toolbar__back { margin-left: -4px; }
}
</style>
