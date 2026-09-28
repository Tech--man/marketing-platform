<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from "vue";
import { countdown } from "@/utils/format";

const props = defineProps({
  target: { type: [String, Number, Date], default: null },
  // past=true 表示这是"距开始"倒计时；false 表示"距结束"
  toStart: Boolean,
});
const emit = defineEmits(["finish"]);

const tick = ref(Date.now());
let timer = null;

const c = computed(() => countdown(props.target, tick.value));
const boxes = computed(() => {
  const { d, h, m, s } = c.value;
  const p = (n) => String(n).padStart(2, "0");
  const arr = [];
  if (d > 0) arr.push({ v: d, u: "天" });
  arr.push({ v: p(h) }, { sep: true }, { v: p(m) }, { sep: true }, { v: p(s) });
  return arr;
});

function step() {
  const before = c.value.ended;
  tick.value = Date.now();
  if (!before && c.value.ended) emit("finish");
}

onMounted(() => {
  timer = setInterval(step, 1000);
});
onBeforeUnmount(() => timer && clearInterval(timer));
watch(() => props.target, () => (tick.value = Date.now()));
</script>

<template>
  <span v-if="c.ended" class="countdown ended"><slot name="ended">已结束</slot></span>
  <span v-else class="countdown" role="timer" :aria-label="toStart ? '距开始' : '距结束'">
    <template v-for="(b, i) in boxes" :key="i">
      <b v-if="b.sep" class="sep">:</b>
      <span v-else class="box">{{ b.v }}<i v-if="b.u" class="unit">{{ b.u }}</i></span>
    </template>
  </span>
</template>

<style scoped>
.countdown {
  display: inline-flex;
  align-items: baseline;
  gap: 2px;
  font-variant-numeric: tabular-nums;
}
.box {
  display: inline-flex;
  align-items: baseline;
  min-width: 1.7ch;
  justify-content: center;
  font-weight: 700;
}
.unit { font-style: normal; font-size: 0.7em; margin-left: 1px; }
.sep { font-weight: 700; opacity: 0.55; margin: 0 1px; }
.ended { color: var(--c-text-muted); font-weight: 600; }
</style>
