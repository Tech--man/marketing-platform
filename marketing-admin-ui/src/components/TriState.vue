<script setup>
import { computed } from 'vue'

/**
 * ④ 的三态读数：真值 / 判定不了（-1）/ 本形态不适用。
 * 后两种**绝不**显示成数字，尤其不许显示成 0——那会把"读不到"说成"没问题"。
 */
const props = defineProps({
  value: { type: Number, default: null },
  applicable: { type: Boolean, default: true },
  note: { type: String, default: '' },
})

const kind = computed(() => {
  if (!props.applicable) return 'na'
  if (props.value === null || props.value < 0) return 'unknown'
  if (props.value === 0) return 'zero'
  return 'bad'
})

const text = computed(() => {
  if (kind.value === 'na') return '不适用'
  if (kind.value === 'unknown') return '判定不了'
  return String(props.value)
})

// note 优先：后端已经把原因写得很具体（"本形态走 RocketMQ，队列深度在 broker 里"），
// 让人对着一个孤零零的"不适用"猜下一步该看哪里。
const title = computed(() => {
  if (props.note) return props.note
  if (kind.value === 'unknown') return '④ 读不到这个数，不等于没问题'
  return ''
})
</script>

<template>
  <span :class="['tri', kind]" :data-kind="kind" :title="title">{{ text }}</span>
</template>

<!-- 外观交给全局 styles/components.css 的 `.tri[data-kind=…]`：这里只留语义，
     绝不在根 span 内塞图标/前缀（tristate.spec 断言根元素文本严格等于 '0'）。 -->

