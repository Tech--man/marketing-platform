<script setup>
import { ref } from 'vue'
import { api } from '@/api/client'
import { useSession } from '@/stores/session'
import PagedTable from '@/components/PagedTable.vue'

/**
 * 秒杀活动页（③）。这里的关键词是<b>分桶</b>：改总库存只有在 `ONLINE` 时
 * 才会在同一条事务里按 `total - sold` 重建 16 个桶，非 ONLINE 只改 DB。
 * 这句话必须由界面说出来——否则运营会以为改了数字票就放出来了。
 */
const s = useSession()
const table = ref(null)
const notice = ref('')
const editor = ref(/** @type {any} */ (null))
const form = ref({})

const columns = [
  { prop: 'activityNo', label: '活动号' },
  { prop: 'itemName', label: '商品' },
  { prop: 'seckillPrice', label: '秒杀价' },
  { prop: 'totalStock', label: '总库存' },
  { prop: 'soldStock', label: '已售' },
  { prop: 'buckets', label: '分桶数' },
  { prop: 'status', label: '状态' },
  { prop: 'version', label: '版本' },
  { prop: 'actions', label: '动作', slot: 'actions', width: '12rem' },
]

function openStock(row) {
  editor.value = { row }
  form.value = { totalStock: String(row.totalStock) }
  notice.value = ''
}

async function saveStock() {
  const { row } = editor.value
  try {
    await api.put(`/api/admin/seckill/activities/${row.activityNo}/stock`, {
      totalStock: Number(form.value.totalStock),
      version: row.version,
    })
    notice.value =
      row.status === 'ONLINE'
        ? '总库存已改，并在同一事务里按 total-sold 重建分桶'
        : '总库存已写库；当前不是 ONLINE，分桶没有重建（放票要另做上线动作）'
    editor.value = null
    await table.value?.reload()
  } catch (e) {
    notice.value = `改库存被拒：${e.message}`
  }
}

async function toggleStatus(row) {
  const next = row.status === 'ONLINE' ? 'OFFLINE' : 'ONLINE'
  try {
    await api.put(`/api/admin/seckill/activities/${row.activityNo}/status`, {
      status: next,
      version: row.version,
    })
    notice.value = next === 'ONLINE' ? '已上线（开闸）' : '已下线'
    await table.value?.reload()
  } catch (e) {
    notice.value = `上下线被拒：${e.message}`
  }
}
</script>

<template>
  <main>
    <header>
      <h1>秒杀活动</h1>
      <p v-if="!s.canWrite" class="hint">当前角色只读：写动作在后端也会被拒（40300）。</p>
    </header>
    <p v-if="notice" :class="notice.includes('被拒') ? 'warn' : 'ok'" data-testid="notice">
      {{ notice }}
    </p>

    <PagedTable ref="table" endpoint="/api/admin/seckill/activities" :columns="columns">
      <template #actions="{ row }">
        <button type="button" data-act="edit-stock" :disabled="!s.canWrite" @click="openStock(row)">
          改库存
        </button>
        <button
          type="button"
          data-act="toggle-status"
          :disabled="!s.canWrite"
          @click="toggleStatus(row)"
        >
          {{ row.status === 'ONLINE' ? '下线' : '上线（开闸）' }}
        </button>
      </template>
    </PagedTable>

    <section v-if="editor" class="drawer">
      <h2>改总库存：{{ editor.row.activityNo }}（已售 {{ editor.row.soldStock }}）</h2>
      <label>
        总库存
        <input v-model="form.totalStock" data-field="totalStock" />
      </label>
      <p v-if="editor.row.status === 'ONLINE'" class="ok">
        当前 <b>ONLINE</b>：保存后会在<b>同事务</b>里按 <code>total - sold</code> 重建
        {{ editor.row.buckets }} 个分桶，Redis 余量立刻是新口径。
      </p>
      <p v-else class="warn">
        当前 <b>{{ editor.row.status }}</b>：这次<b>不会重建分桶</b>，只改库。
        要把票放出来需要另做一次"上线（开闸）"——这里不顺手帮你开闸。
      </p>
      <p>
        <button type="button" data-act="save" @click="saveStock()">保存</button>
        <button type="button" @click="editor = null">取消</button>
      </p>
    </section>
  </main>
</template>

<style scoped>
h1 {
  margin-top: 0;
}
table {
  border-collapse: collapse;
  width: 100%;
  font-size: 13px;
}
th,
td {
  border-bottom: 1px solid #ebeef5;
  padding: 0.35rem 0.5rem;
  text-align: left;
}
.drawer {
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  padding: 0.75rem 1rem;
  margin-top: 1rem;
}
.drawer label {
  display: grid;
  gap: 0.2rem;
  margin-bottom: 0.5rem;
}
button {
  font: inherit;
  margin-right: 0.35rem;
}
.ok,
.warn {
  font-size: 13px;
}
.ok {
  color: #67c23a;
}
.warn {
  color: #e6a23c;
}
p.warn {
  font-weight: 600;
}
</style>
