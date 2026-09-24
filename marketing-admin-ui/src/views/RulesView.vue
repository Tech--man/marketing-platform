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
 * 优惠规则页（③）。只有**一条**写端点：`POST /api/admin/discount/rules` 是整条 upsert，
 * 新建与启停都走它。这条事实决定了这里的形状——不存在"另一个改状态的端点"，
 * 所以启停也必须把整条规则（含 DSL）带回去，否则等于把规则改成"没有 DSL"。
 */
const s = useSession()
const table = ref(null)
const notice = ref('')
const editor = ref(/** @type {any} */ (null))
const form = ref({})

const columns = [
  { prop: 'ruleNo', label: '规则号' },
  { prop: 'name', label: '名称' },
  { prop: 'activityNo', label: '活动' },
  { prop: 'ruleType', label: '类型' },
  { prop: 'mutexGroup', label: '互斥组' },
  { prop: 'priority', label: '优先级', numeric: true },
  { prop: 'status', label: '状态', slot: 'status' },
  { prop: 'version', label: '版本', numeric: true },
  { prop: 'actions', label: '动作', slot: 'actions', width: '12rem' },
]

/** upsert 是整条覆盖：DSL 字段从 ruleJson 解出来一起回传，不然"改个状态"会把门槛/面额抹掉 */
function bodyFrom(row, patch) {
  let dsl = {}
  try {
    dsl = row.ruleJson ? JSON.parse(row.ruleJson) : {}
  } catch {
    // 解不开就只带界面上有的字段：这里不猜，让后端的校验去说"缺什么"
    dsl = {}
  }
  return {
    ruleNo: row.ruleNo,
    name: row.name,
    activityNo: row.activityNo ?? null,
    type: row.ruleType,
    mutexGroup: row.mutexGroup ?? null,
    priority: row.priority,
    status: row.status,
    ...dsl,
    ...patch,
  }
}

function open(row) {
  editor.value = { row }
  let dsl = {}
  try {
    dsl = row.ruleJson ? JSON.parse(row.ruleJson) : {}
  } catch {
    dsl = {}
  }
  form.value = {
    ruleNo: row.ruleNo,
    name: row.name,
    activityNo: row.activityNo ?? '',
    type: row.ruleType,
    mutexGroup: row.mutexGroup ?? '',
    priority: String(row.priority ?? ''),
    status: row.status,
    ruleJson: JSON.stringify(dsl, null, 2),
  }
  notice.value = ''
}

async function save() {
  const f = form.value
  let dsl = {}
  try {
    dsl = f.ruleJson.trim() ? JSON.parse(f.ruleJson) : {}
  } catch (e) {
    notice.value = `规则 JSON 解析不了：${e.message}`
    return
  }
  try {
    await api.post('/api/admin/discount/rules', {
      ruleNo: f.ruleNo,
      name: f.name,
      activityNo: f.activityNo || null,
      type: f.type,
      mutexGroup: f.mutexGroup || null,
      priority: Number(f.priority),
      status: f.status,
      ...dsl,
    })
    notice.value = '规则已 upsert，并 bump 了版本号：各实例秒级刷到本地快照'
    editor.value = null
    await table.value?.reload()
  } catch (e) {
    notice.value = `保存被拒：${e.message}`
  }
}

async function toggleStatus(row) {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  try {
    await api.post('/api/admin/discount/rules', bodyFrom(row, { status: next }))
    notice.value = `${row.ruleNo} 已${next === 'ENABLED' ? '启用' : '停用'}`
    await table.value?.reload()
  } catch (e) {
    notice.value = `启停被拒：${e.message}`
  }
}
</script>

<template>
  <main class="stack">
    <PageHeader title="优惠规则">
      <template #desc>
        <p v-if="!s.canWrite" class="muted">当前角色只读：写动作在后端也会被拒（40300）。</p>
      </template>
    </PageHeader>

    <p v-if="notice" :class="notice.includes('被拒') || notice.includes('解析') ? 'warn' : 'ok'" data-testid="notice">
      {{ notice }}
    </p>

    <Panel flush>
      <PagedTable ref="table" endpoint="/api/admin/discount/rules" :columns="columns">
        <template #status="{ value }">
          <StatusPill :tone="value === 'ENABLED' ? 'success' : 'neutral'">{{ value }}</StatusPill>
        </template>
        <template #actions="{ row }">
          <Button size="sm" data-act="edit" :disabled="!s.canWrite" @click="open(row)">编辑</Button>
          <Button
            size="sm"
            variant="accent-ghost"
            data-act="toggle-status"
            :disabled="!s.canWrite"
            @click="toggleStatus(row)"
          >
            {{ row.status === 'ENABLED' ? '停用' : '启用' }}
          </Button>
        </template>
      </PagedTable>
    </Panel>

    <section v-if="editor" class="drawer">
      <div class="drawer__head"><h2 class="drawer__title">编辑规则：{{ form.ruleNo }}</h2></div>
      <div class="form-grid">
        <Field label="名称"><input v-model="form.name" class="input" data-field="name" /></Field>
        <Field label="活动号"><input v-model="form.activityNo" class="input" data-field="activityNo" /></Field>
        <Field label="类型"><input v-model="form.type" class="input" data-field="type" disabled /></Field>
        <Field label="互斥组"><input v-model="form.mutexGroup" class="input" data-field="mutexGroup" /></Field>
        <Field label="优先级"><input v-model="form.priority" class="input" data-field="priority" /></Field>
        <Field label="状态">
          <select v-model="form.status" class="select" data-field="status">
            <option value="ENABLED">ENABLED</option>
            <option value="DISABLED">DISABLED</option>
          </select>
        </Field>
      </div>
      <Field label="规则 DSL（JSON，整条 upsert 会把它一起写回）" class="drawer__json">
        <textarea v-model="form.ruleJson" class="textarea" data-field="ruleJson" rows="8" spellcheck="false" />
      </Field>
      <p class="hint">
        改完<b>不能只改状态</b>：这一条端点是整条覆盖，所以界面会把上面这份 DSL 一并带回。
      </p>
      <div class="drawer__foot">
        <Button variant="primary" data-act="save" @click="save()">保存</Button>
        <Button variant="ghost" @click="editor = null">取消</Button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.drawer__json {
  margin-top: var(--space-3);
}
.hint {
  color: var(--c-text-muted);
  font-size: var(--fs-sm);
  line-height: 1.6;
  margin-top: var(--space-3);
}
</style>
