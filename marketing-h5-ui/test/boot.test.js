import { describe, it, expect, beforeAll, beforeEach, afterEach, vi } from "vitest";

/**
 * 从真实启动路径（main.js）验一遍端到端：
 *   本地会话在首次导航前恢复 → 守卫放行 → 请求带凭证出网 →
 *   40101 静默刷新重放 → 40102 被动登出并跳登录页。
 *
 * 两个刻意的取舍：
 * 1) 不复制 main.js 的接线（复制了就测不到它被改坏）——代价是它只能启动一次：
 *    一个 jsdom 文档里挂第二个 Vue 应用，第一个还在异步 patch 已被摘掉的 DOM，
 *    会炸成 "emitsOptions of null"。所以 boot 放 beforeAll，用例之间只推进状态。
 * 2) 视图挂载时自己会发请求，被测流程一律打到一个独立的探针 URL 上，
 *    不去数 /api/coupon/usable 被谁调了几次。
 * "游客深链该落哪" 由 router.test.js 用真实路由表覆盖，不在这里重_boot_一次。
 */

const SESSION_KEY = "mkt.h5.session";
const PROBE = "/api/probe";

let router;
let session;
let api;
let fetches = [];
let queue = [];

function envelope(body, status = 200) {
  return { status, json: async () => body, headers: new Headers() };
}
function ok(data) {
  return envelope({ code: 0, message: "OK", data, success: true });
}
function fail(code, message) {
  return envelope({ code, message, data: null, success: false });
}

beforeAll(async () => {
  localStorage.setItem(
    SESSION_KEY,
    JSON.stringify({
      accessToken: "AT-boot",
      refreshToken: "RT-boot",
      uid: 70001,
      identifier: "demo",
      nickname: "演示消费者",
      status: "ACTIVE",
      expiresAt: Date.now() + 900_000,
    })
  );
  const host = Object.assign(document.createElement("div"), { id: "app" });
  document.body.replaceChildren(host);
  window.location.hash = "#/wallet"; // 深链：未登录时这里会被守卫改写去登录页
  window.scrollTo = () => {}; // jsdom 没实现滚动，vue-router 的 scrollBehavior 只刷 stderr

  await import("@/main.js");
  router = (await import("@/router")).default;
  session = (await import("@/stores/session")).useSession();
  api = (await import("@/api/client")).api;
  await router.isReady();
});

beforeEach(() => {
  fetches = [];
  queue = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url, opts = {}) => {
      fetches.push({ url, headers: opts.headers || {}, method: opts.method || "GET" });
      return queue.shift() || ok(null);
    })
  );
});

afterEach(() => vi.unstubAllGlobals());

describe("启动：本地会话先恢复，守卫才判导航", () => {
  it("#/wallet 深链直达卡包，没有绕道登录页", () => {
    expect(router.currentRoute.value.name).toBe("wallet");
    expect(session.isLoggedIn).toBe(true);
    expect(session.display).toBe("演示消费者");
  });

  it("壳子渲染出来了，侧栏脚部用的是真实账号名（不再是手填 UID）", () => {
    expect(document.querySelector(".app-shell")).toBeTruthy();
    expect(document.querySelector(".side__foot").textContent).toContain("演示消费者");
    expect(document.body.textContent).not.toContain("切换演示身份");
  });

  it("每个请求都带着恢复出来的 access token 出网", async () => {
    await api.get(PROBE);
    expect(fetches[0].url).toBe(PROBE);
    expect(fetches[0].headers.Authorization).toBe("Bearer AT-boot");
  });
});

describe("启动后的凭证生命周期", () => {
  it("40101：刷一次、换新凭证、原请求静默重放——停在原页，不去登录页", async () => {
    queue = [
      fail(40101, "登录已过期，请重新登录"),
      ok({ accessToken: "AT-new", refreshToken: "RT-2", expiresInSeconds: 900, uid: 70001 }),
      ok({ pong: 1 }),
    ];
    const data = await api.get(PROBE);

    expect(data).toEqual({ pong: 1 });
    expect(fetches.map((f) => f.url)).toEqual([PROBE, "/api/auth/refresh", PROBE]);
    expect(fetches[1].headers.Authorization).toBeUndefined(); // 刷新一律用 body 里的 refreshToken
    expect(fetches[2].headers.Authorization).toBe("Bearer AT-new");
    expect(session.accessToken).toBe("AT-new");
    expect(JSON.parse(localStorage.getItem(SESSION_KEY)).accessToken).toBe("AT-new");
    await new Promise((r) => setTimeout(r, 20)); // 让任何（不该发生的）跳转排干之后再判"还在原页"
    expect(router.currentRoute.value.name).toBe("wallet");
  });

  it("40102：会话已死，不刷新、清本地、跳登录页并保留来路", async () => {
    queue = [fail(40102, "会话已失效，请重新登录")];
    await expect(api.get(PROBE)).rejects.toMatchObject({ code: 40102 });

    expect(fetches.map((f) => f.url)).toEqual([PROBE]); // 一次刷新都不该有
    expect(session.isLoggedIn).toBe(false);
    expect(localStorage.getItem(SESSION_KEY)).toBeNull();
    // 跳登录页要解析懒加载的 LoginView，是真实的异步，不是几个微任务能等到的
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe("login"));
    expect(router.currentRoute.value.query.redirect).toBe("/wallet");
  });
});
