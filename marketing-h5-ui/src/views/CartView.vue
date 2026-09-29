<script setup>
import { ref, computed, watch, onMounted } from "vue";
import { RouterLink } from "vue-router";
import MButton from "@/components/MButton.vue";
import MPrice from "@/components/MPrice.vue";
import MState from "@/components/MState.vue";
import MSheet from "@/components/MSheet.vue";
import Icon from "@/components/Icon.vue";
import { discountApi, couponApi } from "@/api";
import { ApiError, noteThrottle, messageFor } from "@/api/client";
import { yuan } from "@/utils/format";
import { CATALOG, useCart } from "@/stores/cart";
import { useToast } from "@/stores/toast";

const cart = useCart();
const toast = useToast();

const calc = ref(null);
const calcLoading = ref(false);
const calcError = ref("");
const coupons = ref([]);
const addOpen = ref(false);

const RULE_TYPE = { FULL_REDUCTION: "满减", DISCOUNT: "折扣", LADDER: "阶梯" };

async function runCalc() {
  const input = cart.calcInput;
  if (!input) {
    calc.value = null;
    calcError.value = "";
    return;
  }
  calcLoading.value = true;
  try {
    calc.value = await discountApi.calculate(input);
    calcError.value = "";
  } catch (e) {
    if (e instanceof ApiError) noteThrottle(e);
    calcError.value = e instanceof ApiError ? messageFor(e) : "试算失败";
  } finally {
    calcLoading.value = false;
  }
}

// 行项目/标签变化 → 防抖重算。金额真相只在后端，这里不复刻满减/折扣逻辑。
let t = null;
watch(
  () => JSON.stringify(cart.calcInput),
  () => {
    clearTimeout(t);
    t = setTimeout(runCalc, 260);
  }
);

onMounted(async () => {
  if (!cart.lines.length) {
    cart.add(CATALOG[0]);
    cart.add(CATALOG[2], 2);
  }
  runCalc();
  try {
    coupons.value = (await couponApi.usable()) || [];
  } catch {
    coupons.value = [];
  }
});

function addFrom(p) {
  cart.add(p);
  addOpen.value = false;
}
const selectedCoupon = computed(() => cart.selectedCoupon);
function chooseCoupon(c) {
  cart.selectCoupon(c);
}

const payable = computed(() => calc.value?.payableAmount ?? 0);
const saved = computed(() => calc.value?.totalDiscount ?? 0);
const couponCut = computed(() => Number(selectedCoupon.value?.faceValue) || 0);
const grandTotal = computed(() => Math.max(0, Number(payable.value) - couponCut.value));

function itemLabel(lineId) {
  const l = cart.lines.find((x) => String(x.skuId) === String(lineId));
  return l ? l.name : `#${lineId}`;
}
</script>

