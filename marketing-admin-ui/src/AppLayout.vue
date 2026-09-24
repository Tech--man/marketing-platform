<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useSession } from '@/stores/session'
import { E } from '@/api/client'

const s = useSession()
const route = useRoute()
const router = useRouter()

/**
 * 导航表。`to` 同时是路由 name 的来源（去掉前导斜杠），因此**只列已注册的路由**：
 * 下面用 `router.hasRoute` 过滤，没做的页面不会露出来，也不需要每加一页回来改这里。
 */
const NAV = [
  { to: '/ops', label: '运维大盘' },
  { to: '/config', label: '在线配置' },
  { to: '/activities', label: '活动' },
  { to: '/coupons', label: '券模板' },
  { to: '/rules', label: '优惠规则' },
  { to: '/seckill', label: '秒杀活动' },
  { to: '/cache', label: '缓存重预热', operate: true },
  { to: '/users', label: '账号' },
  { to: '/sessions', label: '在线会话' },
  { to: '/audits', label: '审计' },
]

const visible = computed(() =>
  NAV.filter(
    (n) =>
      router.hasRoute(n.to.slice(1)) &&
      (n.write ? s.canWrite : n.operate ? s.canOperate : true),
  ),
)

const left = ref(s.secondsLeft())
let ticker = null
onMounted(() => {
  ticker = setInterval(() => {
    left.value = s.secondsLeft()
    // 归零前一分钟提示一次，别等表单填了一半才被 40101 打断
    if (s.authed && left.value === 0) s.clear()
  }, 1000)
})
onUnmounted(() => clearInterval(ticker))

async function signOut() {
  try {
    await s.logout()
  } catch (e) {
    if (e.code !== E.REVOKED) throw e
  }
  router.replace({ name: 'login' })
}
</script>

<template>
  <div class="shell">
    <aside>
      <RouterLink class="brand" to="/">营销平台后台</RouterLink>
      <nav>
        <RouterLink
          v-for="n in visible"
          :key="n.to"
          :to="n.to"
          :class="{ on: route.path === n.to }"
          >{{ n.label }}</RouterLink
        >
      </nav>
      <footer>
        <!-- 页脚的角色与"还剩多久"是运营唯一能看到自己是不是只读的地方。
             灰化入口只是省事，判定在后端。 -->
        <p data-testid="role">{{ s.me?.role || '未登录' }} · 剩余 {{ left }}s</p>
        <button data-act="logout" @click="signOut">退出</button>
      </footer>
    </aside>
    <section class="content">
      <RouterView />
    </section>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  min-height: 100vh;
}
aside {
  width: 12rem;
  padding: 1rem;
  border-right: 1px solid #dcdfe6;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
aside nav {
  display: grid;
  gap: 0.25rem;
}
aside a {
  text-decoration: none;
  color: #303133;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
}
aside a.on {
  background: #ecf5ff;
  color: #409eff;
}
footer {
  margin-top: auto;
  font-size: 12px;
  color: #606266;
}
.content {
  flex: 1;
  padding: 1.25rem;
  overflow-x: auto;
}
</style>
