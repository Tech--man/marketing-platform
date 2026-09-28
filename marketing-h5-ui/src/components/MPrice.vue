<script setup>
import { computed } from "vue";
import { splitYuan } from "@/utils/format";

const props = defineProps({
  value: { type: [Number, String], default: 0 },
  size: { type: String, default: "md" }, // sm | md | lg
  strike: { type: [Number, String], default: null }, // 划线原价
  currency: { type: String, default: "¥" },
});

const parts = computed(() => splitYuan(props.value));
</script>

<template>
  <span class="price" :class="size !== 'md' && `price--${size}`">
    <span class="price__cur">{{ currency }}</span><span class="price__int">{{ parts.int }}</span><span
      class="price__dec"
      >.{{ parts.dec }}</span
    >
    <span v-if="strike != null" class="price__strike">¥{{ Number(strike).toFixed(2) }}</span>
  </span>
</template>
