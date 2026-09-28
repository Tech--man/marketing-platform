<script setup>
import { ref, reactive, computed, onMounted } from "vue";
import { RouterLink } from "vue-router";
import MCountdown from "@/components/MCountdown.vue";
import MStatusPill from "@/components/MStatusPill.vue";
import MState from "@/components/MState.vue";
import CouponCard from "@/components/CouponCard.vue";
import SeckillCard from "@/components/SeckillCard.vue";
import Icon from "@/components/Icon.vue";
import { activityApi, couponApi, seckillApi } from "@/api";
import { noteThrottle, messageFor, ApiError } from "@/api/client";
import { cents, yuan, pct, toDate } from "@/utils/format";
import { COUPON_OFFERS, HOME_ACTIVITY_NO } from "@/data/offers";
import { useSession } from "@/stores/session";

const session = useSession();
const NO = HOME_ACTIVITY_NO;

const loading = ref(true);
const error = ref("");
const activity = ref(null);
const canJoin = ref(false);
const gray = ref(false);
const remain = ref(null);
const stocks = reactive({});
const sessions = ref([]);

async function safe(fn, fb) {
  try {
    return await fn();
  } catch (e) {
    if (e instanceof ApiError) noteThrottle(e);
    return fb;
  }
}

function stockText(no) {
  const n = stocks[no];
  if (n == null) return "";
  return n > 0 ? `余量 ${n.toLocaleString()} 张` : "已领光";
}

async function load() {
  loading.value = true;
  error.value = "";
  try {
    // 灰度命中回的是"这个账号在不在这次灰度里"——游客没有账号，跳过而不是去撞 40100。
    const grayProbe = session.isLoggedIn
      ? safe(() => activityApi.grayHit(NO), false)
      : Promise.resolve(false);
    const [act, join, g, rem, sk] = await Promise.all([
      safe(() => activityApi.detail(NO), null),
      safe(() => activityApi.participatable(NO), false),
      grayProbe,
      safe(() => activityApi.budgetRemain(NO), null),
      safe(() => seckillApi.sessions(), []),
    ]);
    activity.value = act;
    canJoin.value = !!join;
    gray.value = !!g;
    remain.value = rem;
    sessions.value = Array.isArray(sk) ? sk.slice(0, 6) : [];
    await Promise.all(
      COUPON_OFFERS.map(async (o) => {
        stocks[o.templateNo] = await safe(() => couponApi.stock(o.templateNo), null);
      })
    );
    if (!act && !error.value) error.value = "未取到活动数据";
  } catch (e) {
    error.value = e instanceof ApiError ? messageFor(e) : "加载失败";
  } finally {
    loading.value = false;
  }
}
onMounted(load);

const usedPct = computed(() => {
  const a = activity.value;
  if (!a) return 0;
  return pct(Number(a.usedAmount) || 0, Number(a.budgetAmount) || 0);
});
const remainYuan = computed(() => (remain.value == null ? null : cents(remain.value)));
const endTarget = computed(() => toDate(activity.value?.endTime));
const started = computed(() => (toDate(activity.value?.startTime)?.getTime() ?? 0) <= Date.now());
</script>

