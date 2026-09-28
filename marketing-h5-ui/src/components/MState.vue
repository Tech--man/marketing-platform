<script setup>
import Icon from "./Icon.vue";

defineProps({
  variant: { type: String, default: "empty" }, // empty | error | loading
  title: { type: String, default: "" },
  hint: { type: String, default: "" },
  icon: { type: String, default: "box" }, // Icon 名
});
</script>

<template>
  <div class="state">
    <div class="state__icon">
      <span v-if="variant === 'loading'" class="spinner" style="color: var(--c-text-faint)" />
      <Icon v-else :name="variant === 'error' ? 'alert' : icon" :size="26" />
    </div>
    <p v-if="title" class="state__title">{{ title }}</p>
    <p v-if="hint" class="state__hint">{{ hint }}</p>
    <slot />
  </div>
</template>
