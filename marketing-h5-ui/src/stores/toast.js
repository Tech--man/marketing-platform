import { defineStore } from "pinia";
import { ref } from "vue";

/** 轻量 Toast 队列：成功/失败/提示三类，自动消隐，视图只调 push/success/error */
let seq = 0;

export const useToast = defineStore("toast", () => {
  const items = ref([]);

  function dismiss(id) {
    items.value = items.value.filter((t) => t.id !== id);
  }

  function push(message, type = "info", ms = 2600) {
    const id = ++seq;
    items.value.push({ id, message, type });
    setTimeout(() => dismiss(id), ms);
    return id;
  }

  return {
    items,
    dismiss,
    push,
    success: (m) => push(m, "success"),
    error: (m) => push(m, "error", 3400),
    info: (m) => push(m, "info"),
  };
});
