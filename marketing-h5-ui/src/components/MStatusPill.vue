<script setup>
defineProps({
  tone: { type: String, default: "neutral" }, // neutral | brand | success | warning | danger | info | gold | outline
  pulse: Boolean,
});
</script>

<template>
  <span class="pill" :class="tone !== 'neutral' && `pill--${tone}`">
    <span v-if="pulse" class="dot dot--pulse" />
    <slot />
  </span>
</template>
