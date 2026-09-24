<script setup>
import { reactive } from 'vue'
import PagedTable from '@/components/PagedTable.vue'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import Field from '@/components/Field.vue'
import StatusPill from '@/components/StatusPill.vue'

/**
 * 审计页（③ 的留痕面）。只读：这里的每一条都是已经发生过的事，
 * 界面上不提供任何"修一条审计"的入口——那是 ③ 定的边界。
 */
const columns = [
  { prop: 'id', label: 'ID', width: '5rem', numeric: true },
  { prop: 'actorName', label: '操作人' },
  { prop: 'role', label: '角色' },
  { prop: 'action', label: '动作' },
  { prop: 'resourceType', label: '对象类型' },
  { prop: 'resourceId', label: '对象号' },
  { prop: 'resultCode', label: '结果', slot: 'resultCode' },
  { prop: 'costMs', label: '耗时ms', numeric: true },
  { prop: 'ip', label: '来源 IP' },
  { prop: 'requestSummary', label: '摘要' },
]
const params = reactive({ actorId: '', action: '', resourceType: '', resourceId: '' })
</script>

<template>
  <main class="stack">
    <PageHeader title="审计" desc="后台写操作的留痕。摘要已在写入时脱敏（JSON 与 key=value 两种形态、驼峰与下划线都盖），所以这里原样显示，不再做任何“猜字段再拼一次”。" />

    <div class="toolbar">
      <Field label="操作人 id"><input v-model="params.actorId" class="input" data-field="actorId" /></Field>
      <Field label="动作">
        <input v-model="params.action" class="input" data-field="action" placeholder="activity.transition" />
      </Field>
      <Field label="对象类型"><input v-model="params.resourceType" class="input" data-field="resourceType" /></Field>
      <Field label="对象号">
        <input v-model="params.resourceId" class="input" data-field="resourceId" placeholder="ACT2026001" />
      </Field>
    </div>

    <Panel flush>
      <PagedTable endpoint="/api/admin/audits" :columns="columns" :params="params">
        <template #resultCode="{ value }">
          <StatusPill :tone="value === 0 || value === '0' ? 'success' : 'danger'" :dot="false">{{ value }}</StatusPill>
        </template>
      </PagedTable>
    </Panel>
  </main>
</template>
