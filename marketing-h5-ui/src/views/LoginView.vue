<script setup>
import { computed, ref } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import MButton from "@/components/MButton.vue";
import Icon from "@/components/Icon.vue";
import AuthShell from "@/components/AuthShell.vue";
import { authApi } from "@/api";
import { ApiError, E, messageFor, noteThrottle } from "@/api/client";
import { safeRedirect } from "@/utils/auth";
import { useSession } from "@/stores/session";
import { useToast } from "@/stores/toast";

const session = useSession();
const toast = useToast();
const route = useRoute();
const router = useRouter();

const identifier = ref("");
const password = ref("");
const reveal = ref(false);
const busy = ref(false);
const error = ref("");

/** 登录后回哪去：只认站内绝对路径，来路是用户可感的输入 */
const target = computed(() => safeRedirect(route.query?.redirect) || "/home");

const points = [
  { title: "券跟着账号走", desc: "卡包、核销记录都在你名下，换设备登录就还在。" },
  { title: "抢购与支付只认本人", desc: "订单归属由登录态判定，别人的单你付不了，你的单别人也付不了。" },
  { title: "凭证自动续期", desc: "短期凭证过期时客户端静默换一枚，不打断你正在做的事。" },
];

const note =
  "登录凭证只存在这台设备的浏览器里（localStorage），每次请求以 Bearer 头随请求发出；" +
  "刷新凭证只能用于换新的短期凭证，不参与业务请求。退出登录会吊销服务端当前会话。";

async function submit() {
  if (busy.value) return;
  error.value = "";
  const id = identifier.value.trim();
  if (!id || !password.value) {
    error.value = "请填写登录名与口令";
    return;
  }
  busy.value = true;
  try {
    const pair = await authApi.login({ identifier: id, password: password.value });
    if (!session.applyTokenPair(pair)) {
      throw new ApiError(E.SYSTEM, "登录响应缺少凭证，请重试", null);
    }
    toast.success(`欢迎回来，${pair.nickname || pair.identifier || id}`);
    router.replace(target.value);
  } catch (e) {
    if (e instanceof ApiError) {
      noteThrottle(e);
      // 40100 在登录这件事上的意思是"账号或口令错"，不是"你该去登录"
      error.value = e.code === E.UNAUTHORIZED ? e.message || "账号或口令不正确" : messageFor(e);
    } else {
      error.value = "登录失败，请稍后重试";
    }
    password.value = "";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <AuthShell
    headline="登录后，优惠才真正属于你"
    pitch="领券、抢购、结算都绑定到你的账号。不登录也能逛活动、看余量——到要动手的那一步再来。"
    title="登录"
    :points="points"
    :note="note"
  >
    <Teleport to="#toolbar-actions">
      <RouterLink to="/register" class="btn btn--quiet btn--sm">创建账号</RouterLink>
    </Teleport>

    <form class="af" novalidate @submit.prevent="submit">
      <div v-if="error" class="banner banner--danger af__err" role="alert">
        <Icon name="alert" :size="16" />
        <span>{{ error }}</span>
      </div>

      <div class="field">
        <label class="field__label" for="login-id">登录名</label>
        <input
          id="login-id"
          v-model="identifier"
          class="input"
          type="text"
          autocomplete="username"
          :readonly="busy"
          placeholder="登录名 / 邮箱 / 手机号"
          maxlength="64"
        />
      </div>

      <div class="field af__pw">
        <label class="field__label" for="login-pw">口令</label>
        <div class="af__pwWrap">
          <input
            id="login-pw"
            v-model="password"
            class="input input--pad-r"
            :type="reveal ? 'text' : 'password'"
            autocomplete="current-password"
            :readonly="busy"
            placeholder="至少 8 位"
            maxlength="64"
          />
          <button
            type="button"
            class="af__peek"
            :aria-label="reveal ? '隐藏口令' : '显示口令'"
            :aria-pressed="reveal"
            @click="reveal = !reveal"
          >
            <Icon :name="reveal ? 'eye-off' : 'eye'" :size="17" />
          </button>
        </div>
      </div>

      <MButton type="submit" block size="lg" :loading="busy">
        {{ busy ? "登录中…" : "登录" }}
      </MButton>

      <p class="af__alt tiny muted">
        还没有账号？<RouterLink to="/register">用登录名创建一个</RouterLink>
      </p>
      <RouterLink to="/home" class="af__skip small">先随便逛逛</RouterLink>
    </form>

    <template #footer>
      <div class="af__who">
        <Icon name="lock" :size="14" />
        <span class="grow truncate tiny">这台设备上没有已保存的口令，凭证只在下一次请求里出现</span>
      </div>
    </template>
  </AuthShell>
</template>

<style scoped>
.af { display: flex; flex-direction: column; gap: var(--space-4); }
.af__err { margin-bottom: var(--space-1); }
.af__pwWrap { position: relative; }
.input--pad-r { padding-right: 46px; }
.af__peek {
  position: absolute;
  top: 50%;
  right: 4px;
  transform: translateY(-50%);
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--c-text-muted);
  display: grid;
  place-items: center;
  transition: background-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.af__peek:hover { background: var(--c-surface-3); color: var(--c-text); }
.af__alt { margin-top: var(--space-1); }
.af__skip { display: inline-block; color: var(--c-brand); }
.af__who {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-5);
  padding-top: var(--space-4);
  border-top: 1px solid var(--hairline);
  color: var(--c-text-muted);
}
</style>
