<script setup>
import { ref, reactive, onMounted } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import MButton from "@/components/MButton.vue";
import MStatusPill from "@/components/MStatusPill.vue";
import MState from "@/components/MState.vue";
import CouponCard from "@/components/CouponCard.vue";
import Icon from "@/components/Icon.vue";
import { couponApi } from "@/api";
import { ApiError, E, needsLogin, noteThrottle, messageFor, poll, grantDone } from "@/api/client";
import { uuid } from "@/utils/format";
import { COUPON_OFFERS } from "@/data/offers";
import { useSession } from "@/stores/session";
import { useToast } from "@/stores/toast";
import { toLogin } from "@/utils/auth";

const session = useSession();
const toast = useToast();
const route = useRoute();
const router = useRouter();

const stocks = reactive({});
const loading = ref(true);
const claim = reactive({});
COUPON_OFFERS.forEach((o) => (claim[o.templateNo] = { state: "idle", couponCode: "", message: "" }));

async function loadStocks() {
  loading.value = true;
  await Promise.all(
    COUPON_OFFERS.map(async (o) => {
      try {
        stocks[o.templateNo] = await couponApi.stock(o.templateNo);
      } catch (e) {
        if (e instanceof ApiError) noteThrottle(e);
        stocks[o.templateNo] = null;
      }
    })
  );
  loading.value = false;
}
onMounted(loadStocks);

async function grab(o) {
  const c = claim[o.templateNo];
  if (c.state === "submitting" || c.state === "processing") return;
  // 券落到谁的账上，由登录态判——游客能看库存，不能领
  if (!session.isLoggedIn) {
    toast.info("先登录，券才会落到你的卡包");
    await router.push(toLogin(route.fullPath));
    return;
  }
  c.state = "submitting";
  c.message = "";
  const requestId = uuid();
  try {
    await couponApi.grant({ requestId, templateNo: o.templateNo });
    c.state = "processing";
    const r = await poll(() => couponApi.grantResult(requestId), {
      isDone: grantDone,
      interval: 800,
      timeout: 24000,
    });
    if (r.status === "SUCCESS") {
      c.state = "success";
      c.couponCode = r.couponCode || "";
      c.message = r.message || "领取成功";
      stocks[o.templateNo] = Math.max(0, (stocks[o.templateNo] ?? 1) - 1);
      toast.success("领取成功，已放入卡包");
    } else {
      c.state = "failed";
      c.message = r.message || "领取失败";
      toast.error(c.message);
    }
  } catch (e) {
    c.state = "failed";
    if (e instanceof ApiError) {
      noteThrottle(e);
      c.message = messageFor(e);
    } else c.message = "领取失败，请稍后重试";
    if (e instanceof ApiError && needsLogin(e)) {
      c.message = "登录态已失效，重新登录后再领一次";
      await router.push(toLogin(route.fullPath));
    }
    if (e.code === E.STOCK || e.code === E.BIZ) await loadStocks();
    toast.error(c.message);
  }
}

function stockLabel(no) {
  const n = stocks[no];
  if (n == null) return "库存加载中";
  return n > 0 ? `余量 ${n.toLocaleString()} 张` : "已领光";
}
</script>

<template>
  <div>
    <Teleport to="#toolbar-actions">
      <RouterLink to="/wallet" class="btn btn--quiet btn--sm">我的卡包</RouterLink>
    </Teleport>

    <div class="cc">
      <div v-if="!session.isLoggedIn" class="banner banner--info cc__gate">
        <Icon name="person" :size="16" />
        <span class="grow">
          余量可以随便看，领取要落到账号上。
          <RouterLink :to="toLogin(route.fullPath)">登录</RouterLink>
          或<RouterLink :to="{ name: 'register', query: { redirect: route.fullPath } }">创建账号</RouterLink>
        </span>
      </div>
      <p class="cc__lede muted">
        券领取后请在有效期内使用；同一账号每人限领数量由活动规则约束。
      </p>

      <MState v-if="loading" variant="loading" title="加载中…" />
      <div v-else class="cc__grid">
        <div v-for="o in COUPON_OFFERS" :key="o.templateNo" class="cc__item">
          <CouponCard
            :face-value="o.faceValue"
            :threshold="o.thresholdAmount"
            :name="o.name"
            :scene="o.scene"
            :tone="o.tone"
            :expire-text="stockLabel(o.templateNo)"
            :dim="(stocks[o.templateNo] ?? 1) <= 0 && claim[o.templateNo].state !== 'success'"
          >
            <div class="cc__act">
              <MButton v-if="claim[o.templateNo].state === 'success'" variant="soft" size="sm" @click="$router.push('/cart')">
                去使用
              </MButton>
              <MButton v-else-if="claim[o.templateNo].state === 'processing'" size="sm" loading :disabled="true">
                发放中
              </MButton>
              <MButton
                v-else
                :variant="claim[o.templateNo].state === 'failed' ? 'ghost' : 'primary'"
                size="sm"
                :loading="claim[o.templateNo].state === 'submitting'"
                :disabled="(stocks[o.templateNo] ?? 1) <= 0"
                @click="grab(o)"
              >
                {{ (stocks[o.templateNo] ?? 1) <= 0 ? "已抢光" : claim[o.templateNo].state === 'failed' ? "再试一次" : "立即领取" }}
              </MButton>
            </div>
          </CouponCard>

          <div v-if="claim[o.templateNo].message && claim[o.templateNo].state !== 'idle'" class="cc__note">
            <MStatusPill :tone="claim[o.templateNo].state === 'success' ? 'success' : claim[o.templateNo].state === 'failed' ? 'danger' : 'info'">
              <Icon
                :name="claim[o.templateNo].state === 'success' ? 'check' : claim[o.templateNo].state === 'failed' ? 'alert' : 'clock'"
                :size="12"
              />
              {{ claim[o.templateNo].state === 'success' ? '已到手' : claim[o.templateNo].state === 'failed' ? '未成功' : '排队中' }}
            </MStatusPill>
            <span class="small muted">{{ claim[o.templateNo].message }}</span>
            <code v-if="claim[o.templateNo].couponCode" class="cc__code">{{ claim[o.templateNo].couponCode }}</code>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cc__gate { margin-bottom: var(--space-4); }
.cc__lede { font-size: var(--fs-sm); margin-bottom: var(--space-5); line-height: 1.5; }
.cc__grid { display: grid; gap: var(--space-4); }
@media (min-width: 1024px) {
  .cc__grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-5); max-width: 1000px; }
}
.cc__item { display: flex; flex-direction: column; gap: var(--space-2); }
.cc__act { min-width: 92px; }
.cc__note { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; padding: 0 var(--space-1); }
.cc__code { font-size: var(--fs-xs); }
</style>
