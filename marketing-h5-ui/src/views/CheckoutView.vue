<script setup>
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import MButton from "@/components/MButton.vue";
import MPrice from "@/components/MPrice.vue";
import MState from "@/components/MState.vue";
import Icon from "@/components/Icon.vue";
import { discountApi, couponApi, activityApi } from "@/api";
import { ApiError, noteThrottle, messageFor, E } from "@/api/client";
import { yuan, cutYuan, netPayable, hasAmt } from "@/utils/format";
import { useCart } from "@/stores/cart";
import { useSession } from "@/stores/session";
import { useToast } from "@/stores/toast";

const cart = useCart();
const session = useSession();
const toast = useToast();
const router = useRouter();

const calc = ref(null);
const loading = ref(true);
const submit = ref({ phase: "idle", steps: [], orderNo: "" });

const coupon = computed(() => cart.selectedCoupon);
// N-5：null 保持 null（读不到），由 MPrice/cutYuan 呈现 "—"/"--"，不塌缩成 0
const couponFace = computed(() => {
  const f = coupon.value?.faceValue;
  return hasAmt(f) ? Number(f) : null;
});

onMounted(async () => {
  if (!cart.lines.length) {
    router.replace("/cart");
    return;
  }
  try {
    calc.value = await discountApi.calculate(cart.calcInput);
  } catch (e) {
    if (e instanceof ApiError) noteThrottle(e);
    toast.error("结算信息加载失败");
  } finally {
    loading.value = false;
  }
});

const grandTotal = computed(() => netPayable(calc.value?.payableAmount, couponFace.value));

function step(name, state, detail) {
  const s = submit.value.steps.find((x) => x.name === name);
  if (s) {
    s.state = state;
    if (detail != null) s.detail = detail;
  } else submit.value.steps.push({ name, state, detail });
}

async function placeOrder() {
  if (submit.value.phase === "running") return;
  const orderNo = submit.value.orderNo || "ORD" + Date.now();
  submit.value.orderNo = orderNo;
  submit.value.phase = "running";
  submit.value.steps = [];
  const activityNo = cart.activityNo;

  // 先核销券，再扣补贴：券没成就不该动预算
  if (cart.selectedCouponCode) {
    step("核销优惠券", "run");
    try {
      await couponApi.consume({ couponCode: cart.selectedCouponCode, orderNo });
      step("核销优惠券", "ok", "已核销");
    } catch (e) {
      if (e instanceof ApiError) noteThrottle(e);
      const msg = e instanceof ApiError ? messageFor(e) : "核销失败";
      step("核销优惠券", "err", msg);
      submit.value.phase = "failed";
      toast.error(msg);
      return;
    }
  }

  const deductCents = Math.round(Number(calc.value?.totalDiscount || 0) * 100);
  if (deductCents > 0) {
    step("扣减活动补贴", "run");
    try {
      const r = await activityApi.deduct(activityNo, { amountCents: deductCents, bizKey: orderNo });
      step("扣减活动补贴", "ok", r === "REPLAYED" ? "已计入（重放）" : "已扣减");
    } catch (e) {
      if (e instanceof ApiError) noteThrottle(e);
      const msg = e instanceof ApiError ? messageFor(e) : "补贴扣减失败";
      const code = e instanceof ApiError ? e.code : 0;
      step("扣减活动补贴", "err", msg + (code === E.BUDGET ? "（补贴已发完）" : ""));
      submit.value.phase = "failed";
      toast.error(msg);
      return;
    }
  }

  step("生成订单", "ok", orderNo);
  submit.value.phase = "done";
  toast.success("下单成功");
  cart.clear();
}
</script>

