<script setup>
import { computed } from "vue";

const props = defineProps({
  variant: { type: String, default: "primary" }, // primary | soft | ghost | quiet
  size: { type: String, default: "md" }, // sm | md | lg
  block: Boolean,
  loading: Boolean,
  disabled: Boolean,
  type: { type: String, default: "button" },
});

const cls = computed(() => [
  "btn",
  `btn--${props.variant}`,
  props.size !== "md" && `btn--${props.size}`,
  props.block && "btn--block",
]);
const off = computed(() => props.disabled || props.loading);
</script>

<template>
  <button
    :class="cls"
    :type="type"
    :disabled="off"
    :aria-disabled="off"
    :aria-busy="loading || undefined"
  >
    <span v-if="loading" class="spinner" aria-hidden="true" />
    <slot v-else name="icon" />
    <slot />
  </button>
</template>
