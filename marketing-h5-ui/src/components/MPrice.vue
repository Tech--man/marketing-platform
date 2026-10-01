<script setup>
import { computed } from "vue";
import { hasAmt, splitYuan } from "@/utils/format";

const props = defineProps({
  // N-5：value 不给 default: 0——缺省保持 null 走 "--" 分支，别把"没传"当"免费"
  value: { type: [Number, String], default: null },
  size: { type: String, default: "md" }, // sm | md | lg
  strike: { type: [Number, String], default: null }, // 划线原价
  currency: { type: String, default: "¥" },
});

// 读不到金额（null/空串，如试算未落地）显示 -- 且不印货币符：
// 折叠成 ¥0.00 会把"不知道"伪装成"免费"（2026-10-01 审计 P2-7，与 yuan()/后台 TriState 同口径）
const readable = computed(() => hasAmt(props.value));
const parts = computed(() => splitYuan(props.value));
</script>

<template>
  <span class="price" :class="size !== 'md' && `price--${size}`">
    <template v-if="readable">
      <span class="price__cur">{{ currency }}</span><span class="price__int">{{ parts.int }}</span><span
        class="price__dec"
        >.{{ parts.dec }}</span
      >
    </template>
    <template v-else><span class="price__int">--</span></template>
    <span v-if="hasAmt(strike)" class="price__strike">¥{{ Number(strike).toFixed(2) }}</span>
  </span>
</template>
