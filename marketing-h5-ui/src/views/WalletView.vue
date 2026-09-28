<script setup>
import { ref, onMounted } from "vue";
import { RouterLink } from "vue-router";
import MState from "@/components/MState.vue";
import CouponCard from "@/components/CouponCard.vue";
import Icon from "@/components/Icon.vue";
import { couponApi } from "@/api";
import { ApiError, noteThrottle, messageFor } from "@/api/client";
import { formatDateTime } from "@/utils/format";
import { useSession } from "@/stores/session";

const session = useSession();
const loading = ref(true);
const error = ref("");
const coupons = ref([]);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const data = await couponApi.usable();
    coupons.value = Array.isArray(data) ? data : [];
  } catch (e) {
    if (e instanceof ApiError) noteThrottle(e);
    error.value = e instanceof ApiError ? messageFor(e) : "加载失败";
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>

<template>
  <div>
    <Teleport to="#toolbar-actions">
      <button class="icon-btn" aria-label="刷新" @click="load">
        <Icon name="refresh" :size="18" :class="{ 'is-spinning': loading }" />
      </button>
    </Teleport>

    <div class="wl">
      <div class="wl__head">
        <div>
          <div class="wl__count num">{{ coupons.length }}</div>
          <div class="muted small">张可用优惠券 · {{ session.display }}</div>
        </div>
      </div>

      <MState v-if="loading" variant="loading" title="加载中…" />
      <MState v-else-if="error" variant="error" title="加载失败" :hint="error">
        <button class="btn btn--ghost btn--sm" @click="load">重试</button>
      </MState>
      <MState v-else-if="!coupons.length" icon="ticket" title="还没有可用券" hint="去领券中心领一张，下单就能立减">
        <RouterLink to="/coupons" class="btn btn--primary btn--sm" style="margin-top: var(--space-2)">去领券</RouterLink>
      </MState>

      <div v-else class="wl__grid">
        <CouponCard
          v-for="c in coupons"
          :key="c.couponCode"
          :face-value="c.faceValue"
          :threshold="c.thresholdAmount"
          :name="c.name || (c.couponType === 'CASH' ? '无门槛代金券' : '满减券')"
          :scene="`券码 ${c.couponCode}`"
          :tone="c.couponType === 'CASH' ? 'brand' : 'gold'"
          :expire-text="`有效期至 ${formatDateTime(c.expireAt)}`"
        >
          <RouterLink to="/cart" class="btn btn--soft btn--sm">去使用</RouterLink>
        </CouponCard>
      </div>

      <p v-if="!loading && coupons.length" class="muted tiny wl__tip">已使用的券会从卡包中移出。</p>
    </div>
  </div>
</template>

<style scoped>
.wl__head { margin-bottom: var(--space-5); }
.wl__count { font-size: var(--fs-3xl); font-weight: 600; letter-spacing: var(--tracking-display); line-height: 1.1; }
.wl__grid { display: grid; gap: var(--space-4); }
@media (min-width: 1024px) {
  .wl__grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-5); }
}
.wl__tip { margin-top: var(--space-5); }
.is-spinning { animation: spin 0.9s linear infinite; }
</style>
