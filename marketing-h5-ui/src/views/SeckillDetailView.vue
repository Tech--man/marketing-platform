<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from "vue";
import { useRoute, useRouter } from "vue-router";
import MButton from "@/components/MButton.vue";
import MPrice from "@/components/MPrice.vue";
import MCountdown from "@/components/MCountdown.vue";
import MStatusPill from "@/components/MStatusPill.vue";
import MState from "@/components/MState.vue";
import Icon from "@/components/Icon.vue";
import { seckillApi } from "@/api";
import { ApiError, needsLogin, noteThrottle, messageFor, E } from "@/api/client";
import { toDate } from "@/utils/format";
import { useSession } from "@/stores/session";
import { useToast } from "@/stores/toast";
import { toLogin } from "@/utils/auth";

const route = useRoute();
const router = useRouter();
// 这个页面里 session 一词已经被"秒杀场次"占了，登录态这里叫 account——两个都可读，重名不可读
const account = useSession();
const toast = useToast();
const NO = route.params.no;

const loading = ref(true);
const error = ref("");
const session = ref(null);
const remaining = ref(null);

const stage = ref("idle"); // idle|grabbing|polling|won|lost|paying|paid
const order = ref(null);
const lostReason = ref("");
let grabToken = null;
let pollTimer = null;
let stockTimer = null;

const PAY_WINDOW_MS = 5 * 60 * 1000;
const payDeadline = ref(0);

const phase = computed(() => {
  const now = Date.now();
  const start = toDate(session.value?.startTime)?.getTime() ?? 0;
  const end = toDate(session.value?.endTime)?.getTime() ?? 0;
  if (now < start) return "upcoming";
  if (now < end) return "live";
  return "ended";
});
const soldPct = computed(() => {
  const s = session.value;
  if (!s || !s.totalStock) return 0;
  const sold = s.totalStock - (remaining.value ?? s.soldStock ?? 0);
  return Math.max(0, Math.min(100, Math.round((sold / s.totalStock) * 100)));
});
const canGrab = computed(() => phase.value === "live" && (remaining.value ?? 1) > 0);

function stopTimers() {
  pollTimer && clearTimeout(pollTimer);
  stockTimer && clearInterval(stockTimer);
  pollTimer = stockTimer = null;
}

async function refreshStock() {
  try {
    const buckets = await seckillApi.bucketStock(NO);
    if (Array.isArray(buckets)) remaining.value = buckets.reduce((a, b) => a + (Number(b) || 0), 0);
  } catch (e) {
    if (e instanceof ApiError) noteThrottle(e);
  }
}

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const list = await seckillApi.sessions();
    session.value = (Array.isArray(list) ? list : []).find((x) => x.activityNo === NO) || null;
    if (!session.value) {
      error.value = "这场秒杀已下线或不存在";
      return;
    }
    await refreshStock();
    stockTimer = setInterval(refreshStock, 5000);
  } catch (e) {
    if (e instanceof ApiError) noteThrottle(e);
    error.value = e instanceof ApiError ? messageFor(e) : "加载失败";
  } finally {
    loading.value = false;
  }
}

function pollResult() {
  stage.value = "polling";
  pollTimer = setTimeout(async () => {
    try {
      const r = await seckillApi.grabResult(grabToken);
      const v = String(r?.result ?? "");
      if (v.startsWith("SUCCESS:")) {
        order.value = { orderNo: v.slice(8), amount: session.value?.seckillPrice };
        payDeadline.value = Date.now() + PAY_WINDOW_MS;
        stage.value = "won";
        toast.success("抢到啦！请在 5 分钟内支付");
      } else if (v.startsWith("FAIL:")) {
        lostReason.value = v.slice(5) || "很遗憾，没抢到";
        stage.value = "lost";
        refreshStock();
      } else if (v === "NOT_FOUND") {
        lostReason.value = "抢购结果已过期";
        stage.value = "lost";
      } else {
        pollResult();
      }
    } catch (e) {
      if (e instanceof ApiError) noteThrottle(e);
      lostReason.value = "查询抢购结果失败，请稍后在订单页查看";
      stage.value = "lost";
    }
  }, 700);
}

