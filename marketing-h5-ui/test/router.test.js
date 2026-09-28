import { describe, it, expect, beforeEach } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import router from "@/router";
import { safeRedirect, toLogin } from "@/utils/auth";
import { useSession } from "@/stores/session";

/**
 * 守卫判定用真实的那份路由（含真实 meta 表）——复制一份 meta 来测自己写的判据，
 * 恰好测不到"线上那条路由到底要不要登录"这件事。
 * 视图都是懒的：解析只 import 模块，不挂载组件，不会真发请求。
 */

beforeEach(async () => {
  setActivePinia(createPinia());
  localStorage.clear();
  window.scrollTo = () => {}; // jsdom 没实现滚动，路由的 scrollBehavior 会往 stderr 刷噪音
  await router.replace("/home").catch(() => {});
});

function loggedIn() {
  useSession().applyTokenPair({
    accessToken: "AT",
    refreshToken: "RT",
    uid: 42,
    identifier: "chen",
    expiresInSeconds: 900,
  });
}

describe("游客可逛的目录不设门槛", () => {
  it("首页 / 领券中心 / 秒杀列表与详情 / 活动详情都直接放行", async () => {
    for (const p of ["/home", "/coupons", "/seckill", "/seckill/SK1", "/activity/ACT1"]) {
      await router.push(p);
      expect(router.currentRoute.value.path).toBe(p);
    }
  });
});

describe("要登录的页面", () => {
  it.each(["/cart", "/checkout", "/wallet", "/me", "/sessions"])("未登录 %s 落到登录页并带来路", async (p) => {
    await router.push(p);
    expect(router.currentRoute.value.name).toBe("login");
    expect(router.currentRoute.value.query.redirect).toBe(p);
  });

  it("登录后同一跳直接过去，不再拦", async () => {
    loggedIn();
    await router.push("/wallet");
    expect(router.currentRoute.value.name).toBe("wallet");
    await router.push("/me");
    expect(router.currentRoute.value.name).toBe("me");
  });

  it("每一条路由都能解析出组件（SFC 编译不过应该在这里炸，而不是在用户的白屏里）", async () => {
    loggedIn();
    for (const p of [
      "/home", "/coupons", "/seckill", "/seckill/SK1", "/activity/ACT1",
      "/cart", "/checkout", "/wallet", "/me", "/sessions",
    ]) {
      await router.push(p);
      expect(router.currentRoute.value.path).toBe(p);
    }
  });

  it("登录成功后按 redirect 回到原页", async () => {
    await router.push("/cart");
    const redirect = router.currentRoute.value.query.redirect;
    setActivePinia(createPinia());
    loggedIn();
    await router.push(safeRedirect(redirect) || "/home");
    expect(router.currentRoute.value.name).toBe("cart");
  });

  it("深链自带的 query 原样保留在来路里", async () => {
    await router.push("/seckill/SK9"); // 游客可逛，不该被拦
    await router.push("/wallet?from=deep");
    expect(router.currentRoute.value.name).toBe("login");
    expect(router.currentRoute.value.query.redirect).toBe("/wallet?from=deep");
  });

  it("登录/注册页对游客可达且能编译", async () => {
    for (const p of ["/login", "/register"]) {
      await router.push(p);
      expect(router.currentRoute.value.path).toBe(p);
    }
  });

  it("已登录再访问登录/注册页直接回首页", async () => {
    loggedIn();
    await router.push("/login");
    expect(router.currentRoute.value.name).toBe("home");
    await router.push("/register");
    expect(router.currentRoute.value.name).toBe("home");
  });
});

describe("redirect 参数按输入对待", () => {
  it("站内路径放行", () => {
    expect(safeRedirect("/wallet")).toBe("/wallet");
    expect(safeRedirect("/seckill/SK1?x=1")).toBe("/seckill/SK1?x=1");
  });
  it("协议相对与外站一律拒绝", () => {
    expect(safeRedirect("//evil.example/x")).toBeNull();
    expect(safeRedirect("https://evil.example")).toBeNull();
    expect(safeRedirect("/\\evil.example")).toBeNull();
    expect(safeRedirect("cart")).toBeNull();
    expect(safeRedirect(undefined)).toBeNull();
  });
  it("没有合法来路时 toLogin 不带 query", () => {
    expect(toLogin("//evil.example")).toEqual({ name: "login", query: {} });
    expect(toLogin("/me")).toEqual({ name: "login", query: { redirect: "/me" } });
  });
});
