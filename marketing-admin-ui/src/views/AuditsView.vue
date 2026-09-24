<script setup>
import { reactive } from 'vue'
import PagedTable from '@/components/PagedTable.vue'

/**
 * 审计页（③ 的留痕面）。只读：这里的每一条都是已经发生过的事，
 * 界面上不提供任何"修一条审计"的入口——那是 ③ 定的边界。
 */
const columns = [
  { prop: 'id', label: 'ID', width: '5rem' },
  { prop: 'actorName', label: '操作人' },
  { prop: 'role', label: '角色' },
  { prop: 'action', label: '动作' },
  { prop: 'resourceType', label: '对象类型' },
  { prop: 'resourceId', label: '对象号' },
  { prop: 'resultCode', label: '结果' },
  { prop: 'costMs', label: '耗时ms' },
  { prop: 'ip', label: '来源 IP' },
  { prop: 'requestSummary', label: '摘要' },
]
const params = reactive({ actorId: '', action: '', resourceType: '', resourceId: '' })
</script>

<template>
  <main>
    <h1>审计</h1>
    <p class="hint">
      摘要已在写入时脱敏（JSON 与 key=value 两种形态、驼峰与下划线都盖），
      所以这里<b>原样显示</b>，不再做任何"猜字段再拼一次"。
    </p>
    <form class="filters" @submit.prevent>
      <label>操作人 id<input v-model="params.actorId" data-field="actorId" /></label>
      <label>动作<input v-model="params.action" data-field="action" placeholder="activity.transition" /></label>
      <label>对象类型<input v-model="params.resourceType" data-field="resourceType" /></label>
      <label>对象号<input v-model="params.resourceId" data-field="resourceId" placeholder="ACT2026001" /></label>
    </form>
    <PagedTable endpoint="/api/admin/audits" :columns="columns" :params="params" />
  </main>
</template>

<style scoped>
h1 {
  margin-top: 0;
}
.filters {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
  margin-bottom: 0.75rem;
}
label {
  display: grid;
  gap: 0.2rem;
  font-size: 13px;
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
.hint {
  color: #909399;
  font-size: 13px;
}
</style>
