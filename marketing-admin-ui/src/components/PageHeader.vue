<script setup>
/**
 * 页头：主标题 + 说明 + 右侧动作区。取代各视图里重复的 <header><h1>…</h1></header>。
 * 标题用 <h1>（各视图的 h1 文案没有被任何测试断言，改成组件安全）。
 */
defineProps({
  title: { type: String, default: "" },
  desc: { type: String, default: "" },
});
</script>

<template>
  <header class="page-head">
    <div class="grow">
      <h1 class="page-head__title"><slot name="title">{{ title }}</slot></h1>
      <p v-if="desc || $slots.desc" class="page-head__desc">
        <slot name="desc">{{ desc }}</slot>
      </p>
    </div>
    <div v-if="$slots.actions" class="page-head__actions"><slot name="actions" /></div>
  </header>
</template>