async function grab() {
  if (stage.value === "grabbing" || stage.value === "polling") return;
  // 名额占在谁名下、订单归谁，全由登录态判：游客能看余量，不能抢
  if (!account.isLoggedIn) {
    toast.info("先登录，抢到的单才是你的");
    await router.push(toLogin(route.fullPath));
    return;
  }
  stage.value = "grabbing";
  lostReason.value = "";
  try {
    const t = await seckillApi.grab({ activityNo: NO });
    grabToken = t?.token;
    if (!grabToken) throw new ApiError(E.SYSTEM, "未获取到抢购凭证", null);
    pollResult();
  } catch (e) {
    if (e instanceof ApiError) noteThrottle(e);
    stage.value = "lost";
    lostReason.value = e instanceof ApiError ? messageFor(e) : "抢购失败";
    if (e instanceof ApiError && needsLogin(e)) {
      lostReason.value = "登录态已失效，重新登录后再抢";
      await router.push(toLogin(route.fullPath));
    }
    if (e instanceof ApiError && (e.code === E.STOCK || e.code === E.NOT_ONLINE)) refreshStock();
  }
}

function onPayExpired() {
  if (stage.value === "won" || stage.value === "paying") {
    lostReason.value = "超时未支付，订单已自动取消并回补库存";
    stage.value = "lost";
    refreshStock();
  }
}

async function pay() {
  stage.value = "paying";
  try {
    const o = await seckillApi.pay(order.value.orderNo);
    order.value = { ...order.value, ...o };
    stage.value = "paid";
    toast.success("支付成功（模拟）");
  } catch (e) {
    if (e instanceof ApiError) noteThrottle(e);
    stage.value = "won";
    toast.error(e instanceof ApiError ? messageFor(e) : "支付失败");
  }
}

onMounted(load);
onBeforeUnmount(stopTimers);
</script>

<template>
  <div>
    <MState v-if="loading" variant="loading" title="加载中…" />
    <MState v-else-if="error" variant="error" title="这场秒杀结束了" :hint="error">
      <button class="btn btn--ghost btn--sm" @click="router.push('/seckill')">返回场次列表</button>
    </MState>

    <div v-else class="skd split split--wide">
      <!-- 左：商品与实况 -->
      <div class="skd__left">
        <div class="skd__stage">
          <Icon name="bolt" :size="88" :stroke-width="1" />
          <MStatusPill class="skd__tag" :tone="phase === 'live' ? 'danger' : phase === 'upcoming' ? 'info' : 'neutral'" :pulse="phase === 'live'">
            {{ phase === 'live' ? '抢购中' : phase === 'upcoming' ? '即将开抢' : '已结束' }}
          </MStatusPill>
        </div>

        <h1 class="skd__name">{{ session.itemName }}</h1>

        <dl class="skd__facts">
          <div><dt>活动编号</dt><dd class="num">{{ session.activityNo }}</dd></div>
          <div><dt>商品 ID</dt><dd class="num">{{ session.itemId }}</dd></div>
          <div><dt>分桶数</dt><dd class="num">{{ session.buckets }}</dd></div>
          <div><dt>总限量</dt><dd class="num">{{ session.totalStock }} 件</dd></div>
        </dl>

        <div class="skd__live">
          <div class="row row--between">
            <span class="muted small">实时余量</span>
            <span class="num strong">{{ remaining == null ? '—' : remaining }} 件</span>
          </div>
          <div class="bar bar--lg"><div class="bar__fill" :style="{ width: soldPct + '%' }" /></div>
          <div class="row row--between tiny muted">
            <span>{{ phase === 'upcoming' ? '距开始' : '距结束' }}</span>
            <MCountdown :target="phase === 'upcoming' ? session.startTime : session.endTime" :to-start="phase === 'upcoming'">
              <template #ended>本场已结束</template>
            </MCountdown>
          </div>
        </div>
      </div>

      <!-- 右：购买面板（桌面粘性） -->
      <aside class="skd__panel card card--pad split__aside">
        <div class="skd__priceRow">
          <MPrice :value="session.seckillPrice" size="lg" />
          <span class="pill pill--brand">秒杀价</span>
        </div>

        <!-- 状态机 -->
        <template v-if="stage === 'idle' || stage === 'lost'">
          <div v-if="stage === 'lost'" class="banner banner--danger skd__banner">
            <Icon name="alert" :size="16" />
            <span>{{ lostReason }}</span>
          </div>
          <MButton v-if="phase === 'live'" block size="lg" :disabled="!canGrab" @click="grab">
            {{ canGrab ? '立即抢购' : '已抢光' }}
          </MButton>
          <MButton v-else-if="phase === 'upcoming'" block size="lg" variant="soft" disabled>还没开抢</MButton>
          <MButton v-else block size="lg" variant="ghost" disabled>本场已结束</MButton>
        </template>

        <div v-else-if="stage === 'grabbing' || stage === 'polling'" class="skd__wait">
          <span class="spinner spinner--big" />
          <div class="strong">{{ stage === 'grabbing' ? '正在抢占名额…' : '排队下单中…' }}</div>
          <div class="muted small">别退出，几秒内给你结果</div>
        </div>

        <template v-else-if="stage === 'won' || stage === 'paying'">
          <div class="skd__won">
            <Icon name="check" :size="16" class="skd__wonIco" />
            <div class="grow">
              <div class="strong">抢到了</div>
              <div class="tiny muted">5 分钟内未支付将自动取消</div>
            </div>
            <MCountdown :target="payDeadline" @finish="onPayExpired" />
          </div>
          <div class="row row--between skd__orderNo">
            <span class="muted small">订单号</span>
            <code>{{ order.orderNo }}</code>
          </div>
          <MButton block size="lg" :loading="stage === 'paying'" @click="pay">立即支付</MButton>
          <button class="btn btn--quiet btn--sm skd__later" @click="router.push('/seckill')">稍后再说</button>
        </template>

        <template v-else-if="stage === 'paid'">
          <div class="skd__paid">
            <span class="skd__paidMark"><Icon name="check" :size="26" /></span>
            <div class="strong" style="font-size: var(--fs-md)">支付成功</div>
            <p class="muted small">订单号 {{ order.orderNo }} · 已锁定秒杀价</p>
          </div>
          <div class="row" style="gap: var(--space-3)">
            <MButton variant="ghost" style="flex:1" @click="router.push('/seckill')">继续逛</MButton>
            <MButton style="flex:1" @click="router.push('/home')">回首页</MButton>
          </div>
        </template>

        <p class="skd__note tiny muted">
          下单后 5 分钟内未支付将自动取消并回补库存；秒杀价已按活动配置生效。
        </p>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.skd__left { min-width: 0; }