<template>
  <div>
    <MState v-if="loading" variant="loading" title="正在准备结算…" />

    <!-- 成功页 -->
    <div v-else-if="submit.phase === 'done'" class="ck__done">
      <span class="ck__doneMark"><Icon name="check" :size="30" /></span>
      <h1 class="ck__doneTitle">下单成功</h1>
      <p class="muted small">订单号 <code>{{ submit.orderNo }}</code></p>
      <div class="card card--pad ck__steps">
        <div v-for="s in submit.steps" :key="s.name" class="dstep">
          <span class="dstep__dot" :class="`dstep__dot--${s.state}`">
            <Icon :name="s.state === 'ok' ? 'check' : s.state === 'err' ? 'alert' : 'clock'" :size="12" />
          </span>
          <span class="grow small">{{ s.name }}</span>
          <span class="muted tiny">{{ s.detail }}</span>
        </div>
      </div>
      <div class="row ck__doneActs">
        <MButton variant="ghost" style="flex:1" @click="$router.push('/home')">回首页</MButton>
        <MButton style="flex:1" @click="$router.push('/wallet')">看我的券</MButton>
      </div>
    </div>

    <!-- 结算：桌面左明细右摘要 -->
    <div v-else class="ck split">
      <div class="ck__left">
        <section class="card ck__items">
          <h2 class="ck__cardTitle">商品</h2>
          <div v-for="(l, i) in cart.lines" :key="l.skuId" class="ckline" :class="{ 'ckline--div': i }">
            <span class="grow truncate">{{ l.name }}<span class="muted tiny"> × {{ l.qty }}</span></span>
            <span class="num">{{ yuan(l.unitPrice * l.qty) }}</span>
          </div>
        </section>

        <section class="card card--pad">
          <h2 class="ck__cardTitle">下单信息</h2>
          <div class="kv"><span class="muted small">下单账号</span><span class="small">{{ session.display }}</span></div>
          <div class="kv"><span class="muted small">活动编号</span><span class="num small">{{ cart.activityNo }}</span></div>
          <div class="kv"><span class="muted small">优惠券</span><span class="small">{{ coupon?.couponCode || '未使用' }}</span></div>
        </section>

        <div v-if="submit.phase === 'failed'" class="banner banner--danger">
          <Icon name="alert" :size="18" />
          <div>
            <div class="strong" style="margin-bottom: 6px">下单未完成，请核对每一步：</div>
            <div v-for="s in submit.steps" :key="s.name" class="dstep">
              <span class="dstep__dot" :class="`dstep__dot--${s.state}`">
                <Icon :name="s.state === 'ok' ? 'check' : s.state === 'err' ? 'alert' : 'clock'" :size="12" />
              </span>
              <span class="grow small">{{ s.name }}</span>
              <span class="muted tiny">{{ s.detail }}</span>
            </div>
            <p class="tiny muted" style="margin-top: 8px">已成功的步骤不会重复扣减（同一订单号幂等），可放心重试。</p>
          </div>
        </div>
      </div>

      <aside class="split__aside">
        <section class="card card--pad">
          <h2 class="ck__cardTitle">应付</h2>
          <div class="sum"><span class="muted">商品 {{ cart.totalQuantity }} 件</span><span class="num">{{ yuan(calc?.originalAmount) }}</span></div>
          <div class="sum"><span class="muted">活动优惠</span><span class="amount-cut num">{{ cutYuan(calc?.totalDiscount) }}</span></div>
          <div v-if="coupon" class="sum"><span class="muted">优惠券</span><span class="amount-cut num">{{ cutYuan(couponFace) }}</span></div>
          <hr class="divider" />
          <div class="sum sum--total"><span class="strong">合计</span><MPrice :value="grandTotal" /></div>

          <div v-if="calc?.degraded" class="banner banner--warning" style="margin-bottom: var(--space-4)">
            <Icon name="alert" :size="16" /><span>优惠引擎暂时降级，本次按原价结算。</span>
          </div>

          <MButton block size="lg" :loading="submit.phase === 'running'" @click="placeOrder">
            {{ submit.phase === 'failed' ? '重试下单' : '提交订单' }}
          </MButton>
          <p class="tiny muted ck__agree" style="margin-top: var(--space-3)">
            提交即表示同意本活动的补贴与用券规则。
          </p>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.ck__left { min-width: 0; display: flex; flex-direction: column; gap: var(--space-5); }
.ck__cardTitle { font-size: var(--fs-md); margin-bottom: var(--space-4); }
.ck__items { padding: var(--space-5) 0; overflow: hidden; }
.ck__items .ck__cardTitle { padding: 0 var(--space-5); }
.ckline { display: flex; align-items: center; gap: var(--space-3); padding: var(--space-3) var(--space-5); font-size: var(--fs-base); }
.ckline--div { border-top: 1px solid var(--hairline); }
.kv { display: flex; justify-content: space-between; align-items: baseline; padding: 6px 0; }
.sum { display: flex; justify-content: space-between; align-items: baseline; font-size: var(--fs-base); padding: 3px 0; }
.sum--total { font-size: var(--fs-md); margin-bottom: var(--space-4); }
.dstep { display: flex; align-items: center; gap: var(--space-2); padding: 3px 0; }
.dstep__dot { width: 18px; height: 18px; border-radius: 50%; display: grid; place-items: center; background: var(--c-surface-3); color: var(--c-text-muted); flex: none; }
.dstep__dot--ok { background: var(--c-success-soft); color: var(--c-success); }
.dstep__dot--err { background: var(--c-danger-soft); color: var(--c-danger); }
.dstep__dot--run { background: var(--c-brand-soft); color: var(--c-brand); }
.ck__agree { line-height: 1.5; }

.ck__done { max-width: 480px; margin: var(--space-7) auto; text-align: center; }
.ck__doneMark { width: 68px; height: 68px; border-radius: 50%; background: var(--c-success-soft); color: var(--c-success); display: grid; place-items: center; margin: 0 auto var(--space-4); }
.ck__doneTitle { font-size: var(--fs-xl); margin-bottom: var(--space-2); }
.ck__steps { text-align: left; margin: var(--space-6) 0; }
.ck__doneActs { display: flex; gap: var(--space-3); }
</style>
