<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Odometer } from '@element-plus/icons-vue'
import { useSession } from '@/stores/session'
import { E } from '@/api/client'
import Button from '@/components/Button.vue'
import Field from '@/components/Field.vue'

const s = useSession()
const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)
const cooldown = ref(0)

async function submit() {
  busy.value = true
  try {
    await s.login(username.value, password.value)
    await s.whoAmI()
    // W4（2026-09-30 第二轮复审）：next 是用户可感的输入，按输入对待——只接受站内
    // 绝对路径（`//evil.example` 以 / 开头但浏览器当协议相对地址跳外站）。与 h5 的
    // safeRedirect 同一判据，两套前端对同类输入不再双标。
    const next = route.query.next
    const safe = typeof next === 'string' && next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/\\')
    router.replace(safe ? next : '/')
  } catch (e) {
    if (e.code === E.THROTTLED) {
      // LoginGuard 每 IP 10 次/分钟且**含成功尝试**。不显示还要等多久，
      // 运营只会以为"口令错了/后台坏了"，然后继续点，把窗口一直续上。
      cooldown.value = e.retryAfterSeconds || 60
      startCooldown()
    } else {
      // 40100 的文案在后端已经把"账号不存在"与"口令错"合成同一句，这里不猜原因
      ElMessage.error(e.message || '登录失败')
    }
  } finally {
    busy.value = false
  }
}

let timer = null
function startCooldown() {
  clearInterval(timer)
  timer = setInterval(() => {
    cooldown.value -= 1
    if (cooldown.value <= 0) clearInterval(timer)
  }, 1000)
}

const hint = computed(() => (cooldown.value > 0 ? `请等待 ${cooldown.value}s` : '登录'))
</script>

<template>
  <main class="login">
    <div class="login__card">
      <div class="login__brand">
        <span class="login__mark"><el-icon><Odometer /></el-icon></span>
        <div>
          <h1>营销平台后台</h1>
          <p class="login__sub">运营与管理控制台</p>
        </div>
      </div>
      <form data-testid="login-form" class="stack-sm" @submit.prevent="submit">
        <Field label="账号">
          <input
            v-model="username"
            class="input"
            data-field="username"
            autocomplete="username"
            placeholder="请输入账号"
          />
        </Field>
        <Field label="口令">
          <input
            v-model="password"
            class="input"
            data-field="password"
            type="password"
            autocomplete="current-password"
            placeholder="请输入口令"
          />
        </Field>
        <Button
          type="submit"
          variant="primary"
          block
          data-act="submit"
          :disabled="busy || cooldown > 0"
        >
          {{ hint }}
        </Button>
      </form>
      <!-- 种子口令在 README 公示（dev 环境），这里不额外显示，免得生产上被人当默认密码 -->
    </div>
  </main>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: var(--space-4);
  background:
    radial-gradient(1100px 520px at 50% -10%, var(--c-accent-soft), transparent 60%),
    var(--c-canvas);
}
.login__card {
  width: 100%;
  max-width: 22rem;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-2);
  padding: var(--space-6);
}
.login__brand {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-5);
}
.login__mark {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  border-radius: var(--radius-md);
  background: var(--c-accent);
  color: var(--c-accent-contrast);
  font-size: 20px;
}
.login__brand h1 {
  font-size: var(--fs-lg);
}
.login__sub {
  font-size: var(--fs-sm);
  color: var(--c-text-muted);
  margin-top: 2px;
}
</style>
