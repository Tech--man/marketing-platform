<script setup>
import { computed, onMounted, ref } from 'vue'
import { api, E } from '@/api/client'
import { useSession } from '@/stores/session'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import Field from '@/components/Field.vue'
import Button from '@/components/Button.vue'

/**
 * ⑤ 的在线配置页（读任意后台角色，写仅 admin）。
 *
 * <p>形状与 `ConfigOverviewView` 逐字对齐：`entries` 是**按 key 一条**，
 * 形态维度在每条的 `rows[{form,value,version,updatedBy,remark}]` 里。
 * 没被覆盖过的键也照样列出来——否则"改了没生效"与"这个键压根没覆盖过"分不开。</p>
 */
const s = useSession()
const d = ref(null)
const error = ref('')
const busy = ref(true)
const notice = ref('')
// 需要显式给形：ref(null) 会被推成 Ref<null>，之后赋对象就编译不过
const editing = ref(/** @type {any} */ (null))
const confirmDel = ref(/** @type {any} */ (null))
const notBroadcast = ref(false)

/**
 * 与后端 `ConfigKeys.FORMS` 同一套。第二份真相的风险在这里是可接受的：
 * 拼错的 form 会被写侧 40000 拒掉并把允许集合写在 message 里（不是静默写进没人读的档），
 * 而 `ownForm` 再动态补进下拉，将来加第五档至少本档可选。
 */
const FORMS = ['GLOBAL', 'LITE', 'FULL', 'DEV']
const formOptions = computed(() => {
  const own = d.value?.ownForm
  return own && !FORMS.includes(own) ? [...FORMS, own] : FORMS
})

const SOURCE_LABEL = { FORM: '本档覆盖', GLOBAL: '全局覆盖', DEFAULT: '出厂值' }

async function load() {
  busy.value = true
  error.value = ''
  try {
    d.value = await api.get('/api/admin/config')
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    busy.value = false
  }
}
onMounted(load)

function startEdit(entry) {
  const own = d.value?.ownForm
  // 默认落在"本档"上是有意的：改错档是这一页最贵的错，预填全局等于把坑摆在路上
  editing.value = {
    key: entry.key,
    form: own && FORMS.includes(own) ? own : 'GLOBAL',
    value: entry.effectiveValue ?? entry.defaultValue ?? '',
    min: entry.min,
    max: entry.max,
    remark: '',
    error: '',
  }
  notice.value = ''
  notBroadcast.value = false
}

async function save() {
  editing.value.error = ''
  try {
    await api.put('/api/admin/config', {
      cfgKey: editing.value.key,
      form: editing.value.form,
      value: editing.value.value,
      remark: editing.value.remark,
    })
    notice.value = `已写入并广播：${editing.value.key} @ ${editing.value.form}`
    editing.value = null
    await load()
  } catch (e) {
    if (e.code === E.NOT_BROADCAST) {
      // 半状态：库里有、快照没发。当成成功就等于把 41009 这个码从世界上抹掉
      notBroadcast.value = true
      editing.value.error = e.message
      return
    }
    // 其余错误（40000 越界/未声明、40300 越权）原样显示，**不清空表单**：
    // 允许区间就写在 message 里，清一次就要人凭记忆重填
    editing.value.error = e.message || String(e)
  }
}

async function rebroadcast() {
  try {
    await api.post('/api/admin/config/rebroadcast')
    notBroadcast.value = false
    // 值早就写进库了，编辑器留着只会让人再保存一次（同一句话再写一遍是安全的，
    // 但"半状态已消解"之后还摆着一个填了一半的表单，是在邀请人重复操作）
    editing.value = null
    notice.value = '已按 DB 现状重发快照，各进程下一个轮询周期内生效'
    await load()
  } catch (e) {
    notice.value = `重广播仍然失败：${e.message}`
  }
}

async function doDelete() {
  try {
    await api.del(
      `/api/admin/config?cfgKey=${encodeURIComponent(confirmDel.value.key)}&form=${encodeURIComponent(confirmDel.value.form)}`,
    )
    notice.value = `已删掉 ${confirmDel.value.key} @ ${confirmDel.value.form} 的覆盖行`
    confirmDel.value = null
    await load()
  } catch (e) {
    notice.value = `删除失败：${e.message}`
  }
}

function rowsOf(entry) {
  return entry.rows || []
}

/** 删行先落在这个确认面板上：删的是"覆盖"，效果是"回出厂"，这句话必须在点下去之前就看到 */
function askDelete(entry) {
  confirmDel.value = { key: entry.key, form: rowsOf(entry)[0].form }
  notice.value = ''
}
</script>

