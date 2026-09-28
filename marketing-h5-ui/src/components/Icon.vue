<script setup>
import { computed } from "vue";

/**
 * 图标集：SF Symbols 风格的线性图标（24 网格、1.6 描边、圆头）。
 * 全部是这里的静态字符串，不含任何外部输入，v-html 只渲染本文件常量。
 * 用 SVG 而不是 emoji 是硬要求：emoji 在不同系统上字形/尺寸/基线全不一致，
 * 也没法跟着 currentColor 走，Apple 的界面里不存在 emoji 当图标这回事。
 */
const props = defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 20 },
  strokeWidth: { type: [Number, String], default: 1.6 },
  fill: { type: Boolean, default: false }, // true = 实心（选中态导航用）
});

const P = {
  house: "M4 10.6 12 4l8 6.6V19a1.5 1.5 0 0 1-1.5 1.5h-3.3v-5.3H8.8v5.3H5.5A1.5 1.5 0 0 1 4 19z",
  ticket: "M4 9.5A1.5 1.5 0 0 1 5.5 8h13A1.5 1.5 0 0 1 20 9.5v1a2 2 0 0 0 0 3v1A1.5 1.5 0 0 1 18.5 16h-13A1.5 1.5 0 0 1 4 14.5v-1a2 2 0 0 0 0-3zM12 8.5v7",
  bolt: "M13.2 2.5 5.4 13.2h5.1l-1 8.3 7.9-10.8h-5.1z",
  cart: "M3.2 4.2h2.1l2.3 10.3a1.2 1.2 0 0 0 1.2.9h8.3a1.2 1.2 0 0 0 1.2-.9L20.6 8H6.2",
  person: "M12 11.5a3.8 3.8 0 1 0 0-7.6 3.8 3.8 0 0 0 0 7.6ZM4.5 20.5a7.5 7.5 0 0 1 15 0",
  wallet: "M3.5 8.5A2.5 2.5 0 0 1 6 6h11.5v2.5M3.5 8.5v8A2.5 2.5 0 0 0 6 19h13a1.5 1.5 0 0 0 1.5-1.5v-7A1.5 1.5 0 0 0 19 9H6a2.5 2.5 0 0 0-2.5 2.5zM16.5 14h.01",
  back: "M14.5 5.5 8 12l6.5 6.5",
  chevron: "M9.5 5.5 16 12l-6.5 6.5",
  close: "M6 6l12 12M18 6 6 18",
  check: "M4.5 12.5 9.5 17.5 19.5 6.5",
  plus: "M12 5.5v13M5.5 12h13",
  minus: "M5.5 12h13",
  refresh: "M20.5 12a8.5 8.5 0 1 1-2.6-6.1M20.5 4.5V9H16",
  clock: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7.5V12l3.2 2",
  tag: "M4 4h7.2L20 12.8 12.8 20 4 11.2zM8 8h.01",
  gift: "M4 11.5h16V20H4zM4 8h16v3.5H4zM12 8v12M12 8S10.2 3.5 7.6 4.4 8.9 8 12 8zM12 8s1.8-4.5 4.4-3.6S15.1 8 12 8z",
  bag: "M6 7.5h12L19 20.5H5zM9.2 7.5a2.8 2.8 0 0 1 5.6 0",
  cup: "M5 8h11v5.5a5 5 0 0 1-10 0zM16 9.5h1.5a2 2 0 0 1 0 4H16M7.5 3v2M11 3v2",
  device: "M7.5 3h9a1.5 1.5 0 0 1 1.5 1.5v15A1.5 1.5 0 0 1 16.5 21h-9A1.5 1.5 0 0 1 6 19.5v-15A1.5 1.5 0 0 1 7.5 3zM10.5 18h3",
  headphones: "M4.5 14.5v-2.2a7.5 7.5 0 0 1 15 0v2.2M4.5 13.5h1.2a1.3 1.3 0 0 1 1.3 1.3v3a1.3 1.3 0 0 1-1.3 1.3H5.8a1.3 1.3 0 0 1-1.3-1.3zM19.5 13.5h-1.2a1.3 1.3 0 0 0-1.3 1.3v3a1.3 1.3 0 0 0 1.3 1.3h.7a1.3 1.3 0 0 0 1.3-1.3z",
  sun: "M12 16.5a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9zM12 2.5V4M12 20v1.5M4.5 4.6 5.6 5.7M18.4 18.3l1.1 1.1M2.5 12H4M20 12h1.5M4.6 19.4l1.1-1.1M18.3 5.6l1.1-1.1",
  moon: "M20.5 14.8A8.8 8.8 0 0 1 9.2 3.5a8.8 8.8 0 1 0 11.3 11.3z",
  alert: "M12 3.5 21.5 20H2.5zM12 10v4.5M12 17.2h.01",
  info: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v5.5M12 7.8h.01",
  box: "M12 3.2 20.5 8v8L12 20.8 3.5 16V8zM3.5 8 12 12.6 20.5 8M12 12.6v8.2",
  search: "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20.5 20.5 16.2 16.2",
  trash: "M4.5 7h15M9.5 7V4.8h5V7M6.5 7l1 13.2h9L17.5 7",
  arrow: "M4.5 12h14M13 6.5l5.5 5.5L13 17.5",
  sparkles: "M12 3.5l1.9 4.9 4.9 1.9-4.9 1.9L12 17.1l-1.9-4.9-4.9-1.9 4.9-1.9zM18.5 15.5l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z",
  shield: "M12 3.2l7.5 2.8v5.6c0 4.6-3.1 7.7-7.5 9.3-4.4-1.6-7.5-4.7-7.5-9.3V6z",
  crown: "M4 17.5h16M4 17.5 3 7l4.8 3.6L12 4.5l4.2 6.1L21 7l-1 10.5",
  // 账号体系专用：口令字段、显隐切换。沿用同一套 24 网格 / 1.6 描边口径。
  lock: "M7.5 10.2V7.8a4.5 4.5 0 0 1 9 0v2.4M6 10.2h12a1.5 1.5 0 0 1 1.5 1.5v6.3A1.5 1.5 0 0 1 18 19.5H6a1.5 1.5 0 0 1-1.5-1.5v-6.3A1.5 1.5 0 0 1 6 10.2zM12 13.9v2.3",
  eye: "M2.5 12S6 5.8 12 5.8 21.5 12 21.5 12 18 18.2 12 18.2 2.5 12 2.5 12zM14.6 12a2.6 2.6 0 1 1-5.2 0 2.6 2.6 0 0 1 5.2 0z",
  "eye-off": "M4.2 4.2l15.6 15.6M9.9 6.1A9.7 9.7 0 0 1 12 5.8c6 0 9.5 6.2 9.5 6.2a17.4 17.4 0 0 1-3.2 3.9M6.6 8.1A16.7 16.7 0 0 0 2.5 12S6 18.2 12 18.2a9.7 9.7 0 0 0 3.5-.6M10.3 10.2a2.6 2.6 0 0 0 3.6 3.6",
};

const inner = computed(() => {
  const d = P[props.name];
  if (!d) return P.box;
  // 多子路径用 "M" 起始的复合串，交给单个 path 也能画（fill-rule 用 nonzero）
  return `<path d="${d}" />`;
});

const cls = computed(() => (props.fill ? "ico ico--fill" : "ico"));
</script>

<template>
  <svg
    :class="cls"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    :stroke-width="strokeWidth"
    stroke="currentColor"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    v-html="inner"
  />
</template>

<style scoped>
.ico { display: block; flex: none; }
.ico--fill :deep(path) { fill: currentColor; }
</style>
