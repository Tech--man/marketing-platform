<script setup>
import { ref } from 'vue'
import { api } from '@/api/client'
import { useSession } from '@/stores/session'
import PagedTable from '@/components/PagedTable.vue'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import Button from '@/components/Button.vue'

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
  <main class="stack">
    <PageHeader title="在线会话" desc="当前持有效 token 的登录会话。吊销即刻生效，被吊销端下一发请求收到 40102。" />

    <div class="toolbar">
      <label v-if="s.canWrite" class="check">
        <input v-model="mine" type="checkbox" data-field="mine" @change="table?.reload()" />
        <span>只看我自己的会话（不勾则需要 admin 角色）</span>
      </label>
      <p v-else class="hint">当前角色只能看自己的会话。</p>
    </div>

    <p v-if="notice" :class="notice.includes('失败') ? 'warn' : 'ok'" data-testid="notice">
      {{ notice }}
    </p>

    <Panel flush>
      <PagedTable
        ref="table"
        endpoint="/api/admin/sessions"
        :columns="columns"
        :params="{ mine: mine ? 'true' : '' }"
      >
        <template #actions="{ row }">
          <Button v-if="s.canWrite" size="sm" variant="danger" data-act="kick" @click="kick(row)">
            下线
          </Button>
        </template>
      </PagedTable>
    </Panel>
  </main>
</template>

<style scoped>
.check {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--fs-sm);
  color: var(--c-text-2);
  cursor: pointer;
  user-select: none;
}
.check input {
  width: 16px;
  height: 16px;
  accent-color: var(--c-accent);
  cursor: pointer;
}
</style>