<template>
  <div>
    <Teleport to="#toolbar-actions">
      <button class="btn btn--quiet btn--sm" @click="addOpen = true">加商品</button>
    </Teleport>

    <div v-if="!cart.lines.length">
      <MState icon="cart" title="购物车是空的">
        <MButton size="sm" @click="addOpen = true">添加商品</MButton>
      </MState>
    </div>

    <div v-else class="ct split">
      <!-- 左：商品行 + 试算明细 -->
      <div class="ct__left">
        <section class="card ct__lines">
          <div v-for="(l, i) in cart.lines" :key="l.skuId" class="line" :class="{ 'line--div': i }">
            <span class="line__thumb"><Icon :name="l.icon || 'bag'" :size="24" /></span>
            <div class="grow">
              <div class="line__name truncate">{{ l.name }}</div>
              <div class="line__spec muted tiny">{{ l.spec }}</div>
              <div class="line__tags">
                <span v-for="tag in l.tags" :key="tag" class="tag-mini">{{ tag }}</span>
              </div>
            </div>
            <div class="line__right">
              <MPrice :value="l.unitPrice * l.qty" size="sm" />
              <div class="stepper">
                <button class="stepper__btn" aria-label="减少" @click="cart.setQty(l.skuId, l.qty - 1)">
                  <Icon name="minus" :size="14" />
                </button>
                <span class="stepper__val">{{ l.qty }}</span>
                <button class="stepper__btn" aria-label="增加" @click="cart.setQty(l.skuId, l.qty + 1)">
                  <Icon name="plus" :size="14" />
                </button>
              </div>
            </div>
          </div>
        </section>

        <!-- 人群标签开关已随服务端 H9 收口移除：自报 userTags 会被 /api/discount/calculate
             覆写为空集，人群规则接入可信来源前不该有"自己给自己发会员价"的入口 -->
        <section class="ct__tags">
          <div class="row wrap" style="gap: var(--space-2)">
            <button class="btn btn--quiet btn--sm" @click="cart.clear()">清空购物车</button>
          </div>
        </section>

        <section class="card card--pad">
          <div class="ct__cardHead">
            <h2 class="strong">优惠试算</h2>
            <span v-if="calc?.degraded" class="pill pill--warning">引擎降级 · 按原价</span>
            <span v-else-if="calcLoading" class="muted tiny">重算中…</span>
          </div>

          <MState v-if="calcError" variant="error" title="试算失败" :hint="calcError">
            <button class="btn btn--ghost btn--sm" @click="runCalc">重试</button>
          </MState>

          <div v-else-if="calc?.appliedRules?.length" class="rules">
            <div v-for="r in calc.appliedRules" :key="r.ruleNo" class="rule">
              <div class="rule__top">
                <span class="rule__badge">{{ RULE_TYPE[r.type] || r.type }}</span>
                <span class="rule__name truncate">{{ r.name }}</span>
                <span class="amount-cut">-{{ yuan(r.discountAmount, { sign: false }) }}</span>
              </div>
              <div v-if="r.shares?.length" class="rule__shares tiny muted">
                分摊：<span v-for="(sh, k) in r.shares" :key="sh.lineId">
                  <template v-if="Number(sh.amount) > 0">{{ itemLabel(sh.lineId) }} -{{ Number(sh.amount).toFixed(2) }}{{ k < r.shares.length - 1 ? ' · ' : '' }}</template>
                </span>
              </div>
            </div>
          </div>
          <p v-else class="muted small">当前购物车未命中活动优惠规则，加购数码或凑单试试。</p>
        </section>
      </div>

      <!-- 堆叠态：试算与摘要接成一张可撕的券 -->
      <div class="ticket-seam" aria-hidden="true" />

      <!-- 右：结算摘要（桌面粘性） -->
      <aside class="split__aside">
        <section class="card card--pad ct__summary">
          <h2 class="strong ct__summaryTitle">摘要</h2>
          <div class="sum"><span class="muted">商品 {{ cart.totalQuantity }} 件</span><span class="num">{{ yuan(calc?.originalAmount) }}</span></div>
          <div class="sum"><span class="muted">活动优惠</span><span class="amount-cut num">-{{ yuan(saved) }}</span></div>
          <div v-if="selectedCoupon" class="sum"><span class="muted">优惠券</span><span class="amount-cut num">-{{ yuan(couponCut) }}</span></div>
          <hr class="divider" />
          <div class="sum sum--total"><span class="strong">合计</span><MPrice :value="grandTotal" /></div>

          <div class="ct__coupon">
            <div class="row row--between">
              <span class="muted small">优惠券</span>
              <span class="tiny muted">{{ coupons.length }} 张可用</span>
            </div>
            <div v-if="!coupons.length" class="tiny muted" style="margin-top: 6px">
              还没有可用券，<RouterLink to="/coupons">去领券</RouterLink>
            </div>
            <div v-else class="ct__couponList">
              <button
                v-for="c in coupons"
                :key="c.couponCode"
                class="cchip"
                :class="{ 'is-on': cart.selectedCouponCode === c.couponCode }"
                @click="chooseCoupon(c)"
              >
                <Icon name="ticket" :size="14" />
                <span class="cchip__amt">-{{ Number(c.faceValue).toFixed(2) }}</span>
                <span class="tiny muted">{{ Number(c.thresholdAmount) > 0 ? `满${c.thresholdAmount}` : '无门槛' }}</span>
                <Icon v-if="cart.selectedCouponCode === c.couponCode" name="check" :size="14" class="cchip__check" />
              </button>
            </div>
          </div>

          <MButton block size="lg" @click="$router.push('/checkout')">去结算（{{ cart.totalQuantity }} 件）</MButton>
        </section>
      </aside>
    </div>

    <MSheet :open="addOpen" title="添加商品" @close="addOpen = false">
      <div class="picker">
        <div v-for="p in CATALOG" :key="p.skuId" class="picker__row">
          <span class="picker__emoji"><Icon :name="p.icon" :size="22" /></span>
          <div class="grow">
            <div class="strong truncate">{{ p.name }}</div>
            <div class="muted tiny">{{ p.spec }} · {{ p.tags.join(' / ') }}</div>
          </div>
          <MPrice :value="p.unitPrice" size="sm" />
          <button class="icon-btn icon-btn--solid picker__add" aria-label="加入" @click="addFrom(p)">
            <Icon name="plus" :size="16" />
          </button>
        </div>
      </div>
    </MSheet>
  </div>
