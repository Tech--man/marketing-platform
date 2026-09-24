<script setup>
import { computed } from "vue";

/**
 * 唯一的按钮实现：根节点就是真正的 <button>，因此父级传来的
 * data-act / disabled / title / @click 全部按属性透传落在这个 <button> 上，
 * 测试里 `find('[data-act=..]').attributes('disabled')` 与文案断言照常成立。
 * type 走 prop（默认 button），提交处显式传 submit，避免裸 <button> 误触发表单。
 */
const props = defineProps({
  variant: { type: String, default: "neutral" },
  size: { type: String, default: "md" },
  type: { type: String, default: "button" },
  block: { type: Boolean, default: false },
});

const cls = computed(() => ({
  btn: true,
  "btn-primary": props.variant === "primary",
  "btn-danger": props.variant === "danger",
  "btn-ghost": props.variant === "ghost",
  "btn-accent-ghost": props.variant === "accent-ghost",
  "btn-sm": props.size === "sm",
  "btn-block": props.block,
}));
</script>

<template>
  <button :class="cls" :type="type"><slot /></button>
</template>

<style scoped>
.btn-block {
  width: 100%;
}
</style>
