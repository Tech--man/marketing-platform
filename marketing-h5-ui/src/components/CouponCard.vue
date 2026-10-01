<script setup>
import MPrice from "./MPrice.vue";
import Icon from "./Icon.vue";
import { hasAmt } from "@/utils/format";

// N-5：faceValue/threshold 不给 default: 0——缺省（父组件没传/接口没回）保持
// undefined，交给 MPrice/hasAmt 显示 "--"/"无门槛"；default: 0 会把"读不到"
// 伪装成"真 0 元券"。
defineProps({
  faceValue: { type: [Number, String], default: null },
  threshold: { type: [Number, String], default: null },
  name: { type: String, default: "" },
  scene: { type: String, default: "" },
  expireText: { type: String, default: "" },
  tone: { type: String, default: "brand" }, // brand | gold | graphite
  dim: Boolean,
});
</script>

<template>
  <div class="cp" :class="[`cp--${tone}`, dim && 'cp--dim']">
    <div class="cp__value">
      <MPrice :value="faceValue" />
      <span class="cp__cond">{{ hasAmt(threshold) && Number(threshold) > 0 ? `满 ${Number(threshold).toFixed(0)} 可用` : "无门槛" }}</span>
    </div>
    <span class="cp__perf" aria-hidden="true" />
    <div class="cp__body">
      <div class="cp__name truncate">{{ name }}</div>
      <div class="cp__scene truncate">{{ scene }}</div>
      <div v-if="expireText" class="cp__expire">
        <Icon name="clock" :size="12" />
        <span>{{ expireText }}</span>
      </div>
    </div>
    <div class="cp__act"><slot /></div>
  </div>
</template>

<style scoped>
/* Apple Wallet 的票证形状：左侧带色的面值区 + 打孔分隔线 + 右侧信息。
   色只用淡染，不铺饱和色。 */
.cp {
  display: flex;
  align-items: stretch;
  background: var(--c-surface);
  border: 1px solid var(--hairline);
  border-radius: var(--radius-lg);
  overflow: hidden;
  min-height: 92px;
}
.cp__value {
  flex: none;
  width: 106px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  padding: var(--space-3) var(--space-2);
  background: var(--c-brand-soft);
  color: var(--c-brand);
}
.cp__value :deep(.price),
.cp__value :deep(.price__cur),
.cp__value :deep(.price__dec) { color: inherit; }
.cp--gold .cp__value { background: var(--c-gold-soft); color: var(--c-gold); }
.cp--graphite .cp__value { background: var(--c-surface-3); color: var(--c-text); }
.cp__cond { font-size: var(--fs-xs); opacity: 0.8; font-weight: 500; }
.cp__perf {
  flex: none;
  width: 0;
  border-left: 1px dashed var(--c-border);
  margin: var(--space-3) 0;
}
.cp__body { flex: 1; min-width: 0; padding: var(--space-4) var(--space-4); display: flex; flex-direction: column; justify-content: center; gap: 3px; }
.cp__name { font-weight: 600; font-size: var(--fs-base); letter-spacing: var(--tracking-body); }
.cp__scene { font-size: var(--fs-sm); color: var(--c-text-muted); }
.cp__expire { display: flex; align-items: center; gap: 4px; font-size: var(--fs-xs); color: var(--c-text-faint); margin-top: 2px; }
.cp__act { flex: none; display: flex; align-items: center; padding-right: var(--space-4); }
.cp--dim { opacity: 0.5; }
.cp--dim .cp__value { background: var(--c-surface-3); color: var(--c-text-muted); }
</style>
