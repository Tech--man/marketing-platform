<script setup>
import { ref, watch } from 'vue'
import { api } from '@/api/client'

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

function query() {
  const q = new URLSearchParams({ page: String(page.value), size: String(props.pageSize) })
  for (const [k, v] of Object.entries(props.params)) {
    if (v !== '' && v !== null && v !== undefined) q.set(k, v)
  }
  return `${props.endpoint}?${q.toString()}`
}

async function load() {
  busy.value = true
  error.value = ''
  try {
    const d = await api.get(query())
    rows.value = d.records ?? []
    total.value = d.total ?? rows.value.length
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = e.message || String(e)
  } finally {
    busy.value = false
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
    <p v-if="busy" data-testid="loading">加载中…</p>
    <p v-else-if="error" data-testid="error" class="err">
      读不到：{{ error }}
      <button type="button" @click="load">重试</button>
    </p>
    <template v-else>
      <table>
        <thead>
          <tr>
            <th v-for="c in columns" :key="c.prop" :style="c.width ? { width: c.width } : {}">
              {{ c.label }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!rows.length">
            <td :colspan="columns.length" class="empty">没有符合条件的行</td>
          </tr>
          <tr v-for="(r, i) in rows" :key="i" data-testid="row">
            <td v-for="c in columns" :key="c.prop" :data-prop="c.prop">
              <slot v-if="c.slot" :name="c.slot" :row="r" :value="r[c.prop]" />
              <template v-else>{{ cell(r, c) }}</template>
            </td>
          </tr>
        </tbody>
      </table>
      <footer class="pager">
        <span>共 {{ total }} 行 · 第 {{ page }} 页</span>
        <button type="button" :disabled="page <= 1" data-act="prev" @click="go(page - 1)">
          上一页
        </button>
        <button
          type="button"
          :disabled="page * pageSize >= total"
          data-act="next"
          @click="go(page + 1)"
        >
          下一页
        </button>
      </footer>
    </template>
  </div>
</template>

<style scoped>
table {
  border-collapse: collapse;
  width: 100%;
  font-size: 14px;
}
th,
td {
  border-bottom: 1px solid #ebeef5;
  padding: 0.4rem 0.5rem;
  text-align: left;
}
.err {
  color: #f56c6c;
}
.empty {
  color: #909399;
}
.pager {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  margin-top: 0.5rem;
  font-size: 13px;
  color: #606266;
}
</style>
