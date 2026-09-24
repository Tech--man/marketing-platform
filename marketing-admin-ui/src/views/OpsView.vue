<script setup>
import { ref, onMounted } from 'vue'
import { api } from '@/api/client'
import TriState from '@/components/TriState.vue'

/**
 * ④ 的运维大盘（只读面，页面上**没有任何写动作**）。
 *
 * <p>这一页的全部价值在于"它说的和 `GET /api/admin/ops` 逐字一致"，
 * 所以这里刻意不做任何加工：不聚合、不算百分比、不把三种"看不见"合并成一个灰色短横。
 * 后端为此写掉了母版的四条前提（见 ④ spec §1），前端若再归一化一次就全白费了。</p>
 */
const d = ref(null)
const error = ref('')
const busy = ref(true)

async function load() {
  busy.value = true
  error.value = ''
  try {
    d.value = await api.get('/api/admin/ops')
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    busy.value = false
  }
}
onMounted(load)

function livenessKind(v) {
  if (v === null || v === undefined) return '不适用'
  return v ? '在跑' : '没在跑'
}
</script>

<template>
  <main v-if="busy">加载中…</main>
  <main v-else-if="error">读不到大盘：{{ error }} <button @click="load">重试</button></main>
  <main v-else-if="d">
    <header class="bar">
      <h1>运维大盘</h1>
      <!-- mode 与 ownForm 是这张盘的"我在读谁"：没有它们，一组数字无法解释自己 -->
      <p data-testid="mode">
        指标源 <b>{{ d.mode }}</b> · 形态 <b>{{ d.ownForm }}</b> · 采样 {{ d.takenAt }}
        <button data-act="reload" @click="load">重读</button>
      </p>
    </header>

    <ul class="notes">
      <li v-for="(n, i) in d.notes" :key="i">{{ n }}</li>
    </ul>

    <h2>抓取目标</h2>
    <table>
      <thead>
        <tr><th>target</th><th>源</th><th>状态</th><th>样本数</th><th>坏行</th><th>地址</th></tr>
      </thead>
      <tbody>
        <tr v-for="t in d.targets" :key="t.name" :data-target="t.name">
          <td>{{ t.name }}</td>
          <td>{{ t.source }}</td>
          <td :class="{ bad: t.status !== 'OK' }">
            {{ t.status }}<template v-if="t.error">（{{ t.error }}）</template>
          </td>
          <td>{{ t.sampleCount }}</td>
          <td><TriState :value="t.malformedLines" /></td>
          <td class="url">{{ t.url }}</td>
        </tr>
      </tbody>
    </table>

    <h2>进程存活</h2>
    <!-- 三态：true 在跑 / false 没在跑 / null 本形态压根没这个进程（≠ 没在跑） -->
    <p class="procs">
      <span v-for="(v, k) in d.liveness.processes" :key="k" class="proc" :data-proc="k">
        {{ k }} <b :class="v === null ? 'na' : v ? 'ok' : 'bad'">{{ livenessKind(v) }}</b>
      </span>
    </p>
    <table v-if="d.liveness.jobs?.length">
      <thead><tr><th>定时任务</th><th>持锁</th><th>锁剩余(s)</th><th>持有者</th></tr></thead>
      <tbody>
        <tr v-for="j in d.liveness.jobs" :key="j.task">
          <td>{{ j.task }}</td>
          <td>{{ j.held ? '有' : '无人' }}</td>
          <td>{{ j.ttlLeft }}</td>
          <td>{{ j.holder }}</td>
        </tr>
      </tbody>
    </table>

    <h2>异步积压</h2>
    <p>
      未排空合计
      <!-- 任一来源不可见时后端把总数也报 -1：这里必须跟着显示"判定不了"而不是 0 -->
      <TriState :value="d.backlog.totalPendingSent" />
    </p>
    <table>
      <thead><tr><th>库</th><th>未排空</th><th>最早重试</th><th>死信</th><th>说明</th></tr></thead>
      <tbody>
        <tr v-for="s in d.backlog.schemas" :key="s.schema" :data-schema="s.schema">
          <td>{{ s.schema }}</td>
          <td><TriState :value="s.pendingSent" :applicable="!s.error" :note="s.error || ''" /></td>
          <td>{{ s.earliestRetryAt || '—' }}</td>
          <td>{{ (s.deadLetters || []).length }}</td>
          <td>{{ s.error || '' }}</td>
        </tr>
      </tbody>
    </table>
    <table>
      <thead><tr><th>通道</th><th>消费组</th><th>长度</th><th>PEL</th><th>说明</th></tr></thead>
      <tbody>
        <tr v-for="s in d.backlog.streams" :key="s.key" :data-stream="s.key">
          <td>{{ s.key }}</td>
          <td>{{ s.group }}</td>
          <td><TriState :value="s.len" :applicable="s.applicable" :note="s.note || ''" /></td>
          <td><TriState :value="s.pending" :applicable="s.applicable" :note="s.note || ''" /></td>
          <td>{{ s.note || '' }}{{ s.error ? ' / ' + s.error : '' }}</td>
        </tr>
      </tbody>
    </table>

    <h2>缓存与账</h2>
    <table>
      <thead><tr><th>target</th><th>类型</th><th>不符条数</th><th>怎么办</th></tr></thead>
      <tbody>
        <tr v-for="c in d.consistency" :key="c.target + c.type">
          <td>{{ c.target }}</td>
          <td>{{ c.type }}</td>
          <td><TriState :value="c.mismatch" /></td>
          <td>{{ c.note || '' }}</td>
        </tr>
      </tbody>
    </table>
    <p class="hint">不符 ≠ 坏：这一列只是说"缓存与 DB 现在不一致"，修法在「缓存重预热」页。</p>

    <h2>审计量</h2>
    <p v-if="d.audit.error">读不到审计表：{{ d.audit.error }}</p>
    <template v-else>
      <p>共 {{ d.audit.rows }} 行，最早 {{ d.audit.oldestAt }}</p>
      <ul>
        <li v-for="a in d.audit.topActions" :key="a.action">{{ a.action }} · {{ a.n }}</li>
      </ul>
    </template>

    <h2>指标（白名单内）</h2>
    <table>
      <thead><tr><th>target</th><th>指标</th><th>值</th></tr></thead>
      <tbody>
        <tr v-for="(m, i) in d.metrics" :key="i">
          <td>{{ m.target }}</td>
          <td>{{ m.name }}<span v-if="Object.keys(m.tags || {}).length" class="tags">
            <span v-for="(v, k) in m.tags" :key="k">{{ k }}={{ v }}</span></span></td>
          <td>{{ m.value }}</td>
        </tr>
      </tbody>
    </table>
  </main>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: baseline;
  gap: 1rem;
}
.notes {
  color: #606266;
  font-size: 13px;
}
table {
  border-collapse: collapse;
  width: 100%;
  font-size: 13px;
  margin-bottom: 1rem;
}
th,
td {
  border-bottom: 1px solid #ebeef5;
  padding: 0.3rem 0.5rem;
  text-align: left;
  vertical-align: top;
}
.url,
.tags span {
  color: #909399;
  font-size: 12px;
  margin-right: 0.4rem;
}
.bad {
  color: #f56c6c;
}
.ok {
  color: #67c23a;
}
.na {
  color: #909399;
  font-style: italic;
}
.proc {
  margin-right: 0.75rem;
}
.hint {
  color: #909399;
  font-size: 12px;
}
button {
  font: inherit;
}
</style>
