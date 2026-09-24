<script setup>
import { ref, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { api } from '@/api/client'
import TriState from '@/components/TriState.vue'
import Panel from '@/components/Panel.vue'
import Button from '@/components/Button.vue'

/**
 * ④ 的运维大盘（只读面，页面上**没有任何写动作**）。
 *
 * <p>这一页的全部价值在于"它说的和 `GET /api/admin/ops` 逐字一致"，
 * 所以这里刻意不做任何加工：不聚合、不算百分比、不把三种"看不见"合并成一个灰色短横。
 * 后端为此写掉了母版的四条前提（见 ④ spec §1），前端若再归一化一次就全白费了。
 * 本次改版只动外观（面板分区、状态色、图例），读数与语义一律保持原样。</p>
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
function livenessCls(v) {
  if (v === null || v === undefined) return 'na'
  return v ? 'up' : 'down'
}
</script>

<template>
  <main v-if="busy" class="loading">加载中…</main>
  <main v-else-if="error" class="state-err">
    <p>读不到大盘：{{ error }}</p>
    <Button size="sm" @click="load">重试</Button>
  </main>
  <main v-else-if="d" class="stack">
    <div class="ops-head">
      <div>
        <h1 class="ops-head__title">运维大盘</h1>
        <p class="ops-head__meta" data-testid="mode">
          指标源 <b>{{ d.mode }}</b> · 形态 <b>{{ d.ownForm }}</b> · 采样 {{ d.takenAt }}
        </p>
      </div>
      <Button size="sm" data-act="reload" @click="load">
        <el-icon class="ico"><Refresh /></el-icon> 重读
      </Button>
    </div>

    <ul v-if="d.notes?.length" class="notes">
      <li v-for="(n, i) in d.notes" :key="i">{{ n }}</li>
    </ul>

    <Panel title="抓取目标" flush>
      <div class="data-wrap">
        <table class="data">
          <thead>
            <tr><th>target</th><th>源</th><th>状态</th><th class="num">样本数</th><th class="num">坏行</th><th>地址</th></tr>
          </thead>
          <tbody>
            <tr v-for="t in d.targets" :key="t.name" :data-target="t.name">
              <td>{{ t.name }}</td>
              <td>{{ t.source }}</td>
              <td>
                <span :class="['badge', t.status !== 'OK' ? 'is-down' : 'is-up']">
                  {{ t.status }}<template v-if="t.error">（{{ t.error }}）</template>
                </span>
              </td>
              <td class="num">{{ t.sampleCount }}</td>
              <td class="num"><TriState :value="t.malformedLines" /></td>
              <td class="url">{{ t.url }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </Panel>

    <Panel title="进程存活" desc="三态：true 在跑 / false 没在跑 / null 本形态压根没这个进程（≠ 没在跑）">
      <div class="legend">
        <span class="proc" v-for="(v, k) in d.liveness.processes" :key="k" :data-proc="k">
          {{ k }}
          <b :class="['badge', 'badge-' + livenessCls(v)]">{{ livenessKind(v) }}</b>
        </span>
      </div>
      <div v-if="d.liveness.jobs?.length" class="data-wrap" style="margin-top: var(--space-4)">
        <table class="data">
          <thead><tr><th>定时任务</th><th>持锁</th><th class="num">锁剩余(s)</th><th>持有者</th></tr></thead>
          <tbody>
            <tr v-for="j in d.liveness.jobs" :key="j.task">
              <td>{{ j.task }}</td>
              <td>{{ j.held ? '有' : '无人' }}</td>
              <td class="num">{{ j.ttlLeft }}</td>
              <td>{{ j.holder }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </Panel>

    <Panel title="异步积压">
      <p class="sum">
        未排空合计
        <!-- 任一来源不可见时后端把总数也报 -1：这里必须跟着显示"判定不了"而不是 0 -->
        <TriState :value="d.backlog.totalPendingSent" />
      </p>
      <div class="data-wrap">
        <table class="data">
          <thead><tr><th>库</th><th class="num">未排空</th><th>最早重试</th><th class="num">死信</th><th>说明</th></tr></thead>
          <tbody>
            <tr v-for="s in d.backlog.schemas" :key="s.schema" :data-schema="s.schema">
              <td>{{ s.schema }}</td>
              <td class="num"><TriState :value="s.pendingSent" :applicable="!s.error" :note="s.error || ''" /></td>
              <td>{{ s.earliestRetryAt || '—' }}</td>
              <td class="num">{{ (s.deadLetters || []).length }}</td>
              <td class="muted">{{ s.error || '' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="data-wrap" style="margin-top: var(--space-4)">
        <table class="data">
          <thead><tr><th>通道</th><th>消费组</th><th class="num">长度</th><th class="num">PEL</th><th>说明</th></tr></thead>
          <tbody>
            <tr v-for="s in d.backlog.streams" :key="s.key" :data-stream="s.key">
              <td>{{ s.key }}</td>
              <td>{{ s.group }}</td>
              <td class="num"><TriState :value="s.len" :applicable="s.applicable" :note="s.note || ''" /></td>
              <td class="num"><TriState :value="s.pending" :applicable="s.applicable" :note="s.note || ''" /></td>
              <td class="muted">{{ s.note || '' }}{{ s.error ? ' / ' + s.error : '' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </Panel>

    <Panel title="缓存与账" flush>
      <div class="data-wrap">
        <table class="data">
          <thead><tr><th>target</th><th>类型</th><th class="num">不符条数</th><th>怎么办</th></tr></thead>
          <tbody>
            <tr v-for="c in d.consistency" :key="c.target + c.type">
              <td>{{ c.target }}</td>
              <td>{{ c.type }}</td>
              <td class="num"><TriState :value="c.mismatch" /></td>
              <td class="muted">{{ c.note || '' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="hint" style="padding: var(--space-3) var(--space-4)">不符 ≠ 坏：这一列只是说"缓存与 DB 现在不一致"，修法在「缓存重预热」页。</p>
    </Panel>

    <Panel title="审计量">
      <p v-if="d.audit.error">读不到审计表：{{ d.audit.error }}</p>
      <template v-else>
        <p>共 {{ d.audit.rows }} 行，最早 {{ d.audit.oldestAt }}</p>
        <ul class="notes">
          <li v-for="a in d.audit.topActions" :key="a.action">{{ a.action }} · {{ a.n }}</li>
        </ul>
      </template>
    </Panel>

    <Panel title="指标（白名单内）" flush>
      <div class="data-wrap">
        <table class="data">
          <thead><tr><th>target</th><th>指标</th><th class="num">值</th></tr></thead>
          <tbody>
            <tr v-for="(m, i) in d.metrics" :key="i">
              <td>{{ m.target }}</td>
              <td>{{ m.name }}<span v-if="Object.keys(m.tags || {}).length" class="tags">
                <span v-for="(v, k) in m.tags" :key="k">{{ k }}={{ v }}</span></span></td>
              <td class="num">{{ m.value }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </Panel>
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
.ops-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}
.ops-head__title {
  font-size: var(--fs-xl);
}
.ops-head__meta {
  font-size: var(--fs-sm);
  color: var(--c-text-muted);
  margin-top: var(--space-1);
}
.ops-head__meta b {
  color: var(--c-text-2);
  font-weight: 600;
}
.notes {
  margin: 0;
  padding-left: var(--space-5);
  color: var(--c-text-muted);
  font-size: var(--fs-sm);
}
.notes li {
  margin: 2px 0;
}
.sum {
  font-size: var(--fs-sm);
  color: var(--c-text-2);
  margin-bottom: var(--space-3);
}
.legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
}
.proc {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--fs-sm);
  color: var(--c-text-2);
}
.badge {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 var(--space-2);
  border-radius: var(--radius-pill);
  font-size: var(--fs-xs);
  font-weight: 600;
}
.badge.is-up,
.badge-up {
  color: var(--c-success);
  background: var(--c-success-soft);
}
.badge.is-down,
.badge-down {
  color: var(--c-danger);
  background: var(--c-danger-soft);
}
.badge-na {
  color: var(--c-text-muted);
  background: var(--c-surface-3);
  font-style: italic;
}
.url {
  color: var(--c-text-muted);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  word-break: break-all;
}
.tags {
  display: inline-flex;
  gap: var(--space-1);
  margin-left: var(--space-2);
  flex-wrap: wrap;
}
.tags span {
  color: var(--c-text-muted);
  font-size: var(--fs-xs);
  background: var(--c-surface-2);
  border-radius: var(--radius-sm);
  padding: 0 6px;
}
</style>