<template>
  <div class="home">
    <MState v-if="loading" variant="loading" title="正在加载…" />
    <MState v-else-if="error" variant="error" title="加载失败" :hint="error">
      <button class="btn btn--ghost btn--sm" @click="load">重试</button>
    </MState>

    <template v-else>
      <!-- 进行中的活动：Apple 式编辑型主卡，靠排版层级而非色块撑场 -->
      <section v-if="activity" class="hero card card--pad">
        <div class="hero__main">
          <div class="hero__eyebrow">
            <Icon name="sparkles" :size="14" />
            进行中的活动
          </div>
          <h1 class="hero__title">{{ activity.name }}</h1>
          <div class="hero__pills">
            <MStatusPill :tone="canJoin ? 'success' : 'neutral'" :pulse="canJoin">
              {{ canJoin ? "进行中 · 可参加" : "暂不可参加" }}
            </MStatusPill>
            <MStatusPill v-if="gray" tone="info">抢先体验</MStatusPill>
          </div>
          <div class="hero__count">
            <span class="muted small">{{ started ? "距结束" : "距开始" }}</span>
            <MCountdown :target="endTarget" :to-start="!started">
              <template #ended>活动已结束</template>
            </MCountdown>
          </div>
          <RouterLink :to="`/activity/${NO}`" class="hero__link">
            查看活动详情 <Icon name="chevron" :size="14" />
          </RouterLink>
        </div>

        <div class="hero__side">
          <div class="hero__stat">
            <div class="hero__statLabel">剩余补贴</div>
            <div v-if="remainYuan != null" class="hero__statValue num">{{ yuan(remainYuan) }}</div>
            <div v-else class="muted small">读数暂不可用</div>
          </div>
          <div class="bar bar--lg"><div class="bar__fill" :style="{ width: usedPct + '%' }" /></div>
          <div class="row row--between tiny muted">
            <span>补贴进度</span><span>{{ usedPct }}% 已发放</span>
          </div>
        </div>
      </section>

      <!-- 神券 -->
      <section class="section">
        <div class="section__head">
          <h2 class="section__title">神券限时领</h2>
          <RouterLink to="/coupons" class="section__more">全部 <Icon name="chevron" :size="13" /></RouterLink>
        </div>
        <div class="rail">
          <div v-for="o in COUPON_OFFERS" :key="o.templateNo" class="rail__card">
            <CouponCard :face-value="o.faceValue" :threshold="o.thresholdAmount" :name="o.name"
              :scene="o.scene" :tone="o.tone" :expire-text="stockText(o.templateNo)">
              <RouterLink to="/coupons" class="btn btn--soft btn--sm">领取</RouterLink>
            </CouponCard>
          </div>
        </div>
      </section>

      <!-- 秒杀 -->
      <section class="section">
        <div class="section__head">
          <h2 class="section__title">限时秒杀</h2>
          <RouterLink to="/seckill" class="section__more">全部 <Icon name="chevron" :size="13" /></RouterLink>
        </div>
        <MState v-if="!sessions.length" icon="bolt" title="暂无进行中的秒杀" hint="下场好戏即将开抢" />
        <div v-else class="grid grid--cards">
          <SeckillCard v-for="s in sessions" :key="s.activityNo" :session="s" />
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
/* 主卡：移动竖排，桌面两列（正文 + 数据侧栏） */
.hero { margin-bottom: var(--space-7); }
.hero__eyebrow {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: var(--fs-xs); font-weight: 600; letter-spacing: 0.02em;
  color: var(--c-text-muted); margin-bottom: var(--space-3);
}
.hero__title { font-size: var(--fs-2xl); line-height: 1.12; }
.hero__pills { display: flex; gap: var(--space-2); margin: var(--space-4) 0; flex-wrap: wrap; }
.hero__count { display: flex; align-items: center; gap: var(--space-3); font-size: var(--fs-lg); }
.hero__link {
  display: inline-flex; align-items: center; gap: 2px;
  margin-top: var(--space-5); font-size: var(--fs-base); font-weight: 500; color: var(--c-brand);
}
.hero__link:hover { text-decoration: underline; }
.hero__side {
  margin-top: var(--space-5); padding-top: var(--space-5);
  border-top: 1px solid var(--hairline);
  display: flex; flex-direction: column; gap: var(--space-3);
}
.hero__statLabel { font-size: var(--fs-xs); color: var(--c-text-muted); margin-bottom: 2px; }
.hero__statValue { font-size: var(--fs-xl); font-weight: 600; letter-spacing: -0.02em; }
.bar--lg { height: 6px; }

@media (min-width: 1024px) {
  .hero {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 300px;
    gap: var(--space-8);
    padding: var(--space-8);
    align-items: center;
  }
  .hero__title { font-size: var(--fs-3xl); }
  .hero__side { margin-top: 0; padding-top: 0; border-top: 0; border-left: 1px solid var(--hairline); padding-left: var(--space-8); }
}

.rail__card { width: min(86vw, 380px); }
</style>
