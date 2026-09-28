import { authApi } from "@/api";
import { useSession } from "@/stores/session";

/**
 * 登录态相关的纯逻辑：来路参数怎么信、登出怎么做。
 * 放在 utils 而不是 router 里，是为了让视图/守卫共用同一份判据而不互相 import
 * （router 需要 toLogin，视图需要 safeRedirect，谁都不该为了一个函数去拉起路由实例）。
 */

/**
 * 登录后回哪去。只接受站内绝对路径：`//evil.example` 也以 / 开头，
 * 但浏览器会把它当协议相对地址跳到外站——redirect 是用户可感的输入，按输入对待。
 */
export function safeRedirect(fullPath) {
  if (typeof fullPath !== "string") return null;
  if (!fullPath.startsWith("/") || fullPath.startsWith("//") || fullPath.startsWith("/\\")) return null;
  return fullPath;
}

/** 未登录点到需要登录的页面/动作：登录页 + 来路 */
export function toLogin(fullPath) {
  const redirect = safeRedirect(fullPath);
  return { name: "login", query: redirect ? { redirect } : {} };
}

/**
 * 主动登出：先请服务端吊销当前会话（这一步可能因为会话本来就死了而失败），
 * 再无条件清本地——服务端本来就报"没有这条会话"恰恰是我们想要的结果，
 * 不该因为它报错就把凭证留在这台设备上。
 *
 * @returns {Promise<boolean>} true = 服务端确认吊销；false = 只清了本地
 */
export async function signOut() {
  const session = useSession();
  let confirmed = false;
  try {
    await authApi.logout();
    confirmed = true;
  } catch {
    confirmed = false;
  } finally {
    session.clear();
  }
  return confirmed;
}
