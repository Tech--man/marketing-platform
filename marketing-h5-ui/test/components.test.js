import { describe, it, expect, vi } from "vitest";
import { mount } from "@vue/test-utils";
import MPrice from "@/components/MPrice.vue";
import MButton from "@/components/MButton.vue";
import MStatusPill from "@/components/MStatusPill.vue";
import CouponCard from "@/components/CouponCard.vue";
import MCountdown from "@/components/MCountdown.vue";

describe("MPrice", () => {
  it("拆分货币符号/整数/小数并放大整数位", () => {
    const w = mount(MPrice, { props: { value: 1999.5 } });
    expect(w.find(".price__cur").text()).toBe("¥");
    expect(w.find(".price__int").text()).toBe("1999");
    expect(w.find(".price__dec").text()).toBe(".50");
  });
  it("给了 strike 才渲染划线原价", () => {
    expect(mount(MPrice, { props: { value: 100 } }).find(".price__strike").exists()).toBe(false);
    const w = mount(MPrice, { props: { value: 80, strike: 120 } });
    expect(w.find(".price__strike").text()).toContain("120.00");
  });
});

describe("MButton", () => {
  it("loading 时禁用并显示 spinner，占位 aria-busy", () => {
    const w = mount(MButton, { props: { loading: true } });
    const btn = w.find("button");
    expect(btn.attributes("disabled")).toBeDefined();
    expect(btn.attributes("aria-busy")).toBe("true");
    expect(w.find(".spinner").exists()).toBe(true);
  });
  it("disabled 与 variant 落到 class/attr", () => {
    const w = mount(MButton, { props: { disabled: true, variant: "ghost" } });
    expect(w.classes()).toContain("btn--ghost");
    expect(w.find("button").attributes("disabled")).toBeDefined();
  });
});

describe("MStatusPill", () => {
  it("tone 决定语义类，pulse 才有多普点", () => {
    const w = mount(MStatusPill, { props: { tone: "success", pulse: true } });
    expect(w.classes()).toContain("pill--success");
    expect(w.find(".dot--pulse").exists()).toBe(true);
  });
});

describe("CouponCard", () => {
  it("门槛>0 显示满减条件，否则无门槛", () => {
    expect(mount(CouponCard, { props: { faceValue: 20, threshold: 100, name: "x" } }).text()).toContain("满 100 可用");
    expect(mount(CouponCard, { props: { faceValue: 5, threshold: 0, name: "y" } }).text()).toContain("无门槛");
  });
});

describe("MCountdown", () => {
  it("目标已过期时渲染结束槽/文案", () => {
    const w = mount(MCountdown, { props: { target: Date.now() - 5000 } });
    expect(w.text()).toContain("已结束");
  });
  it("未过期时渲染出时/分/秒数字（曾有 bug：小时与分钟被冒号吞掉）", () => {
    // 挂钟必须冻住：目标写成 now+9s，组件在下一毫秒读表就变 08，
    // 这个用例曾有约一半的运行是"莫名失败"的。
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-09-25T12:00:00"));
    let w = null;
    try {
      const target = Date.now() + (2 * 3600 + 5 * 60 + 9) * 1000;
      w = mount(MCountdown, { props: { target } });
      const boxes = w.findAll(".box").map((b) => b.text());
      expect(boxes).toEqual(["02", "05", "09"]);
    } finally {
      w?.unmount();
      vi.useRealTimers();
    }
  });
});
