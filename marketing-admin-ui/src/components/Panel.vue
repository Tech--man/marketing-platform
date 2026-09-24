<script setup>
/**
 * 卡片容器：表面 + 描边 + 克制的阴影。头部（标题/说明/动作）与主体都是可选插槽。
 * flush 用于表格贴边（去掉主体内边距，让表头自己撑满）。
 */
defineProps({
  title: { type: String, default: "" },
  desc: { type: String, default: "" },
  flush: { type: Boolean, default: false },
});
</script>

<template>
  <section class="panel" :class="{ 'panel--flush': flush }">
    <header
      v-if="title || $slots.desc || $slots.actions"
      class="panel__head"
    >
      <div class="grow">
        <h2 v-if="title" class="panel__title">{{ title }}</h2>
        <slot name="desc">
          <p v-if="desc" class="panel__desc">{{ desc }}</p>
        </slot>
      </div>
      <div v-if="$slots.actions" class="panel__actions">
        <slot name="actions" />
      </div>
    </header>
    <div class="panel__body"><slot /></div>
  </section>
</template>