<template>
  <main v-if="busy" class="loading">加载中…</main>
  <main v-else-if="error" class="state-err">
    <p>读不到配置：{{ error }}</p>
    <Button size="sm" @click="load">重试</Button>
  </main>
  <main v-else-if="d" class="stack">
    <PageHeader title="在线配置" desc="读任意后台角色，写仅 admin。没被覆盖过的键也照样列出——否则“改了没生效”与“这个键压根没覆盖过”分不开。">
      <template #actions>
        <Button size="sm" data-act="reload" @click="load">重读</Button>
      </template>
    </PageHeader>

    <p class="meta" data-testid="own-form">
      本进程形态 <b>{{ d.ownForm }}</b> · 生效快照版本
      <b>{{ d.appliedVersion }}</b>
      <span v-if="d.appliedVersion === 0" class="muted">（没人写过覆盖，全部跑出厂值）</span>
    </p>

    <p v-if="d.degradedKeys?.length" class="warn">
      本进程忽略了这些键（未声明或越界）：{{ d.degradedKeys.join('、') }}
    </p>
    <p v-if="d.unreportedServices?.length" class="warn">
      这些进程没上报自述（它们的键此刻不可见）：{{ d.unreportedServices.join('、') }}
    </p>
    <p v-if="d.orphans?.length" class="warn">
      幽灵行（写了但没人声明）：
      <span v-for="o in d.orphans" :key="o.form + o.cfgKey">[{{ o.form }}] {{ o.cfgKey }}={{ o.value }} by {{ o.updatedBy }}</span>
    </p>

    <p v-if="notBroadcast" class="warn" data-testid="not-broadcast">
      配置已落库但未广播——现在库里是新值、各进程还在跑旧值。
      <Button size="sm" variant="danger" data-act="rebroadcast" @click="rebroadcast">重新广播</Button>
    </p>
    <p v-else-if="notice" class="notice">{{ notice }}</p>

    <Panel flush>
      <div class="data-wrap">
        <table class="data">
          <thead>
            <tr>
              <th>键</th>
              <th>声明方</th>
              <th>生效值</th>
              <th>来源</th>
              <th>出厂值</th>
              <th>允许区间</th>
              <th>各形态覆盖行</th>
              <th>动作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in d.entries" :key="e.key" :data-key="e.key">
              <td>
                {{ e.key }}
                <small>{{ e.description }}</small>
              </td>
              <td>{{ e.service }}</td>
              <td><b>{{ e.effectiveValue }}</b></td>
              <td :data-src="e.source">
                <span class="pill" :class="e.source === 'DEFAULT' ? '' : 'pill-accent'">{{ SOURCE_LABEL[e.source] || e.source }}</span>
              </td>
              <td class="muted">{{ e.defaultValue }}</td>
              <td class="muted num">[{{ e.min }}, {{ e.max }}]</td>
              <td>
                <span v-for="r in rowsOf(e)" :key="r.form" class="row-chip">
                  <span class="chip">{{ r.form }}={{ r.value }}</span
                  ><small class="muted"> v{{ r.version }} by {{ r.updatedBy }}</small>
                </span>
                <span v-if="!rowsOf(e).length" class="muted">无</span>
              </td>
              <td class="actions">
                <Button v-if="s.canWrite" size="sm" data-act="edit" @click="startEdit(e)">改值</Button>
                <Button
                  v-else
                  size="sm"
                  data-act="edit"
                  disabled
                  title="只有 admin 角色能改阈值（operator 在网关可写运维，但改不动配置）"
                >
                  改值
                </Button>
                <Button
                  v-if="rowsOf(e).length && s.canWrite"
                  size="sm"
                  variant="accent-ghost"
                  data-act="delete"
                  @click="askDelete(e)"
                >
                  删行
                </Button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </Panel>

    <section v-if="editing" class="drawer" data-testid="editor">
      <div class="drawer__head"><h2 class="drawer__title">写覆盖：{{ editing.key }}</h2></div>
      <div class="form-grid">
        <Field label="形态">
          <select v-model="editing.form" class="select" data-field="form">
            <option v-for="f in formOptions" :key="f" :value="f">{{ f }}</option>
          </select>
        </Field>
        <Field label="值"><input v-model="editing.value" class="input" data-field="value" /></Field>
        <Field label="备注"><input v-model="editing.remark" class="input" data-field="remark" /></Field>
      </div>
      <p class="hint">
        越界与未声明的键会在写侧就被拒；窗宽这类“不该在线改”的东西根本不在这张表里。
      </p>
      <p v-if="editing.error" class="warn" data-testid="editor-error">{{ editing.error }}</p>
      <div class="drawer__foot">
        <Button variant="primary" data-act="save" @click="save">保存</Button>
        <Button variant="ghost" @click="editing = null">取消</Button>
      </div>
    </section>

    <section v-if="confirmDel" class="drawer">
      <div class="drawer__head"><h2 class="drawer__title">删掉这一行？</h2></div>
      <p class="drawer__warn">
        删除 <b>{{ confirmDel.key }}</b> 在 <b>{{ confirmDel.form }}</b> 档的覆盖行 =
        <b>恢复出厂</b>：这一档退回本进程 yml 的出厂值，不是删成 0，也不会去改别的档。
      </p>
      <div class="drawer__foot">
        <Button variant="danger" data-act="confirm-delete" @click="doDelete">确认删除</Button>
        <Button variant="ghost" @click="confirmDel = null">取消</Button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.loading {
  color: var(--c-text-muted);
}
.state-err {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-4);
  background: var(--c-danger-soft);
  color: var(--c-danger);
  border-radius: var(--radius-md);
}
.meta {
  font-size: var(--fs-sm);
  color: var(--c-text-muted);
}
.meta b {
  color: var(--c-text-2);
  font-weight: 600;
}
.row-chip {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin: 2px var(--space-2) 2px 0;
}
.chip {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  background: var(--c-surface-2);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-sm);
  padding: 1px 6px;
}
.actions {
  white-space: nowrap;
}
.actions > * + * {
  margin-left: var(--space-1);
}
.drawer__warn {
  color: var(--c-text-2);
  line-height: 1.6;
}
</style>
