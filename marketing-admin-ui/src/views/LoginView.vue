<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useSession } from '@/stores/session'
import { E } from '@/api/client'
import { ref } from 'vue'

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
    router.replace(route.query.next || '/')
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
    <h1>营销平台后台</h1>
    <form data-testid="login-form" @submit.prevent="submit">
      <input v-model="username" data-field="username" autocomplete="username" placeholder="账号" />
      <input
        v-model="password"
        data-field="password"
        type="password"
        autocomplete="current-password"
        placeholder="口令"
      />
      <button data-act="submit" :disabled="busy || cooldown > 0" type="submit">{{ hint }}</button>
    </form>
    <!-- 种子口令在 README 公示（dev 环境），这里不额外显示，免得生产上被人当默认密码 -->
  </main>
</template>

<style scoped>
.login {
  max-width: 22rem;
  margin: 12vh auto;
  display: grid;
  gap: 0.75rem;
}
.login input,
.login button {
  padding: 0.5rem 0.75rem;
  font: inherit;
}
</style>
