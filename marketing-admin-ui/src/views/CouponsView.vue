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
 * 券模板页（③）。端点是 `/api/admin/coupon/templates`，与活动的 `/api/admin/activities`
 * 不同构，所以 URL 写在这里而不是拼出来。
 */
const s = useSession()
const table = ref(null)
const notice = ref('')
const editor = ref(/** @type {any} */ (null))
const form = ref({})

const columns = [
  { prop: 'templateNo', label: '模板号' },
  { prop: 'name', label: '名称' },
  { prop: 'couponType', label: '类型' },
  { prop: 'faceValue', label: '面额', numeric: true },
  { prop: 'thresholdAmount', label: '门槛', numeric: true },
  { prop: 'totalStock', label: '总库存', numeric: true },
  { prop: 'perUserLimit', label: '每人限领', numeric: true },
  { prop: 'validDays', label: '有效天数', numeric: true },
  { prop: 'status', label: '状态', slot: 'status' },
  { prop: 'version', label: '版本', numeric: true },
  { prop: 'actions', label: '动作', slot: 'actions', width: '12rem' },
]

function openStock(row) {
  editor.value = { kind: 'stock', row }
  form.value = { totalStock: String(row.totalStock) }
  notice.value = ''
}

async function saveStock() {
  const { row } = editor.value
  try {
    // body 只有两个字段：多带一个就会把后端的 @NotNull 校验绕过去（version 缺失=放弃乐观锁）
    await api.put(`/api/admin/coupon/templates/${row.templateNo}/stock`, {
      totalStock: Number(form.value.totalStock),
      version: row.version,
    })
    notice.value = '库存已改，并在新值足够时补预热缓存'
    editor.value = null
    await table.value?.reload()
  } catch (e) {
    // 低于已发放数量是 40000：表单**不清空**，人要看见自己填了什么被拒了
    notice.value = `改库存被拒：${e.message}`
  }
}

async function toggleStatus(row) {
  const next = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  try {
    await api.put(`/api/admin/coupon/templates/${row.templateNo}/status`, {
      status: next,
      version: row.version,
    })
    // 上线只 warmIfAbsent：已发出的券不会被收回来，这句话必须说，
    // 否则运营会以为"再上线一次能把发错的券收回库存"
    notice.value =
      next === 'ACTIVE' ? '已上线（只补预热缺失的缓存，不会收回已发出的券）' : '已下线'
    await table.value?.reload()
  } catch (e) {
    notice.value = `上下线被拒：${e.message}`
  }
}
</script>

<template>
  <main class="stack">
    <PageHeader title="券模板">
      <template #desc>
        <p v-if="!s.canWrite" class="muted">当前角色只读：写动作在后端也会被拒（40300）。</p>
      </template>
    </PageHeader>

    <p v-if="notice" :class="notice.includes('被拒') ? 'warn' : 'ok'" data-testid="notice">
      {{ notice }}
    </p>

    <Panel flush>
      <PagedTable ref="table" endpoint="/api/admin/coupon/templates" :columns="columns">
        <template #status="{ value }">
          <StatusPill :tone="value === 'ACTIVE' ? 'success' : 'neutral'">{{ value }}</StatusPill>
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
            @click="toggleStatus(row)"
          >
            {{ row.status === 'ACTIVE' ? '下线' : '上线' }}
          </Button>
        </template>
      </PagedTable>
    </Panel>

    <section v-if="editor" class="drawer">
      <div class="drawer__head"><h2 class="drawer__title">改总库存：{{ editor.row.templateNo }}</h2></div>
      <div class="form-grid">
        <Field label="总库存">
          <input v-model="form.totalStock" class="input" data-field="totalStock" />
        </Field>
      </div>
      <p class="hint">
        低于<b>已发放数量</b>会被写侧直接拒（40000）——那是真实已经发出去的券，不能被一次改动抹掉。
      </p>
      <div class="drawer__foot">
        <Button variant="primary" data-act="save" @click="saveStock()">保存</Button>
        <Button variant="ghost" @click="editor = null">取消</Button>
      </div>
    </section>
  </main>
</template>
