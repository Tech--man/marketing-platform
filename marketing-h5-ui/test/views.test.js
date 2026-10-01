import { describe, it, expect, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createRouter, createMemoryHistory } from "vue-router";
import CartView from "@/views/CartView.vue";
import CheckoutView from "@/views/CheckoutView.vue";
import { useCart, CATALOG } from "@/stores/cart";

/**
 * N-19（v3 复审）：视图层回归闸。format.test.js 的 netPayable/cutYuan 用例钉住的是
 * 工具层契约——但第九批的病灶恰恰在**调用层**：把 payable 改回 `?? 0` 时工具层照旧
 * 全绿、界面重新显示 ¥0.00。这里从挂载端到端断言"试算未落地 → 合计渲染 --"，
 * 任何一层把 null 塌缩回 0 都会红。
 */
vi.mock("@/api", () => ({
  authApi: {},
  activityApi: { deduct: vi.fn(), budgetRemain: vi.fn() },
  couponApi: { usable: vi.fn(async () => []), consume: vi.fn() },
  discountApi: { calculate: vi.fn() },
  seckillApi: { sessions: vi.fn() },
}));

const stubs = { Teleport: true, RouterLink: true };

function freshRouter() {
  return createRouter({ history: createMemoryHistory(), routes: [] });
}

/** 每例独立 pinia；可选在挂载前播种购物车（CheckoutView 空车会被重定向走） */
async function mountView(View, { seedCart = false } = {}) {
  const pinia = createPinia();
  setActivePinia(pinia);
  if (seedCart) {
    useCart().add(CATALOG[0]);
  }
  const wrapper = mount(View, {
    global: { plugins: [pinia, freshRouter()], stubs },
  });
  await flushPromises();
  return wrapper;
}

describe("CartView · 金额读不到的呈现", () => {
  it("N-19：试算未落地（calculate 挂起）→ 合计显示 -- 且不印货币符，绝不出现 ¥0.00", async () => {
    const { discountApi } = await import("@/api");
    discountApi.calculate.mockImplementation(() => new Promise(() => {}));
    const w = await mountView(CartView);

    const total = w.find(".sum--total");
    expect(total.find(".price__int").text()).toBe("--");
    expect(total.find(".price__cur").exists()).toBe(false);
    // 商品原价行同样不许伪装：yuan(null) → "—"，而不是 ¥0.00
    expect(w.text()).not.toContain("¥0.00");
  });

  it("N-19（对照）：试算落地 → 合计显示真实金额，不是 --", async () => {
    const { discountApi } = await import("@/api");
    discountApi.calculate.mockImplementation(async () => ({
      payableAmount: "123.45",
      originalAmount: "200.00",
      totalDiscount: "76.55",
      appliedRules: [],
    }));
    const w = await mountView(CartView);

    const total = w.find(".sum--total");
    expect(total.find(".price__int").text()).toBe("123");
    expect(total.find(".price__dec").text()).toBe(".45");
  });
});

describe("CheckoutView · 金额读不到的呈现", () => {
  it("N-19：结算试算失败 → 应付合计显示 --，而不是把失败折叠成免费", async () => {
    const { discountApi } = await import("@/api");
    discountApi.calculate.mockImplementation(async () => {
      throw new Error("calc down");
    });
    const w = await mountView(CheckoutView, { seedCart: true });

    const total = w.find(".sum--total");
    expect(total.find(".price__int").text()).toBe("--");
    expect(total.find(".price__cur").exists()).toBe(false);
  });
});
