<script setup>
/**
 * 表单字段：说明在上、控件在下；用 <label> 包裹控件形成隐式关联（无障碍）。
 * 真正的 <input/select/textarea data-field=..> 由调用方放进默认插槽，
 * 所以测试里 `data-field` 的取值、`.element.value`、`<select><option>` 断言都不受影响。
 */
defineProps({
  label: { type: String, default: "" },
  hint: { type: String, default: "" },
});
</script>

<template>
  <label class="field">
    <span v-if="label || $slots.label" class="field__label">
      <slot name="label">{{ label }}</slot>
    </span>
    <slot />
    <span v-if="hint || $slots.hint" class="field__hint">
      <slot name="hint">{{ hint }}</slot>
    </span>
  </label>
</template>
