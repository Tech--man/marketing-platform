<script setup>
/**
 * 状态徽章：一枚语义色药丸，前面带状态点。用于角色 / 启用停用 / 在线离线这类短状态串。
 * 点色用 currentColor，文字仍来自插槽，不额外塞数字或图标（保留可读性与断言文案）。
 */
defineProps({
  tone: { type: String, default: "neutral" }, // neutral|success|warning|danger|info|accent
  dot: { type: Boolean, default: true },
});
</script>

<template>
  <span class="pill" :class="tone !== 'neutral' ? 'pill-' + tone : ''">
    <i v-if="dot" class="pill__dot" /><slot />
  </span>
</template>
