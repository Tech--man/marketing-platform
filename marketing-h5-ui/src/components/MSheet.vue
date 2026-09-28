<script setup>
import { onMounted, onBeforeUnmount, watch } from "vue";
import Icon from "./Icon.vue";

const props = defineProps({
  open: Boolean,
  title: { type: String, default: "" },
});
const emit = defineEmits(["close"]);

function onKey(e) {
  if (e.key === "Escape") emit("close");
}
function lock(on) {
  document.body.style.overflow = on ? "hidden" : "";
}
watch(
  () => props.open,
  (v) => {
    lock(v);
    if (v) document.addEventListener("keydown", onKey);
    else document.removeEventListener("keydown", onKey);
  }
);
onBeforeUnmount(() => {
  lock(false);
  document.removeEventListener("keydown", onKey);
});
</script>

<template>
  <Teleport to="body">
    <transition name="sheet">
      <div v-if="open" class="sheet-root">
        <div class="mask" @click="emit('close')" />
        <div class="sheet" role="dialog" aria-modal="true" :aria-label="title">
          <div class="sheet__grab" aria-hidden="true" />
          <div v-if="title || $slots.head" class="sheet__head">
            <h3>{{ title }}</h3>
            <button class="icon-btn icon-btn--solid" aria-label="关闭" @click="emit('close')">
              <Icon name="close" :size="16" />
            </button>
          </div>
          <div class="sheet__body">
            <slot />
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped>
.sheet-root { position: fixed; inset: 0; z-index: var(--z-mask); }
.sheet__x { font-size: var(--fs-lg); line-height: 1; }
.sheet-enter-active, .sheet-leave-active { transition: opacity var(--dur) var(--ease); }
.sheet-enter-active .sheet, .sheet-leave-active .sheet {
  transition: transform var(--dur) var(--ease);
}
.sheet-enter-from, .sheet-leave-to { opacity: 0; }
.sheet-enter-from .sheet, .sheet-leave-to .sheet { transform: translateY(100%); }
@media (min-width: 768px) {
  .sheet-enter-from .sheet, .sheet-leave-to .sheet { transform: translateX(-50%) translateY(12px) scale(0.98); }
}
</style>
