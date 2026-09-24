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
 * 账号页（③ 的 admin 面）。停用会**同时作废该账号的全部会话**，
 * 这句话必须出现在按下去之前——否则被停的人正开着页面，而运营不知道为什么他忽然掉线。
 */
const s = useSession()
const table = ref(null)
const params = ref({ keyword: '' })
const notice = ref('')
const confirmRow = ref(/** @type {any} */ (null))

const columns = [
  { prop: 'id', label: 'ID', width: '4rem', numeric: true },
  { prop: 'username', label: '账号' },
  { prop: 'displayName', label: '显示名' },
  { prop: 'role', label: '角色', slot: 'role' },
  { prop: 'status', label: '状态', slot: 'status' },
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
  <main class="stack">
    <PageHeader title="账号" desc="管理后台登录账号与角色。停用会即时作废该账号的全部在线会话。" />

    <div class="toolbar">
      <Field label="按账号 / 显示名搜索">
        <input v-model="params.keyword" class="input" data-field="keyword" placeholder="例如 viewer" />
      </Field>
      <Button variant="primary" @click="table?.reload()">查询</Button>
    </div>

    <p v-if="notice" :class="notice.includes('被拒') ? 'warn' : 'ok'" data-testid="notice">
      {{ notice }}
    </p>

    <Panel flush>
      <PagedTable ref="table" endpoint="/api/admin/users" :columns="columns" :params="params">
        <template #role="{ value }">
          <span class="pill" :class="value === 'admin' ? 'pill-accent' : ''">{{ value }}</span>
        </template>
        <template #status="{ value }">
          <StatusPill :tone="value === 'ACTIVE' ? 'success' : 'neutral'" :dot="false">{{ value }}</StatusPill>
        </template>
        <template #actions="{ row }">
          <Button
            v-if="s.canWrite && row.status === 'ACTIVE'"
            size="sm"
            variant="danger"
            data-act="disable"
            @click="confirmRow = { id: row.id, username: row.username }"
          >
            停用
          </Button>
          <Button
            v-if="s.canWrite && row.status !== 'ACTIVE'"
            size="sm"
            variant="accent-ghost"
            data-act="enable"
            @click="confirmRow = { id: row.id, username: row.username, enable: true }"
          >
            启用
          </Button>
        </template>
      </PagedTable>
    </Panel>

    <section v-if="confirmRow" class="drawer">
      <p v-if="confirmRow.enable">{{ `启用 ${confirmRow.username}？` }}</p>
      <p v-if="!confirmRow.enable" class="drawer__warn">
        停用 <b>{{ confirmRow.username }}</b> 会把它<b>当前所有在线会话一并作废</b>：
        那个人正在开的页面下一发请求就会收到 40102 并被踢回登录页。
      </p>
      <div class="drawer__foot">
        <Button
          :variant="confirmRow.enable ? 'accent-ghost' : 'danger'"
          data-act="confirm"
          @click="apply(confirmRow.enable ? 'ACTIVE' : 'INACTIVE')"
        >
          确认
        </Button>
        <Button variant="ghost" @click="confirmRow = null">取消</Button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.drawer__warn {
  color: var(--c-text-2);
  font-size: var(--fs-base);
  line-height: 1.6;
}
</style>
