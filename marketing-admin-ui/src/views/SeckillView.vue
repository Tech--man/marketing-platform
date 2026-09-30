<script setup>
import { ref } from 'vue'
import { api } from '@/api/client'
import { useSession } from '@/stores/session'
import PagedTable from '@/components/PagedTable.vue'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import Field from '@/components/Field.vue'
import Button from '@/components/Button.vue'
import StatusPill from '@/components/StatusPill.vue'

/**
 * 秒杀活动页（③）。这里的关键词是<b>分桶</b>：改总库存只有在 `ONLINE` 时
 * 才会在同一条事务里按 `total - sold` 重建 16 个桶，非 ONLINE 只改 DB。
 * 这句话必须由界面说出来——否则运营会以为改了数字票就放出来了。
 */
const s = useSession()
const table = ref(null)
const notice = ref('')
const editor = ref(/** @type {any} */ (null))
const confirmToggle = ref(/** @type {any} */ (null))
const form = ref({})

const columns = [
  { prop: 'activityNo', label: '活动号' },
  { prop: 'itemName', label: '商品' },
  { prop: 'seckillPrice', label: '秒杀价', numeric: true },
  { prop: 'totalStock', label: '总库存', numeric: true },
  { prop: 'soldStock', label: '已售', numeric: true },
  { prop: 'buckets', label: '分桶数', numeric: true },
  { prop: 'status', label: '状态', slot: 'status' },
  { prop: 'version', label: '版本', numeric: true },
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

// W4（2026-09-30 第二轮复审）：上下线是开闸/关闸动作，先落确认抽屉（误触代价 = 凭空开闸放抢）
function askToggle(row) {
  confirmToggle.value = row
  notice.value = ''
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
  } finally {
    confirmToggle.value = null
  }
}
</script>

<template>
  <main class="stack">
    <PageHeader title="秒杀活动">
      <template #desc>
        <p v-if="!s.canWrite" class="muted">当前角色只读：写动作在后端也会被拒（40300）。</p>
      </template>
    </PageHeader>

    <p v-if="notice" :class="notice.includes('被拒') ? 'warn' : 'ok'" data-testid="notice">
      {{ notice }}
    </p>

    <Panel flush>
      <PagedTable ref="table" endpoint="/api/admin/seckill/activities" :columns="columns">
        <template #status="{ value }">
          <StatusPill :tone="value === 'ONLINE' ? 'success' : 'neutral'">{{ value }}</StatusPill>
        </template>
        <template #actions="{ row }">
          <Button size="sm" data-act="edit-stock" :disabled="!s.canWrite" @click="openStock(row)">
            改库存
          </Button>
          <Button
            size="sm"
            variant="accent-ghost"
            data-act="toggle-status"
            :disabled="!s.canWrite"
            @click="askToggle(row)"
          >
            {{ row.status === 'ONLINE' ? '下线' : '上线（开闸）' }}
          </Button>
        </template>
      </PagedTable>
    </Panel>

    <section v-if="editor" class="drawer">
      <div class="drawer__head">
        <h2 class="drawer__title">改总库存：{{ editor.row.activityNo }}（已售 {{ editor.row.soldStock }}）</h2>
      </div>
      <div class="form-grid">
        <Field label="总库存">
          <input v-model="form.totalStock" class="input" data-field="totalStock" />
        </Field>
      </div>
      <p v-if="editor.row.status === 'ONLINE'" class="ok">
        当前 <b>ONLINE</b>：保存后会在<b>同事务</b>里按 <code>total - sold</code> 重建
        {{ editor.row.buckets }} 个分桶，Redis 余量立刻是新口径。
      </p>
      <p v-else class="warn">
        当前 <b>{{ editor.row.status }}</b>：这次<b>不会重建分桶</b>，只改库。
        要把票放出来需要另做一次"上线（开闸）"——这里不顺手帮你开闸。
      </p>
      <div class="drawer__foot">
        <Button variant="primary" data-act="save" @click="saveStock()">保存</Button>
        <Button variant="ghost" @click="editor = null">取消</Button>
      </div>
    </section>

    <section v-if="confirmToggle" class="drawer">
      <div class="drawer__head">
        <h2 class="drawer__title">
          {{ confirmToggle.status === 'ONLINE' ? '下线' : '上线（开闸）' }}：{{ confirmToggle.activityNo }}
        </h2>
      </div>
      <p v-if="confirmToggle.status !== 'ONLINE'" class="warn" data-testid="toggle-confirm-warn">
        上线 = <b>开闸放抢</b>：分桶按 total-sold 立即生效，确认价格与库存已复核。
      </p>
      <p v-else class="warn" data-testid="toggle-confirm-warn">
        下线后新请求被拒，在途抢购会跑完（已扣名额不回收）。
      </p>
      <div class="drawer__foot">
        <Button variant="danger" data-act="confirm-toggle" @click="toggleStatus(confirmToggle)">
          确认执行
        </Button>
        <Button variant="ghost" @click="confirmToggle = null">取消</Button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.ok,
.warn {
  margin-top: var(--space-3);
  line-height: 1.6;
}
</style>
