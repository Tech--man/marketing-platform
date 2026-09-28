import { createRouter, createWebHashHistory } from "vue-router";
import { useSession } from "@/stores/session";
import { toLogin } from "@/utils/auth";

/**
 * 路由用 hash 模式：产物挂在网关的 /h5/** 静态目录下，hash 让"资源前缀"和"路由前缀"
 * 解耦——/h5/#/cart 永远命中同一份 index.html，深链刷新不依赖服务端回退。
 *
 * meta.title 供全局工具条渲染；meta.back 决定要不要出返回按钮——
 * 标题栏由壳子统一持有，视图不再各自画一个 header。
 *
 * meta 两条新的：
 *  - auth=true      需要登录态（对齐网关：这些页面要打的端点全部要求 access token）
 *  - guestOnly=true 已登录就别再停在登录/注册页
 * 游客可逛的目录（首页 / 领券中心 / 秒杀列表与详情的看数据部分 / 活动详情）**不设门槛**，
 * 与网关 consumer.permit-paths 的"游客本来就该看到"同一判据。
 */
const routes = [
  { path: "/", redirect: "/home" },
  { path: "/home", name: "home", component: () => import("@/views/HomeView.vue"), meta: { tab: "home", title: "营销中心" } },
  { path: "/coupons", name: "coupons", component: () => import("@/views/CouponCenterView.vue"), meta: { tab: "coupons", title: "领券中心" } },
  { path: "/seckill", name: "seckill", component: () => import("@/views/SeckillListView.vue"), meta: { tab: "seckill", title: "限时秒杀" } },
  { path: "/seckill/:no", name: "seckill-detail", component: () => import("@/views/SeckillDetailView.vue"), meta: { tab: "seckill", title: "抢购", back: true } },
  { path: "/cart", name: "cart", component: () => import("@/views/CartView.vue"), meta: { tab: "cart", title: "购物车", auth: true } },
  { path: "/checkout", name: "checkout", component: () => import("@/views/CheckoutView.vue"), meta: { tab: "cart", title: "确认订单", back: true, auth: true } },
  { path: "/wallet", name: "wallet", component: () => import("@/views/WalletView.vue"), meta: { tab: "wallet", title: "我的卡包", back: true, auth: true } },
  { path: "/activity/:no", name: "activity", component: () => import("@/views/ActivityView.vue"), meta: { tab: "home", title: "活动详情", back: true } },
  { path: "/me", name: "me", component: () => import("@/views/MeView.vue"), meta: { tab: "me", title: "账户", auth: true } },
  { path: "/sessions", name: "sessions", component: () => import("@/views/SessionsView.vue"), meta: { tab: "me", title: "登录设备", back: true, auth: true } },
  { path: "/login", name: "login", component: () => import("@/views/LoginView.vue"), meta: { title: "登录", guestOnly: true } },
  { path: "/register", name: "register", component: () => import("@/views/RegisterView.vue"), meta: { title: "创建账号", guestOnly: true } },
  { path: "/:pathMatch(.*)*", redirect: "/home" },
];

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
});

/**
 * 守卫只有一条判据：这条路由要登录吗？（meta.auth）要而本地没有会话，就把来路
 * 交给登录页，成功后回到这一页。目录类页面（首页/领券中心/秒杀/活动详情）不设门槛，
 * 与网关 consumer.permit-paths 的"游客本来就该看到"同一判据；页面里那些"要身份的动作"
 * 由视图自己拿 toLogin 兜（见 CouponCenterView / SeckillDetailView / ActivityView）。
 */
router.beforeEach((to) => {
  const session = useSession();
  if (to.meta?.auth && !session.isLoggedIn) return toLogin(to.fullPath);
  if (to.meta?.guestOnly && session.isLoggedIn) return { name: "home" };
  return true;
});

export default router;
