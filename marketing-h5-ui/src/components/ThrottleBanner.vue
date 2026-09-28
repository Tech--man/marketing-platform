<script setup>
import { throttleState, clearThrottle } from "@/api/client";
function refresh() {
  clearThrottle();
  location.reload();
}
</script>

<template>
  <transition name="slide">
    <div v-if="throttleState.active" class="throttle" role="alert">
      <span class="throttle__dot" />
      <span class="throttle__txt">
        当前访问火爆，已进入排队{{ throttleState.queueCode ? `（${throttleState.queueCode}）` : "" }}
      </span>
      <button class="throttle__act" @click="refresh">重试</button>
    </div>
  </transition>
</template>

<style scoped>
.throttle {
  position: sticky;
  top: 0;
  z-index: var(--z-nav);
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background: var(--c-warning-soft);
  color: var(--c-warning);
  font-size: var(--fs-sm);
  font-weight: 600;
  padding-top: calc(var(--space-3) + var(--safe-t));
}
.throttle__txt { flex: 1; }
.throttle__dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; animation: blink 1s infinite; }
.throttle__act {
  border: 1px solid currentColor; background: transparent; color: inherit;
  border-radius: var(--radius-pill); padding: 3px 14px; font-weight: 700; font-size: var(--fs-xs);
}
@keyframes blink { 50% { opacity: 0.3; } }
.slide-enter-active, .slide-leave-active { transition: all var(--dur) var(--ease); }
.slide-enter-from, .slide-leave-to { opacity: 0; transform: translateY(-100%); }
</style>
