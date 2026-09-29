import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { activityApi, couponApi, discountApi, seckillApi, authApi } from "@/api";
import { bindAuthBridge } from "@/api/client";
import { useSession } from "@/stores/session";

/**
 * 线形（wire shape）回归：账号体系上线后这些端点**不再接受调用方自报 userId**，
 * 身份由 access token 判。这里钉住"发出去的 body/query 长什么样"——
 * 少一条断言，就多一个把 userId 塞回去的机会。
 */

const seen = [];

beforeEach(() => {
  setActivePinia(createPinia());
  localStorage.clear();
  seen.length = 0;
  bindAuthBridge(null);
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url, opts = {}) => {
      seen.push({
        url,
        method: opts.method || "GET",
        body: opts.body ? JSON.parse(opts.body) : undefined,
        auth: (opts.headers || {}).Authorization,
      });
      const data =
        url === "/api/auth/refresh"
          ? { accessToken: "AT-new", refreshToken: "RT-2", expiresInSeconds: 900, uid: 3 }
          : { ok: true };
      return { status: 200, json: async () => ({ code: 0, message: "OK", data, success: true }), headers: new Headers() };
    })
  );
});
afterEach(() => vi.unstubAllGlobals());

function last() {
  return seen[seen.length - 1];
}

describe("券 /api/coupon", () => {
  it("grant 只带 requestId + templateNo", async () => {
    await couponApi.grant({ requestId: "R-1", templateNo: "CT2026001" });
    expect(last()).toMatchObject({ method: "POST", url: "/api/coupon/grant", body: { requestId: "R-1", templateNo: "CT2026001" } });
    expect("userId" in last().body).toBe(false);
  });

  it("consume 只带 couponCode + orderNo", async () => {
    await couponApi.consume({ couponCode: "C-1", orderNo: "O-1" });
    expect(last().body).toEqual({ couponCode: "C-1", orderNo: "O-1" });
  });

  it("usable 不带任何查询参数", async () => {
    await couponApi.usable();
    expect(last().url).toBe("/api/coupon/usable");
    expect(last().url).not.toContain("?");
  });

  it("stock 是游客口：不带 Authorization", async () => {
    await couponApi.stock("CT2026001");
    expect(last().url).toBe("/api/coupon/stock/CT2026001");
    expect(last().auth).toBeUndefined();
  });

  it("grantResult 是登录口：带上 access token", async () => {
    useSession().applyTokenPair({ accessToken: "AT-1", refreshToken: "RT-1", uid: 1, expiresInSeconds: 900 });
    await couponApi.grantResult("R-1");
    expect(last().url).toBe("/api/coupon/grant/result/R-1");
    expect(last().auth).toBe("Bearer AT-1");
  });
});

describe("活动 /api/activity", () => {
  it("gray-hit 不带查询参数（灰度按 token 里的人判）", async () => {
    await activityApi.grayHit("ACT2026001");
    expect(last().url).toBe("/api/activity/ACT2026001/gray-hit");
  });

  it("detail / participatable / budgetRemain 是游客口", async () => {
    await Promise.all([
      activityApi.detail("ACT2026001"),
      activityApi.participatable("ACT2026001"),
      activityApi.budgetRemain("ACT2026001"),
    ]);
    expect(seen.map((s) => s.url)).toEqual([
      "/api/activity/ACT2026001",
      "/api/activity/ACT2026001/participatable",
      "/api/activity/ACT2026001/budget/remain",
    ]);
    expect(seen.every((s) => s.auth === undefined)).toBe(true);
  });
});

describe("优惠计算 / 秒杀", () => {
  it("calculate 的 body 里没有 userId / userTags", async () => {
    await discountApi.calculate({ activityNo: "ACT2026001", items: [{ lineId: "1" }] });
    expect(last().url).toBe("/api/discount/calculate");
    expect("userId" in last().body).toBe(false);
    expect("userTags" in last().body).toBe(false);
  });

  it("grab 只带 activityNo", async () => {
    await seckillApi.grab({ activityNo: "SK2026001" });
    expect(last().body).toEqual({ activityNo: "SK2026001" });
  });

  it("pay 走路径参数、空 body、仍需登录态", async () => {
    useSession().applyTokenPair({ accessToken: "AT-9", refreshToken: "RT-9", uid: 2, expiresInSeconds: 900 });
    await seckillApi.pay("O-77");
    expect(last().url).toBe("/api/seckill/pay/O-77");
    expect(last().method).toBe("POST");
    expect(last().auth).toBe("Bearer AT-9");
  });

  it("场次与分桶余量是游客口", async () => {
    await Promise.all([seckillApi.sessions(), seckillApi.bucketStock("SK1")]);
    expect(seen.map((s) => s.url)).toEqual(["/api/seckill/activities", "/api/seckill/stock/SK1"]);
    expect(seen.every((s) => s.auth === undefined)).toBe(true);
  });
});

describe("账号 /api/auth", () => {
  it("login/register 的 body 就是 DTO 那几项", async () => {
    await authApi.login({ identifier: "demo", password: "demo123456" });
    expect(last()).toMatchObject({ url: "/api/auth/login", body: { identifier: "demo", password: "demo123456" } });
    await authApi.register({ identifier: "n", password: "p", nickname: "昵称" });
    expect(last().body).toEqual({ identifier: "n", password: "p", nickname: "昵称" });
  });

  it("refresh 由 client 直接发：不带 Authorization（access 正是过期的那枚）", async () => {
    useSession().applyTokenPair({ accessToken: "AT-old", refreshToken: "RT-1", uid: 3, expiresInSeconds: 900 });
    await authApi.refresh("RT-1");
    expect(last()).toMatchObject({ url: "/api/auth/refresh", body: { refreshToken: "RT-1" } });
    expect(last().auth).toBeUndefined();
  });

  it("me / sessions / logout 都带 access token", async () => {
    useSession().applyTokenPair({ accessToken: "AT-m", refreshToken: "RT-m", uid: 4, expiresInSeconds: 900 });
    await Promise.all([authApi.me(), authApi.sessions(), authApi.logout()]);
    expect(seen.map((s) => s.url)).toEqual(["/api/auth/me", "/api/auth/sessions", "/api/auth/logout"]);
    expect(seen.every((s) => s.auth === "Bearer AT-m")).toBe(true);
  });

  it("changePassword 是 PUT /api/auth/password", async () => {
    await authApi.changePassword({ oldPassword: "a", newPassword: "b" });
    expect(last()).toMatchObject({ method: "PUT", url: "/api/auth/password", body: { oldPassword: "a", newPassword: "b" } });
  });
});
