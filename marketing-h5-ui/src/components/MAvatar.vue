<script setup>
import { computed } from "vue";
const props = defineProps({
  hue: { type: Number, default: 20 },
  label: { type: String, default: "" },
  size: { type: Number, default: 44 },
});
const bg = computed(
  () => `linear-gradient(160deg, hsl(${props.hue} 34% 62%), hsl(${(props.hue + 24) % 360} 32% 48%))`
);
const initial = computed(() => (props.label || "客").slice(0, 1));
</script>

<template>
  <span class="av" :style="{ background: bg, width: size + 'px', height: size + 'px', fontSize: size * 0.44 + 'px' }" aria-hidden="true">
    {{ initial }}
  </span>
</template>

<style scoped>
.av {
  display: inline-grid;
  place-items: center;
  border-radius: var(--radius-pill);
  color: #fff;
  font-weight: 700;
  flex: none;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.18);
}
</style>
