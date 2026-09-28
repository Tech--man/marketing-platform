<script setup>
import { computed } from "vue";
import MPrice from "./MPrice.vue";
import MCountdown from "./MCountdown.vue";
import MStatusPill from "./MStatusPill.vue";
import Icon from "./Icon.vue";
import { toDate, pct } from "@/utils/format";

const props = defineProps({ session: { type: Object, required: true } });

const s = computed(() => props.session);
const remaining = computed(() => Math.max(0, (s.value.totalStock || 0) - (s.value.soldStock || 0)));
const soldPct = computed(() => pct(s.value.soldStock, s.value.totalStock));

const phase = computed(() => {
  const now = Date.now();
  const start = toDate(s.value.startTime)?.getTime() ?? 0;
  const end = toDate(s.value.endTime)?.getTime() ?? 0;
  if (now < start) return "upcoming";
  if (now < end) return "live";
  return "ended";
});

const soldOut = computed(() => remaining.value <= 0);
const low = computed(() => !soldOut.value && soldPct.value >= 80);
</script>

<template>
  <RouterLink :to="`/seckill/${s.activityNo}`" class="sk card card--flush card--pressable">
    <div class="sk__media">
      <Icon name="bolt" :size="40" :stroke-width="1.2" class="sk__glyph" />
      <MStatusPill class="sk__tag" :tone="phase === 'live' ? 'danger' : phase === 'upcoming' ? 'info' : 'neutral'" :pulse="phase === 'live'">
        {{ phase === 'live' ? '抢购中' : phase === 'upcoming' ? '即将开抢' : '已结束' }}
      </MStatusPill>
    </div>
    <div class="sk__body">
      <div class="sk__name clamp-2">{{ s.itemName }}</div>
      <div class="sk__price">
        <MPrice :value="s.seckillPrice" />
        <span class="sk__label">限量 {{ s.totalStock }} 件</span>
      </div>
      <div class="sk__progress">
        <div class="bar"><div class="bar__fill" :class="{ 'bar__fill--danger': low }" :style="{ width: soldPct + '%' }" /></div>
        <span class="sk__remain" :class="{ 'is-low': low || soldOut }">
          {{ soldOut ? '已抢光' : `剩 ${remaining} 件` }}
        </span>
      </div>
      <div class="sk__foot">
        <span class="sk__count">
          <Icon name="clock" :size="14" />
          <MCountdown v-if="phase === 'upcoming'" :target="s.startTime" to-start>
            <template #ended>开抢</template>
          </MCountdown>
          <MCountdown v-else-if="phase === 'live'" :target="s.endTime" />
          <span v-else class="muted">已结束</span>
        </span>
        <span class="sk__cta">{{ phase === 'live' ? (soldOut ? '看看别的' : '去抢购') : phase === 'upcoming' ? '提前蹲守' : '看回放' }}<Icon name="chevron" :size="14" /></span>
      </div>
    </div>
  </RouterLink>
</template>

<style scoped>
/* 整张卡是个 RouterLink，而全局 a{color:brand}——不在这儿收回来的话，
   商品名会跟着变成链接蓝。链接色只留给底部的 CTA。 */
.sk { display: flex; flex-direction: column; color: var(--c-text); }
.sk__media {
  height: 132px;
  display: grid;
  place-items: center;
  background: var(--c-surface-2);
  border-bottom: 1px solid var(--hairline);
  position: relative;
}
.sk__glyph { color: var(--c-text-faint); }
.sk__tag { position: absolute; top: var(--space-3); left: var(--space-3); }
.sk__body { padding: var(--space-4); display: flex; flex-direction: column; gap: var(--space-3); }
.sk__name { font-weight: 600; font-size: var(--fs-base); line-height: 1.35; min-height: 2.7em; letter-spacing: var(--tracking-body); }
.sk__price { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-2); }
.sk__label { font-size: var(--fs-xs); color: var(--c-text-muted); }
.sk__progress { display: flex; align-items: center; gap: var(--space-3); }
.sk__progress .bar { flex: 1; }
.sk__remain { font-size: var(--fs-xs); color: var(--c-text-muted); flex: none; font-weight: 500; }
.sk__remain.is-low { color: var(--c-danger); }
.sk__foot { display: flex; align-items: center; justify-content: space-between; }
.sk__count { display: inline-flex; align-items: center; gap: 6px; color: var(--c-text-2); font-size: var(--fs-sm); }
.sk__cta { display: inline-flex; align-items: center; gap: 1px; font-size: var(--fs-sm); font-weight: 500; color: var(--c-brand); }
</style>
