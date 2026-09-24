<script setup>
import { computed, onUnmounted, ref } from 'vue'
import { api, E } from '@/api/client'
import { useSession } from '@/stores/session'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import Field from '@/components/Field.vue'
import Button from '@/components/Button.vue'

/**
 * 重预热页（③，admin + operator 都能用）。
 *
 * <p>回执三态里 <code>DISPATCHED</code> 是最容易被界面写错的一种：它既不是成功也不是失败，
 * 而是"已经交给 owning 服务、回执还没回来"。这里给它一个独立的、明确的状态，
 * 并按 id 轮询 ack 直到 DONE / FAILED；轮询超时也**不静默**——留一个"再查一次"，
 * 因为回执键有 10 分钟 TTL，静默放弃等于让人以为消息丢了。</p>
 */
const s = useSession()
const localTypes = ref([])
/**
 * 可选类型 = 本进程注册的 ∪ ④ 面板 consistency 行报上来的。
 *
 * <p>只用 `/cache/types` 会在 FULL 档把这一页废掉：那个端点回答的是"这个 JVM 里注册了什么"，
 * 而分进程的 admin 里一个 reheater 都没有（实测返回 `[]`）——偏偏跨进程重预热才是这一页最需要
 * 用得上的场合。consistency 行由各 owning 进程自己上报，类型名与 owning target 都是真值，
 * 所以拿它当第二个来源，而不是在前端写死一份类型清单（那会变成第三份真相）。</p>
 */
const panelTypes = ref([])
const typeOptions = computed(() => {
  const map = new Map()
  for (const t of localTypes.value) map.set(t, '')
  for (const p of panelTypes.value) if (!map.has(p.type)) map.set(p.type, p.target)
  return [...map.entries()].map(([type, target]) => ({ type, target }))
})
const selected = ref('')
const key = ref('')
const force = ref(true)
const busy = ref(false)
const state = ref('')
const receipt = ref(/** @type {any} */ (null))
const error = ref('')
let timer = null

async function loadTypes() {
  localTypes.value = (await api.get('/api/admin/cache/types')) || []
  if (typeOptions.value.length) pickType()
  // 面板读不到不影响本页：只是少了跨进程那半份类型清单
  try {
    const ops = await api.get('/api/admin/ops')
    panelTypes.value = (ops?.consistency || []).map((c) => ({ type: c.type, target: c.target }))
    if (!typeOptions.value.length) {
      error.value = '本进程与面板都没报上任何重预热类型'
    }
    pickType()
  } catch {
    /* 面板不可读就只用本地清单 */
  }
}
loadTypes()

function pickType() {
  if (!selected.value && typeOptions.value.length) selected.value = typeOptions.value[0].type
}

function stop() {
  clearInterval(timer)
  timer = null
}
onUnmounted(stop)

async function run() {
  stop()
  error.value = ''
  receipt.value = null
  const type = selected.value
  if (!type) {
    error.value = '没有可选的重预热类型（本进程与 ④ 面板都没报上来）'
    return
  }
  busy.value = true
  try {
    const r = await api.post(
      `/api/admin/cache/reheat?type=${encodeURIComponent(type)}&key=${encodeURIComponent(key.value)}&force=${force.value}`,
    )
    await settle(type, r)
  } catch (e) {
    if (e.code === E.NOT_APPLICABLE) {
      error.value = `没人认领：${e.message}`
    } else {
      error.value = e.message
    }
  } finally {
    busy.value = false
  }
}