</template>

<style scoped>
.ct__left { min-width: 0; display: flex; flex-direction: column; gap: var(--space-5); }
.ct__lines { overflow: hidden; }
.line { display: flex; gap: var(--space-4); padding: var(--space-4) var(--space-5); align-items: center; }
.line--div { border-top: 1px solid var(--hairline); }
.line__thumb { width: 52px; height: 52px; border-radius: var(--radius-md); background: var(--c-surface-2); border: 1px solid var(--hairline); display: grid; place-items: center; color: var(--c-text-muted); flex: none; }
.line__name { font-weight: 600; font-size: var(--fs-base); letter-spacing: var(--tracking-body); }
.line__spec { margin-top: 1px; }
.line__tags { display: flex; gap: 4px; margin-top: 6px; }
.tag-mini { font-size: 10px; color: var(--c-text-muted); background: var(--c-surface-3); padding: 1px 7px; border-radius: var(--radius-pill); }
.line__right { display: flex; flex-direction: column; align-items: flex-end; gap: var(--space-2); flex: none; }

.ct__tags { display: flex; flex-direction: column; gap: var(--space-3); align-items: flex-start; }
.tag-toggle { border: 1px solid var(--c-border); background: var(--c-surface); color: var(--c-text-2); border-radius: var(--radius-pill); padding: 5px 14px; font-size: var(--fs-sm); font-weight: 500; }
.tag-toggle.is-on { background: var(--c-brand); border-color: transparent; color: #fff; }

.ct__cardHead { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); margin-bottom: var(--space-4); }
.ct__cardHead h2 { font-size: var(--fs-md); }
.rules { display: flex; flex-direction: column; gap: var(--space-4); }
.rule__top { display: flex; align-items: center; gap: var(--space-2); font-size: var(--fs-base); }
.rule__badge { flex: none; font-size: 11px; background: var(--c-brand-soft); color: var(--c-brand); padding: 2px 8px; border-radius: var(--radius-pill); font-weight: 600; }
.rule__name { flex: 1; min-width: 0; }
.rule__shares { margin-top: 4px; }

.ct__summary { display: flex; flex-direction: column; gap: var(--space-2); }
.ct__summaryTitle { font-size: var(--fs-md); margin-bottom: var(--space-2); }
.sum { display: flex; justify-content: space-between; align-items: baseline; font-size: var(--fs-base); }
.sum--total { font-size: var(--fs-md); margin-bottom: var(--space-4); }
.ct__coupon { margin: var(--space-4) 0 var(--space-5); padding-top: var(--space-4); border-top: 1px solid var(--hairline); display: flex; flex-direction: column; gap: var(--space-3); }
.ct__couponList { display: flex; flex-direction: column; gap: var(--space-2); }
.cchip { position: relative; display: flex; align-items: center; gap: var(--space-2); border: 1px solid var(--c-border); background: var(--c-surface); border-radius: var(--radius-md); padding: 10px var(--space-3); text-align: left; transition: border-color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease); }
.cchip:hover { border-color: var(--c-border-strong); }
.cchip.is-on { border-color: var(--c-brand); background: var(--c-brand-soft); }
.cchip__amt { font-weight: 600; color: var(--c-brand); }
.cchip__check { margin-left: auto; color: var(--c-brand); }

.picker { display: flex; flex-direction: column; }
.picker__row { display: flex; align-items: center; gap: var(--space-3); padding: var(--space-3) 0; border-bottom: 1px solid var(--hairline); }
.picker__row:last-child { border-bottom: 0; }
.picker__emoji { width: 44px; height: 44px; display: grid; place-items: center; background: var(--c-surface-2); border: 1px solid var(--hairline); border-radius: var(--radius-md); color: var(--c-text-muted); flex: none; }
.picker__add { flex: none; }

@media (min-width: 1024px) {
  .line { padding: var(--space-5) var(--space-6); }
}

/* 堆叠态：把"优惠试算"和"摘要"接成一张券——接缝两侧去掉圆角与描边，
   由 .ticket-seam 的虚线和打孔缺口承担分界。 */
@media (max-width: 1023.98px) {
  .ct { gap: 0; }
  .ct__left > .card:last-child {
    border-bottom-left-radius: 0;
    border-bottom-right-radius: 0;
    border-bottom-color: transparent;
  }
  .ct > .split__aside > .card {
    border-top-left-radius: 0;
    border-top-right-radius: 0;
    border-top-color: transparent;
  }
}
</style>
