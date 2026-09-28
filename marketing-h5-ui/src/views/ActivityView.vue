<script setup>
import { ref, computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import MButton from "@/components/MButton.vue";
import MPrice from "@/components/MPrice.vue";
import MStatusPill from "@/components/MStatusPill.vue";
import MState from "@/components/MState.vue";
import Icon from "@/components/Icon.vue";
import { activityApi } from "@/api";
import { ApiError, messageFor, needsLogin, noteThrottle } from "@/api/client";
import { cents, yuan, pct, toDate, formatDateTime, uuid } from "@/utils/format";
import { useSession } from "@/stores/session";
import { useToast } from "@/stores/toast";
import { toLogin } from "@/utils/auth";

const route = useRoute();
const router = useRouter();
const session = useSession();
const toast = useToast();
const NO = route.params.no;

const loading = ref(true);
const error = ref("");
const act = ref(null);
const canJoin = ref(false);
const gray = ref(false);
const remain = ref(null);
const reserving = ref(false);

const STATUS = {
  DRAFT: ["草稿", "neutral"], SUBMITTED: ["待审", "info"], APPROVED: ["已过审", "info"],
  ONLINE: ["进行中", "success"], FINISHED: ["已结束", "neutral"], OFFLINE: ["已下线", "warning"],
};

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [a, j, g, r] = await Promise.all([
      activityApi.detail(NO),
      activityApi.participatable(NO).catch(() => false),
      // 灰度是按账号判的，游客没有账号——跳过，不是去撞 40100
      session.isLoggedIn ? activityApi.grayHit(NO).catch(() => false) : Promise.resolve(false),
      activityApi.budgetRemain(NO).catch(() => null),
    ]);
    act.value = a;
    canJoin.value = !!j;
    gray.value = !!g;
    remain.value = r;
  } catch (e) {
    if (e instanceof ApiError) noteThrottle(e);
    error.value = e instanceof ApiError ? messageFor(e) : "加载失败";
  } finally {
    loading.value = false;
  }
}
onMounted(load);

const usedPct = computed(() =>
  act.value ? pct(Number(act.value.usedAmount) || 0, Number(act.value.budgetAmount) || 0) : 0
);
const remainYuan = computed(() => (remain.value == null ? null : cents(remain.value)));

async function reserve() {
  if (!act.value || reserving.value) return;
  if (!session.isLoggedIn) {
    toast.info("预留补贴要落到账号上，先登录");
    await router.push(toLogin(route.fullPath));
    return;
  }
  reserving.value = true;
  try {
    const r = await activityApi.deduct(NO, { amountCents: 100, bizKey: "reserve-" + uuid() });
    toast.success(r === "REPLAYED" ? "该笔已计入（幂等重放）" : "已为你预留 ¥1.00 补贴");
    remain.value = await activityApi.budgetRemain(NO).catch(() => remain.value);
  } catch (e) {
    if (e instanceof ApiError) noteThrottle(e);
    if (e instanceof ApiError && needsLogin(e)) {
      toast.info("登录态已失效，重新登录后再试");
      await router.push(toLogin(route.fullPath));
    } else {
      toast.error(e instanceof ApiError ? messageFor(e) : "预留失败");
    }
  } finally {
    reserving.value = false;
  }
}
</script>

<template>
  <div>
    <MState v-if="loading" variant="loading" title="加载中…" />
    <MState v-else-if="error" variant="error" title="加载失败" :hint="error">
      <button class="btn btn--ghost btn--sm" @click="load">重试</button>
    </MState>

    <div v-else-if="act" class="av split">
      <div class="av__left">
        <div class="av__eyebrow">
          <MStatusPill :tone="STATUS[act.status]?.[1] || 'neutral'" :pulse="act.status === 'ONLINE'">
            {{ STATUS[act.status]?.[0] || act.status }}
          </MStatusPill>
          <span class="num tiny muted">{{ act.activityNo }}</span>
        </div>
        <h1 class="av__title">{{ act.name }}</h1>
        <p v-if="act.remark" class="muted av__remark">{{ act.remark }}</p>

        <dl class="av__facts">
          <div class="fact">
            <dt><Icon name="shield" :size="15" /> 参与资格</dt>
            <dd><MStatusPill :tone="canJoin ? 'success' : 'neutral'">{{ canJoin ? '当前可参加' : '暂不可参加' }}</MStatusPill></dd>
          </div>
          <div class="fact">
            <dt><Icon name="sparkles" :size="15" /> 灰度命中</dt>
            <dd><MStatusPill :tone="gray ? 'info' : 'neutral'">{{ gray ? '已命中 · 抢先体验' : '未命中' }}</MStatusPill></dd>
          </div>
          <div class="fact">
            <dt><Icon name="clock" :size="15" /> 活动时间</dt>
            <dd class="small">{{ formatDateTime(act.startTime) }} — {{ formatDateTime(act.endTime) }}</dd>
          </div>
          <div class="fact">
            <dt><Icon name="tag" :size="15" /> 灰度比例</dt>
            <dd class="small num">{{ act.grayPercent ?? 0 }}%</dd>
          </div>
        </dl>

        <MButton size="lg" @click="router.push('/cart')">去购物车用优惠</MButton>
      </div>

      <aside class="split__aside">
        <section class="card card--pad">
          <div class="row row--between av__budgetHead">
            <h2 class="strong">活动补贴</h2>
            <span class="small muted">{{ usedPct }}% 已发放</span>
          </div>
          <div class="bar bar--lg"><div class="bar__fill" :style="{ width: usedPct + '%' }" /></div>

          <div class="av__remain">
            <div class="muted small">剩余补贴</div>
            <MPrice v-if="remainYuan != null" :value="remainYuan" size="lg" />
            <div v-else class="muted small">读数暂不可用</div>
          </div>

          <div class="kv"><span class="muted small">总额</span><span class="num small">{{ yuan(act.budgetAmount) }}</span></div>
          <div class="kv"><span class="muted small">已用</span><span class="num small">{{ yuan(act.usedAmount) }}</span></div>

          <MButton variant="soft" block style="margin-top: var(--space-5)" :loading="reserving" :disabled="!canJoin" @click="reserve">
            {{ canJoin ? '预留 ¥1.00 补贴' : '当前不可参加' }}
          </MButton>
          <p class="tiny muted av__hint">同一 bizKey 重复提交会被判为重放，不会二次扣减。</p>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.av__left { min-width: 0; }
.av__eyebrow { display: flex; align-items: center; gap: var(--space-3); margin-bottom: var(--space-4); }
.av__title { font-size: var(--fs-2xl); line-height: 1.12; }
.av__remark { margin-top: var(--space-3); font-size: var(--fs-base); }
.av__facts {
  display: grid; gap: var(--space-4);
  margin: var(--space-6) 0;
  padding: var(--space-5) 0;
  border-top: 1px solid var(--hairline);
  border-bottom: 1px solid var(--hairline);
}
.fact dt { display: flex; align-items: center; gap: 6px; font-size: var(--fs-xs); color: var(--c-text-muted); margin-bottom: 6px; }
.fact dd { margin: 0; }
.bar--lg { height: 6px; }
.av__budgetHead { margin-bottom: var(--space-3); }
.av__budgetHead h2 { font-size: var(--fs-md); }
.av__remain { margin: var(--space-5) 0; }
.kv { display: flex; justify-content: space-between; padding: 4px 0; }
.av__hint { margin-top: var(--space-3); line-height: 1.5; }
@media (min-width: 1024px) {
  .av__title { font-size: var(--fs-3xl); }
  .av__facts { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
