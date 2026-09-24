<script setup>
import { ref } from 'vue'
import { api } from '@/api/client'
import { useSession } from '@/stores/session'
import PagedTable from '@/components/PagedTable.vue'

/**
 * 账号页（③ 的 admin 面）。停用会**同时作废该账号的全部会话**，
 * 这句话必须出现在按下去之前——否则被停的人正开着页面，而运营不知道为什么他忽然掉线。
 */
const s = useSession()
const table = ref(null)
const params = ref({ keyword: '' })
const notice = ref('')
const confirmRow = ref(/** @type {any} */ (null))

const columns = [
  { prop: 'id', label: 'ID', width: '4rem' },
  { prop: 'username', label: '账号' },
  { prop: 'displayName', label: '显示名' },
  { prop: 'role', label: '角色' },
  { prop: 'status', label: '状态' },
  { prop: 'locked', label: '锁定', formatter: (v) => (v ? '是' : '否') },
  { prop: 'actions', label: '动作', slot: 'actions', width: '8rem' },
]

async function apply(status) {
  const row = confirmRow.value
  try {
    await api.put(`/api/admin/users/${row.id}/status?status=${status}`)
    notice.value =
      status === 'INACTIVE'
        ? `已停用 ${row.username}，其全部会话同时作废`
        : `已启用 ${row.username}`
    confirmRow.value = null
    await table.value?.reload()
  } catch (e) {
    notice.value = `操作被拒：${e.message}`
  }
}
</script>

<template>
  <main>
    <h1>账号</h1>
    <form class="row" @submit.prevent="table?.reload()">
      <label>
        按账号/显示名搜索
        <input v-model="params.keyword" data-field="keyword" />
      </label>
      <button type="submit">查询</button>
    </form>
    <p v-if="notice" :class="notice.includes('被拒') ? 'warn' : 'ok'" data-testid="notice">
      {{ notice }}
    </p>

    <PagedTable ref="table" endpoint="/api/admin/users" :columns="columns" :params="params">
      <template #actions="{ row }">
        <button
          v-if="s.canWrite && row.status === 'ACTIVE'"
          type="button"
          data-act="disable"
          @click="confirmRow = { id: row.id, username: row.username }"
        >
          停用
        </button>
        <button
          v-if="s.canWrite && row.status !== 'ACTIVE'"
          type="button"
          data-act="enable"
          @click="confirmRow = { id: row.id, username: row.username, enable: true }"
        >
          启用
        </button>
      </template>
    </PagedTable>

    <section v-if="confirmRow" class="drawer">
      <p>
        {{ confirmRow.enable ? `启用 ${confirmRow.username}？` : '' }}
      </p>
      <p v-if="!confirmRow.enable">
        停用 <b>{{ confirmRow.username }}</b> 会把它<b>当前所有在线会话一并作废</b>：
        那个人正在开的页面下一发请求就会收到 40102 并被踢回登录页。
      </p>
      <p>
        <button
          type="button"
          data-act="confirm"
          @click="apply(confirmRow.enable ? 'ACTIVE' : 'INACTIVE')"
        >
          确认
        </button>
        <button type="button" @click="confirmRow = null">取消</button>
      </p>
    </section>
  </main>
</template>

<style scoped>
h1 {
  margin-top: 0;
}
.row {
  display: flex;
  gap: 0.75rem;
  align-items: end;
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
.drawer {
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  padding: 0.75rem 1rem;
  margin-top: 1rem;
}
button {
  font: inherit;
  margin-right: 0.35rem;
}
.ok {
  color: #67c23a;
}
.warn {
  color: #f56c6c;
}
</style>
