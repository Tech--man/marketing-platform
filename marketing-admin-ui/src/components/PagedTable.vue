<script setup>
import { ref, watch } from 'vue'
import { api } from '@/api/client'
import Button from '@/components/Button.vue'

/**
 * 列表页的共用基座：翻页 + 过滤参数 + 三种非正常态（加载中 / 出错 / 空）。
 *
 * <p>出错时必须把后端 message 原样显示，不能退化成"加载失败"：⑤/③ 的 40000 文案里
 * 写着允许区间、41008 里写着"他人已更新"，藏起来就等于让人盲试。
 * 空态也一样——"这个查询没有行"与"这个进程还没上报"是两件事（后者由 ④ 的三态负责）。</p>
 */
const props = defineProps({
  endpoint: { type: String, required: true },
  columns: { type: Array, required: true },
  params: { type: Object, default: () => ({}) },
  pageSize: { type: Number, default: 20 },
})

const rows = ref([])
const total = ref(0)
const page = ref(1)
const busy = ref(false)
const error = ref('')
let seq = 0

function query() {
  const q = new URLSearchParams({ page: String(page.value), size: String(props.pageSize) })
  for (const [k, v] of Object.entries(props.params)) {
    if (v !== '' && v !== null && v !== undefined) q.set(k, v)
  }
  return `${props.endpoint}?${q.toString()}`
}

async function load() {
  // W4（2026-09-30 第二轮复审）：请求序号防乱序——快速翻页/切关键词时两个在飞
  // 请求"先到先赢"，慢的旧响应后到会覆盖新状态（页码第 2 页、数据第 1 页）。
  // 落盘前比对序号，只有最新一次请求的结果才生效。
  const mySeq = ++seq
  busy.value = true
  error.value = ''
  try {
    const d = await api.get(query())
    if (seq !== mySeq) return
    rows.value = d.records ?? []
    total.value = d.total ?? rows.value.length
  } catch (e) {
    if (seq !== mySeq) return
    rows.value = []
    total.value = 0
    error.value = e.message || String(e)
  } finally {
    if (seq === mySeq) busy.value = false
  }
}

watch(() => [props.endpoint, JSON.stringify(props.params)], () => {
  page.value = 1
  load()
})

function go(p) {
  page.value = p
  load()
}

function cell(row, col) {
  const raw = row[col.prop]
  return col.formatter ? col.formatter(raw, row) : raw === null || raw === undefined ? '—' : raw
}

defineExpose({ reload: load })
load()
</script>

<template>
  <div class="paged">
    <p v-if="busy" class="loading" data-testid="loading">加载中…</p>
    <p v-else-if="error" class="err" data-testid="error">
      读不到：{{ error }}
      <Button size="sm" variant="ghost" @click="load">重试</Button>
    </p>
    <template v-else>
      <div class="data-wrap">
        <table class="data">
          <thead>
            <tr>
              <th
                v-for="c in columns"
                :key="c.prop"
                :class="{ num: c.numeric }"
                :style="c.width ? { width: c.width } : {}"
              >
                {{ c.label }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!rows.length">
              <td :colspan="columns.length" class="empty">没有符合条件的行</td>
            </tr>
            <tr v-for="(r, i) in rows" :key="i" data-row>
              <td
                v-for="c in columns"
                :key="c.prop"
                :class="{ num: c.numeric }"
                :data-prop="c.prop"
              >
                <slot v-if="c.slot" :name="c.slot" :row="r" :value="r[c.prop]" />
                <template v-else>{{ cell(r, c) }}</template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="pager">
        <span class="pager__count">共 {{ total }} 行</span>
        <Button size="sm" variant="ghost" :disabled="page <= 1" data-act="prev" @click="go(page - 1)">
          上一页
        </Button>
        <span class="pager__page">{{ page }}</span>
        <Button
          size="sm"
          variant="ghost"
          :disabled="page * pageSize >= total"
          data-act="next"
          @click="go(page + 1)"
        >
          下一页
        </Button>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.loading {
  color: var(--c-text-muted);
  font-size: var(--fs-sm);
}
.err {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3);
  border-radius: var(--radius-md);
  background: var(--c-danger-soft);
  color: var(--c-danger);
  font-size: var(--fs-sm);
}
.empty {
  color: var(--c-text-muted);
  text-align: center;
  padding: var(--space-7) var(--space-4);
}
</style>