/** DONE 收尾；DISPATCHED 起轮询；FAILED 显示 owning 服务写回的错因 */
async function settle(type, r) {
  receipt.value = r
  if (r.status === 'DONE') {
    state.value = `已完成：${r.type} ${r.key}  ${r.before} → ${r.after}`
    return
  }
  if (r.status === 'FAILED') {
    state.value = `执行失败：${r.error || r.note}`
    return
  }
  state.value = '已投递给 owning 服务，等回执…'
  let tries = 0
  await new Promise((resolve) => {
    timer = setInterval(async () => {
      tries += 1
      try {
        const ack = await api.get(
          `/api/admin/cache/reheat/ack?type=${encodeURIComponent(type)}&id=${encodeURIComponent(r.id)}`,
        )
        receipt.value = ack
        if (ack.status === 'DONE') {
          state.value = `已完成：${ack.type} ${ack.key}  ${ack.before} → ${ack.after}`
          stop()
          resolve()
          return
        }
        if (ack.status === 'FAILED') {
          state.value = `执行失败：${ack.error || ack.note}`
          stop()
          resolve()
          return
        }
      } catch (e) {
        state.value = `查询回执失败：${e.message}`
      }
      if (tries >= 15) {
        state.value = `仍待回执（已投递 id=${r.id}，回执键 10 分钟内有效）`
        stop()
        resolve()
      }
    }, 2000)
  })
}

/** 超时之后再手动查一次（复用同一段 settle 逻辑，避免第二套状态机） */
async function pollAgain() {
  const r = receipt.value
  if (!r?.id) return
  await settle(r.type, r)
}
</script>

<template>
  <main class="stack">
    <PageHeader title="缓存重预热" desc="预扣缓存与 DB 对不上时（大盘的“缓存与账”那一列非 0），修的地方在这里。operator 也可以用这一页——它是运维动作，不是配置。" />

    <Panel>
      <form class="form-grid" @submit.prevent="run">
        <Field label="类型">
          <select v-model="selected" class="select" data-field="type">
            <option v-for="o in typeOptions" :key="o.type" :value="o.type">
              {{ o.type }}{{ o.target ? `（由 ${o.target} 上报）` : '（本进程）' }}
            </option>
          </select>
        </Field>
        <Field label="key（活动号 / 模板号 / 秒杀活动号）">
          <input v-model="key" class="input" data-field="key" placeholder="例如 ACT2026001" />
        </Field>
        <div class="cache-submit">
          <label class="check">
            <input v-model="force" type="checkbox" data-field="force" />
            <span>force（值本来就对时也强制重写一遍）</span>
          </label>
          <Button
            variant="primary"
            data-act="reheat"
            :disabled="busy || !typeOptions.length"
            @click="run"
          >
            {{ busy ? '执行中…' : '重预热' }}
          </Button>
        </div>
      </form>
      <p v-if="!typeOptions.length" class="hint">
        一个类型都没有：本进程没注册 reheater，④ 面板也没报上 consistency 类型。
      </p>
    </Panel>

    <p v-if="error" class="warn" data-testid="error">{{ error }}</p>
    <p v-if="state" class="ok" data-testid="state">{{ state }}</p>
    <p v-if="receipt" class="receipt">
      <span class="badge" :class="receipt.status === 'FAILED' ? 'is-down' : 'is-up'">{{ receipt.status }}</span>
      type={{ receipt.type }} · key={{ receipt.key }}
      <template v-if="receipt.id"> · id={{ receipt.id }}</template>
      <template v-if="receipt.status !== 'DISPATCHED'">
        · before={{ receipt.before }} → after={{ receipt.after }}
      </template>
      <small v-if="receipt.note"> · {{ receipt.note }}</small>
    </p>
    <p v-if="state.startsWith('仍待回执')">
      <Button size="sm" variant="accent-ghost" data-act="poll-again" @click="pollAgain()">
        再查一次回执
      </Button>
    </p>
  </main>
</template>

<style scoped>
.cache-submit {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}
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
.receipt {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  background: var(--c-surface-2);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  padding: var(--space-2) var(--space-3);
  font-size: var(--fs-sm);
  color: var(--c-text-2);
}
.receipt .badge {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 var(--space-2);
  border-radius: var(--radius-pill);
  font-size: var(--fs-xs);
  font-weight: 600;
  font-family: var(--font-mono);
}
.receipt .badge.is-up {
  color: var(--c-success);
  background: var(--c-success-soft);
}
.receipt .badge.is-down {
  color: var(--c-danger);
  background: var(--c-danger-soft);
}
</style>