.skd__stage {
  position: relative;
  height: 220px;
  display: grid;
  place-items: center;
  background: var(--c-surface);
  border: 1px solid var(--hairline);
  border-radius: var(--radius-xl);
  color: var(--c-text-faint);
}
.skd__tag { position: absolute; top: var(--space-4); left: var(--space-4); }
.skd__name { font-size: var(--fs-xl); margin: var(--space-6) 0 var(--space-5); }
.skd__facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3) var(--space-5);
  margin: 0 0 var(--space-6);
  padding: var(--space-5) 0;
  border-top: 1px solid var(--hairline);
  border-bottom: 1px solid var(--hairline);
}
.skd__facts dt { font-size: var(--fs-xs); color: var(--c-text-muted); margin-bottom: 2px; }
.skd__facts dd { margin: 0; font-size: var(--fs-base); font-weight: 500; }
.skd__live { display: flex; flex-direction: column; gap: var(--space-3); }
.bar--lg { height: 6px; }

.skd__panel { margin-top: var(--space-6); display: flex; flex-direction: column; gap: var(--space-4); }
.skd__priceRow { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); }
.skd__banner { align-items: center; }
.skd__wait { display: flex; flex-direction: column; align-items: center; gap: var(--space-3); padding: var(--space-6) 0; text-align: center; }
.spinner--big { width: 28px; height: 28px; border-width: 2.5px; color: var(--c-brand); }
.skd__won { display: flex; align-items: center; gap: var(--space-2); padding: var(--space-3); background: var(--c-success-soft); border-radius: var(--radius-md); color: var(--c-success); }
.skd__wonIco { flex: none; }
.skd__orderNo { font-size: var(--fs-sm); }
.skd__later { align-self: center; }
.skd__paid { display: flex; flex-direction: column; align-items: center; gap: var(--space-2); text-align: center; padding: var(--space-4) 0; }
.skd__paidMark { width: 56px; height: 56px; border-radius: 50%; background: var(--c-success-soft); color: var(--c-success); display: grid; place-items: center; }
.skd__note { line-height: 1.5; }

@media (min-width: 1024px) {
  .skd__stage { height: 340px; }
  .skd__name { font-size: var(--fs-2xl); }
  .skd__facts { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .skd__panel { margin-top: 0; padding: var(--space-6); }
}
</style>
