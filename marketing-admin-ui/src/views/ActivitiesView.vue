<script setup>
import { computed, ref } from 'vue'
import { api, E } from '@/api/client'
import { useSession } from '@/stores/session'
import PagedTable from '@/components/PagedTable.vue'

/**
 * 活动管理页（③）。四个动作各有归宿：流转（状态机）、改预算（同事务重预热预扣缓存）、
 * 改灰度（**只写 DB**，由每 5s 回源生效）、建活动（落 DRAFT）。
 */
const s = useSession()
const table = ref(null)
const notice = ref('')
const conflict = ref('')
/** 打开的编辑器：{kind:'budget'|'gray', row:{...}} */
const editor = ref(/** @type {any} */ (null))
const form = ref({})

/**
 * 与后端 `ActivityStateMachine.TRANSITIONS` 同表。**这份副本只用来裁按钮**：
 * 漂了的最坏后果是"少给一个入口"或"点了被 41001 拒"，而后端的 message 会原样显示，
 * 所以不会出现"界面允许、数据坏了"。
 */
const EVENTS = {
  DRAFT: ['SUBMIT'],
  AUDITING: ['APPROVE', 'REJECT'],
  GRAY: ['PROMOTE', 'FINISH'],
  ONLINE: ['OFFLINE', 'FINISH'],
  OFFLINE: ['RE_ONLINE', 'FINISH'],
  FINISHED: [],
}
const LABEL = {
  SUBMIT: '提审',
  APPROVE: '审核通过',
  REJECT: '驳回',
  PROMOTE: '放量上线',
  OFFLINE: '下线',
  RE_ONLINE: '重新上线',
  FINISH: '结束',
}

const columns = computed(() => [
  { prop: 'activityNo', label: '编号' },
  { prop: 'name', label: '名称' },
  { prop: 'status', label: '状态' },
  { prop: 'budgetAmount', label: '预算' },
  { prop: 'usedAmount', label: '已用' },
  { prop: 'grayPercent', label: '灰度%', formatter: (v) => (v === null ? '未配' : v) },
  { prop: 'version', label: '版本' },
  { prop: 'actions', label: '动作', slot: 'actions', width: '15rem' },
])

function eventsOf(row) {
  return EVENTS[row.status] || []
}

function open(row, kind) {
  editor.value = { kind, row }
  form.value =
    kind === 'budget'
      ? { budgetAmount: String(row.budgetAmount ?? ''), remark: '' }
      : { grayPercent: String(row.grayPercent ?? ''), grayWhitelist: row.grayWhitelist ?? '' }
  notice.value = ''
  conflict.value = ''
}

async function submit() {
  const { kind, row } = editor.value
  const body =
    kind === 'budget'
      ? {
          budgetAmount: Number(form.value.budgetAmount),
          version: row.version,
          remark: form.value.remark,
        }
      : {
          // 空串要发 null：灰度"未配"与"配成 0%"是两件事（0% 是全量拒绝，null 才是没参与灰度）
          grayPercent: form.value.grayPercent === '' ? null : Number(form.value.grayPercent),
          grayWhitelist: form.value.grayWhitelist || null,
          version: row.version,
        }
  try {
    const updated = await api.put(`/api/admin/activities/${row.activityNo}/${kind}`, body)
    notice.value =
      kind === 'budget'
        ? `预算已改并按流水对账重预热预扣缓存，C 端余额立刻是新口径`
        : `灰度已落库（DB 是真值），最长 5s 后由 activity 回源生效——刷新页面看不到变化是正常的`
    editor.value = null
    await table.value?.reload()
    void updated
  } catch (e) {
    if (e.code === E.CONFLICT) {
      // 撞号不是"保存失败"：库里的行已经不是页面上这一份了，让人对着旧值再改一遍最坏
      conflict.value = e.message
      return
    }
    notice.value = `写入被拒：${e.message}`
  }
}

async function transition(row, event) {
  notice.value = ''
  conflict.value = ''
  try {
    await api.post(`/api/admin/activities/${row.activityNo}/transition?event=${event}`)
    notice.value = `${row.activityNo} 已执行 ${event}`
    await table.value?.reload()
  } catch (e) {
    // 41001 原文显示：前端这张表只是省事，判定在后端
    notice.value = `流转被拒：${e.message}`
  }
}

async function reloadAfterConflict() {
  editor.value = null
  conflict.value = ''
  await table.value?.reload()
  notice.value = '已重新加载列表，请对照新值再改一次'
}
</script>

<template>
  <main>
    <header>
      <h1>活动</h1>
      <p v-if="!s.canWrite" class="hint">
        当前角色只读：写动作在后端也会被拒（40300），这里就不摆按钮了。
      </p>
    </header>
    <p v-if="notice" data-testid="notice" :class="notice.startsWith('流转被拒') || notice.startsWith('写入被拒') ? 'warn' : 'ok'">
      {{ notice }}
    </p>

    <PagedTable ref="table" endpoint="/api/admin/activities" :columns="columns">
      <template #actions="{ row }">
        <button
          v-for="e in eventsOf(row)"
          :key="e"
          type="button"
          :data-event="e"
          :disabled="!s.canWrite"
          @click="transition(row, e)"
        >
          {{ LABEL[e] || e }}
        </button>
        <button type="button" data-act="edit-budget" :disabled="!s.canWrite" @click="open(row, 'budget')">
          改预算
        </button>
        <button type="button" data-act="edit-gray" :disabled="!s.canWrite" @click="open(row, 'gray')">
          改灰度
        </button>
      </template>
    </PagedTable>

    <section v-if="editor" class="drawer">
      <h2>{{ editor.kind === 'budget' ? '改预算' : '改灰度' }}：{{ editor.row.activityNo }}</h2>

      <p v-if="conflict" data-testid="conflict-bar" class="warn">
        {{ conflict }}
        <button type="button" data-act="reload-conflict" @click="reloadAfterConflict()">
          重新加载并对比
        </button>
      </p>

      <template v-if="editor.kind === 'budget'">
        <label>
          预算金额（元）
          <input v-model="form.budgetAmount" data-field="budgetAmount" />
        </label>
        <label>
          备注
          <input v-model="form.remark" data-field="remark" />
        </label>
        <p class="hint">
          保存后在同一条事务里<b>重预热预扣缓存</b>：预算按<b>流水对账</b>重算（不是按满额回涨），
          所以 C 端的 <code>/budget/remain</code> 立刻反映新口径；已扣掉的额度不会被这次改动退还。
        </p>
      </template>
      <template v-else>
        <label>
          灰度百分比（0-100，留空 = 未配灰度）
          <input v-model="form.grayPercent" data-field="grayPercent" />
        </label>
        <label>
          白名单用户号（逗号分隔）
          <input v-model="form.grayWhitelist" data-field="grayWhitelist" />
        </label>
        <p class="hint">
          灰度<b>只写 DB</b>，不刷任何缓存：真值在 <code>activity.gray_percent</code> 两列，
          activity 每 5s 回源一次。所以 Redis 被清空也不会变成全量放行。
        </p>
      </template>

      <p>
        <button
          v-if="editor.kind === 'budget'"
          type="button"
          data-act="save-budget"
          @click="submit()"
        >
          保存
        </button>
        <button v-else type="button" data-act="save-gray" @click="submit()">保存</button>
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
.hint {
  color: #909399;
  font-size: 12px;
}
.warn {
  color: #f56c6c;
}
.ok {
  color: #67c23a;
}
</style>
