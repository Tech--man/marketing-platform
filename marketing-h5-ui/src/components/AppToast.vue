<script setup>
import { useToast } from "@/stores/toast";
import Icon from "@/components/Icon.vue";
const toast = useToast();
const ICON = { success: "check", error: "alert", info: "info" };
</script>

<template>
  <Teleport to="body">
    <div class="toaster" aria-live="polite">
      <transition-group name="toast">
        <div
          v-for="t in toast.items"
          :key="t.id"
          class="toast"
          :class="`toast--${t.type}`"
          @click="toast.dismiss(t.id)"
        >
          <Icon :name="ICON[t.type] || 'info'" :size="16" class="toast__ico" />
          <span class="toast__msg">{{ t.message }}</span>
        </div>
      </transition-group>
    </div>
  </Teleport>
</template>

<style scoped>
.toaster {
  position: fixed;
  left: 50%;
  bottom: calc(var(--nav-h) + var(--safe-b) + 20px);
  transform: translateX(-50%);
  z-index: var(--z-toast);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  width: min(92vw, 420px);
  pointer-events: none;
}
@media (min-width: 1024px) {
  .toaster { bottom: auto; top: 76px; }
}
.toast {
  pointer-events: auto;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 10px 18px;
  background: var(--c-text);
  color: var(--c-canvas);
  border-radius: var(--radius-pill);
  box-shadow: var(--shadow-2);
  font-size: var(--fs-sm);
  font-weight: 500;
  max-width: 100%;
}
.toast__ico { flex: none; opacity: 0.95; }
.toast--success .toast__ico { color: var(--c-success); }
.toast--error .toast__ico { color: var(--c-danger); }
.toast--info .toast__ico { color: var(--c-info); }
.toast__msg { line-height: 1.4; }
.toast-enter-active, .toast-leave-active { transition: all var(--dur) var(--ease); }
.toast-enter-from { opacity: 0; transform: translateY(12px) scale(0.97); }
.toast-leave-to { opacity: 0; transform: scale(0.97); }
</style>
