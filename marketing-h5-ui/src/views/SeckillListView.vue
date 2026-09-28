<script setup>
import { ref, onMounted, onBeforeUnmount } from "vue";
import MState from "@/components/MState.vue";
import SeckillCard from "@/components/SeckillCard.vue";
import Icon from "@/components/Icon.vue";
import { seckillApi } from "@/api";
import { ApiError, noteThrottle, messageFor } from "@/api/client";

const loading = ref(true);
const error = ref("");
const sessions = ref([]);
let timer = null;

async function load(silent = false) {
  if (!silent) loading.value = true;
  try {
    const data = await seckillApi.sessions();
    sessions.value = Array.isArray(data) ? data : [];
    error.value = "";
  } catch (e) {
    if (e instanceof ApiError) noteThrottle(e);
    if (!silent) error.value = e instanceof ApiError ? messageFor(e) : "加载失败";
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  load();
  timer = setInterval(() => load(true), 15000);
});
onBeforeUnmount(() => timer && clearInterval(timer));
</script>

<template>
  <div>
    <div class="sl__lede">
      <span class="sl__ledeIcon"><Icon name="clock" :size="15" /></span>
      <span>场次与库存每 15 秒自动刷新，开抢后无需手动重进</span>
    </div>

    <MState v-if="loading" variant="loading" title="正在加载场次…" />
    <MState v-else-if="error" variant="error" title="加载失败" :hint="error">
      <button class="btn btn--ghost btn--sm" @click="load()">重试</button>
    </MState>
    <MState v-else-if="!sessions.length" icon="bolt" title="今天没有进行中的秒杀" hint="晚点再来蹲一场" />
    <div v-else class="grid grid--cards">
      <SeckillCard v-for="s in sessions" :key="s.activityNo" :session="s" />
    </div>
  </div>
</template>

<style scoped>
.sl__lede {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: var(--fs-sm);
  color: var(--c-text-muted);
  margin-bottom: var(--space-5);
}
.sl__ledeIcon { display: inline-flex; color: var(--c-text-faint); }
</style>
