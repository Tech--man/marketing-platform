import { describe, it, expect, beforeEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { useSession } from "@/stores/session";
import { useCart, CATALOG } from "@/stores/cart";
import { authBridge } from "@/api/client";

const KEY = "mkt.h5.session";

function pair(over = {}) {
  return {
    accessToken: "AT-1",
    refreshToken: "RT-1",
    expiresInSeconds: 900,
    uid: 90001,
    identifier: "chen.2026",
    nickname: "陈",
    ...over,
  };
}

beforeEach(() => {
  setActivePinia(createPinia());
  localStorage.clear();
});

describe("session 登录态：默认是游客", () => {
  it("没有本地会话时 isLoggedIn=false，且不写任何垃圾键", () => {
    const s = useSession();
    expect(s.isLoggedIn).toBe(false);
    expect(s.accessToken).toBe("");
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it("localStorage 被写坏（非 JSON / 半截对象）也只退回游客，不炸启动", () => {
    localStorage.setItem(KEY, "{not json");
    setActivePinia(createPinia());
    expect(useSession().isLoggedIn).toBe(false);

    localStorage.setItem(KEY, JSON.stringify({ uid: 1 }));
    setActivePinia(createPinia());
    expect(useSession().isLoggedIn).toBe(false);
  });
});

describe("session 持久化与恢复", () => {
  it("applyTokenPair 落内存也落 localStorage，含到期时刻", () => {
    const s = useSession();
    const before = Date.now();
    expect(s.applyTokenPair(pair())).toBe(true);
    expect(s.isLoggedIn).toBe(true);
    expect(s.uid).toBe(90001);
    expect(s.identifier).toBe("chen.2026");
    const saved = JSON.parse(localStorage.getItem(KEY));
    expect(saved.accessToken).toBe("AT-1");
    expect(saved.refreshToken).toBe("RT-1");
    expect(saved.expiresAt).toBeGreaterThanOrEqual(before + 900 * 1000);
  });

  it("TokenPair 缺 accessToken 视为失败，不留半截会话", () => {
    const s = useSession();
    expect(s.applyTokenPair({ refreshToken: "RT" })).toBe(false);
    expect(s.isLoggedIn).toBe(false);
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it("新建一个 pinia（等价于整页 reload）后从 localStorage 恢复——hash 路由刷新不掉登录态", () => {
    useSession().applyTokenPair(pair());
    setActivePinia(createPinia());
    const restored = useSession();
    expect(restored.isLoggedIn).toBe(true);
    expect(restored.accessToken).toBe("AT-1");
    expect(restored.uid).toBe(90001);
    expect(restored.display).toBe("陈");
  });

  it("clear 把内存与 localStorage 一起抹掉", () => {
    const s = useSession();
    s.applyTokenPair(pair());
    s.clear();
    expect(s.isLoggedIn).toBe(false);
    expect(s.uid).toBe(0);
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it("refreshToken 缺省时保留旧的，别把可续期的会话刷成半截", () => {
    const s = useSession();
    s.applyTokenPair(pair());
    s.applyTokenPair({ accessToken: "AT-2", uid: 90001 });
    expect(s.accessToken).toBe("AT-2");
    expect(s.refreshToken).toBe("RT-1");
  });
});

describe("session 展示字段", () => {
  it("display 依次回落：昵称 → 登录名 → 兜底文案", () => {
    const s = useSession();
    expect(s.display).toBe("已登录用户");
    s.applyTokenPair(pair({ nickname: "", identifier: "chen.2026" }));
    expect(s.display).toBe("chen.2026");
    s.applyTokenPair(pair({ nickname: "陈" }));
    expect(s.display).toBe("陈");
  });

  it("avatarHue 由 uid 散列，稳定且不越界", () => {
    const s = useSession();
    s.applyTokenPair(pair({ uid: 90001 }));
    expect(s.avatarHue).toBe(90001 % 360);
    s.applyTokenPair(pair({ uid: 720 }));
    expect(s.avatarHue).toBe(0);
  });

  it("applyMe 用服务端权威身份覆盖展示字段", () => {
    const s = useSession();
    s.applyTokenPair(pair({ nickname: "旧名" }));
    s.applyMe({ uid: 90001, identifier: "chen.2026", nickname: "新名", status: "ACTIVE" });
    expect(s.nickname).toBe("新名");
    expect(s.status).toBe("ACTIVE");
    expect(JSON.parse(localStorage.getItem(KEY)).nickname).toBe("新名");
  });
});

describe("session ↔ api/client 的 bridge", () => {
  it("store 建立时把读写凭证的能力交给 client，且反映实时状态", () => {
    const s = useSession();
    const bridge = authBridge();
    expect(bridge, "client 没拿到 bridge：请求将永远以游客身份发出").toBeTruthy();
    expect(bridge.access()).toBeNull();
    expect(bridge.refresh()).toBeNull();

    s.applyTokenPair(pair());
    expect(bridge.access()).toBe("AT-1");
    expect(bridge.refresh()).toBe("RT-1");

    bridge.clear();
    expect(s.accessToken).toBe("");
    expect(localStorage.getItem(KEY)).toBeNull();

    const applied = bridge.apply(pair({ accessToken: "AT-9", uid: 5 }));
    expect(applied).toBe(true);
    expect(s.accessToken).toBe("AT-9");
    expect(s.uid).toBe(5);
  });
});

describe("cart 行项目与 CalcInput 构造", () => {
  it("加购同 SKU 合并数量", () => {
    const cart = useCart();
    cart.add(CATALOG[0]);
    cart.add(CATALOG[0], 3);
    expect(cart.lines.length).toBe(1);
    expect(cart.lines[0].qty).toBe(4);
    expect(cart.totalQuantity).toBe(4);
  });
  it("setQty<=0 即移除；remove 生效", () => {
    const cart = useCart();
    cart.add(CATALOG[1]);
    cart.setQty(CATALOG[1].skuId, 0);
    expect(cart.lines.length).toBe(0);
  });
  it("calcInput 把行映射成带 lineId 的 CalcItem；空车为 null", () => {
    const cart = useCart();
    expect(cart.calcInput).toBeNull();
    cart.add(CATALOG[2], 2);
    const input = cart.calcInput;
    expect(input.items[0].lineId).toBe(String(CATALOG[2].skuId));
    expect(input.items[0].quantity).toBe(2);
    expect(input.items[0].unitPrice).toBe(CATALOG[2].unitPrice);
  });
  it("calcInput 里不许出现 userId：身份由 access token 判，多一个 userId 就多一个越权入口", () => {
    const cart = useCart();
    cart.add(CATALOG[0]);
    const raw = JSON.stringify(cart.calcInput);
    expect("userId" in cart.calcInput).toBe(false);
    expect(raw).not.toContain("userId");
  });
  it("selectCoupon 幂等切换：选两次=取消", () => {
    const cart = useCart();
    const coupon = { couponCode: "C-1", faceValue: 5 };
    cart.selectCoupon(coupon);
    expect(cart.selectedCouponCode).toBe("C-1");
    cart.selectCoupon(coupon);
    expect(cart.selectedCouponCode).toBeNull();
  });
  it("calcInput 里不许出现 userTags：服务端 H9 收口后自报人群标签已被覆写，发了也是白发", () => {
    const cart = useCart();
    cart.add(CATALOG[0]);
    const raw = JSON.stringify(cart.calcInput);
    expect("userTags" in cart.calcInput).toBe(false);
    expect(raw).not.toContain("userTags");
  });
});
