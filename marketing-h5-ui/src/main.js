import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import router from "./router";
import { initTheme } from "./theme";
import { onSessionCleared } from "./api/client";
import { toLogin } from "./utils/auth";
import { useSession } from "./stores/session";
import "./styles/index.css";

initTheme(); // 先落主题再挂载，避免暗色首屏闪白

const app = createApp(App);
const pinia = createPinia();
app.use(pinia);

// 先把本地会话读回来（并把读写凭证的 bridge 交给 api/client），再装路由——
// vue-router 的 install 会当场发起首次导航，深链 #/wallet 若在这之前被守卫看到，
// 本地凭证还没成形，会被误判成"未登录"踢去登录页。
useSession();

app.use(router);

/**
 * 凭证被判死（40100 / 40102 / 刷新失败）时，被动登出也要有去处：
 * 停在原页只会让下一次请求再撞一次墙。守卫不做这件事——它是纯函数，
 * 而"会话什么时候死的"只有发请求的 client 知道。
 */
let bouncing = false;
onSessionCleared(() => {
  const current = router.currentRoute.value;
  if (bouncing || current.meta?.guestOnly) return;
  bouncing = true;
  const target = current.name === "home" ? { name: "login" } : toLogin(current.fullPath);
  router.replace(target).finally(() => {
    bouncing = false;
  });
});

app.mount("#app");
