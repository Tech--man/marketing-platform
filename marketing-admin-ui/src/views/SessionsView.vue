<script setup>
import { ref } from 'vue'
import { api } from '@/api/client'
import { useSession } from '@/stores/session'
import PagedTable from '@/components/PagedTable.vue'

/**
 * 在线会话页。<code>mine</code> 两种取值是两条不同权限：
 * 不带 mine 是"看所有人"（只给 admin），带 mine 是"看我自己的设备"。
 */
const s = useSession()
const table = ref(null)
const mine = ref(false)
const notice = ref('')

const columns = [
  { prop: 'username', label: '账号' },
  { prop: 'loginIp', label: '登录 IP' },
  { prop: 'userAgent', label: '客户端' },
  { prop: 'expireAt', label: '过期时间' },
  { prop: 'jti', label: '会话 id' },
  { prop: 'actions', label: '动作', slot: 'actions', width: '7rem' },
]

async function kick(row) {
  try {
    await api.del(`/api/admin/sessions/${encodeURIComponent(row.jti)}`)
    // 被踢的那一端下一发请求会收到 40102，而前端**不会**替它重放那一发
    notice.value = `已吊销 ${row.username} 在 ${row.loginIp || '未知来源'} 的会话`
    await table.value?.reload()
  } catch (e) {
    notice.value = `吊销失败：${e.message}`
  }
}
</script>

<template>
  <main>
    <h1>在线会话</h1>
    <label v-if="s.canWrite" class="chk">
      <input v-model="mine" type="checkbox" data-field="mine" @change="table?.reload()" />
      只看我自己的会话（不勾则需要 admin 角色）
    </label>
    <p v-else class="hint">当前角色只能看自己的会话。</p>
    <p v-if="notice" :class="notice.includes('失败') ? 'warn' : 'ok'" data-testid="notice">
      {{ notice }}
    </p>

    <PagedTable
      ref="table"
      endpoint="/api/admin/sessions"
      :columns="columns"
      :params="{ mine: mine ? 'true' : '' }"
    >
      <template #actions="{ row }">
        <button v-if="s.canWrite" type="button" data-act="kick" @click="kick(row)">下线</button>
      </template>
    </PagedTable>
  </main>
</template>

<style scoped>
h1 {
  margin-top: 0;
}
.chk {
  display: flex;
  gap: 0.35rem;
  align-items: center;
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
button {
  font: inherit;
}
.hint {
  color: #909399;
  font-size: 13px;
}
.ok {
  color: #67c23a;
}
.warn {
  color: #f56c6c;
}
</style>
