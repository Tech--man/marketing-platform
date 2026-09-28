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

/* 校验口径与后端 RegisterRequest 一致，写在这里是为了"提交前就说得清"，
   不是为了替后端把关——真判 still 在后端，绕过界面一样进不来。 */
const ID_RE = /^[A-Za-z0-9_.@-]{4,64}$/;
const PW_MIN = 8;
const PW_MAX = 64;
const NICK_MAX = 32;

const identifier = ref("");
const nickname = ref("");
const password = ref("");
const confirm = ref("");
const reveal = ref(false);
const busy = ref(false);
const error = ref("");
const touched = ref({});

const target = computed(() => safeRedirect(route.query?.redirect) || "/home");

const idBad = computed(() => !!touched.value.id && !ID_RE.test(identifier.value.trim()));
const pwBad = computed(
  () => !!touched.value.pw && (password.value.length < PW_MIN || password.value.length > PW_MAX)
);
const confirmBad = computed(() => !!touched.value.pw2 && confirm.value !== password.value);
const nickBad = computed(() => nickname.value.trim().length > NICK_MAX);

const strength = computed(() => {
  const p = password.value;
  if (!p) return 0;
  let n = 0;
  if (p.length >= PW_MIN) n++;
  if (p.length >= 12) n++;
  if (/[^A-Za-z0-9]/.test(p)) n++;
  if (/[A-Z]/.test(p) && /[a-z0-9]/.test(p)) n++;
  return Math.min(n, 4);
});
const strengthLabel = ["还没填", "偏弱", "可用", "良好", "很强"][strength.value];

const points = [
  { title: "一个登录名，到处通用", desc: "字母、数字与 _ . @ - 组合，4-64 位，不必是手机号或邮箱。" },
  { title: "口令只以摘要落库", desc: "服务端存 BCrypt 摘要，任何界面都不会把口令回给你。" },
  { title: "多设备各一条会话", desc: "每台设备一份独立凭证，可以在账户页单独登出。" },
];

const note =
  "注册即签发一条会话：短期凭证用于每次请求，刷新凭证只用于换新凭证。" +
  "改口令会让该账号此前的全部会话立即失效——包括别的设备上的。";

function bad() {
  if (!ID_RE.test(identifier.value.trim())) return "登录名需为 4-64 位字母、数字或 _ . @ -";
  if (password.value.length < PW_MIN || password.value.length > PW_MAX) return `口令长度需在 ${PW_MIN}-${PW_MAX} 位之间`;
  if (confirm.value !== password.value) return "两次输入的口令不一致";
  if (nickBad.value) return `昵称不超过 ${NICK_MAX} 字`;
  return "";
}

async function submit() {
  if (busy.value) return;
  touched.value = { id: true, pw: true, pw2: true };
  error.value = "";
  const first = bad();
  if (first) {
    error.value = first;
    return;
  }
  busy.value = true;
  try {
    const pair = await authApi.register({
      identifier: identifier.value.trim(),
      password: password.value,
      nickname: nickname.value.trim() || undefined,
    });
    if (!session.applyTokenPair(pair)) {
      throw new ApiError(E.SYSTEM, "注册响应缺少凭证，请重试", null);
    }
    toast.success("账号已创建，已为你登录");
    router.replace(target.value);
  } catch (e) {
    if (e instanceof ApiError) {
      noteThrottle(e);
      error.value = e.code === E.UNAUTHORIZED ? "注册未完成，请检查填写的内容" : messageFor(e);
    } else {
      error.value = "注册失败，请稍后重试";
    }
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <AuthShell
    eyebrow="消费者账号"
    headline="创建一个账号"
    pitch="注册成功后这台设备就是登录态，直接回到你刚才要做的那一步。"
    title="创建账号"
    :points="points"
    :note="note"
  >
    <Teleport to="#toolbar-actions">
      <RouterLink to="/login" class="btn btn--quiet btn--sm">已有账号，登录</RouterLink>
    </Teleport>

    <form class="af" novalidate @submit.prevent="submit">
      <div v-if="error" class="banner banner--danger af__err" role="alert">
        <Icon name="alert" :size="16" />
        <span>{{ error }}</span>
      </div>

      <div class="field">
        <label class="field__label" for="reg-id">登录名</label>
        <input
          id="reg-id"
          v-model="identifier"
          class="input"
          :class="{ 'input--bad': idBad }"
          type="text"
          autocomplete="username"
          :readonly="busy"
          aria-describedby="reg-id-hint"
          :aria-invalid="idBad || undefined"
          placeholder="如 chen.2026"
          maxlength="64"
          @blur="touched.id = true"
        />
        <p id="reg-id-hint" class="field__hint tiny" :class="{ 'text-danger': idBad }">
          {{ idBad ? "需为 4-64 位字母、数字或 _ . @ -" : "4-64 位，可用字母、数字与 _ . @ -" }}
        </p>
      </div>

      <div class="field">
        <label class="field__label" for="reg-nick">昵称<span class="faint">（可选）</span></label>
        <input
          id="reg-nick"
          v-model="nickname"
          class="input"
          :class="{ 'input--bad': nickBad }"
          type="text"
          autocomplete="nickname"
          :readonly="busy"
          :maxlength="64"
          placeholder="展示在账户与结算页"
        />
        <p v-if="nickBad" class="field__hint tiny text-danger">昵称不超过 {{ NICK_MAX }} 字</p>
      </div>

      <div class="field">
        <label class="field__label" for="reg-pw">口令</label>
        <div class="af__pwWrap">
          <input
            id="reg-pw"
            v-model="password"
            class="input input--pad-r"
            :class="{ 'input--bad': pwBad }"
            :type="reveal ? 'text' : 'password'"
            autocomplete="new-password"
            :readonly="busy"
            aria-describedby="reg-pw-hint"
            :aria-invalid="pwBad || undefined"
            :maxlength="64"
            @blur="touched.pw = true"
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
        <div class="af__meter" role="group" aria-label="口令强度">
          <span class="af__bars" aria-hidden="true">
            <i v-for="i in 4" :key="i" :class="{ 'is-on': strength >= i }" />
          </span>
          <span class="tiny muted">{{ strengthLabel }}</span>
        </div>
        <p id="reg-pw-hint" class="field__hint tiny" :class="{ 'text-danger': pwBad }">
          {{ pwBad ? `口令长度需在 ${PW_MIN}-${PW_MAX} 位之间` : `${PW_MIN}-${PW_MAX} 位；含大小写与符号更稳` }}
        </p>
      </div>

      <div class="field">
        <label class="field__label" for="reg-pw2">确认口令</label>
        <input
          id="reg-pw2"
          v-model="confirm"
          class="input"
          :class="{ 'input--bad': confirmBad }"
          type="password"
          autocomplete="new-password"
          :readonly="busy"
          :aria-invalid="confirmBad || undefined"
          :maxlength="64"
          @blur="touched.pw2 = true"
        />
        <p v-if="confirmBad" class="field__hint tiny text-danger">两次输入的口令不一致</p>
      </div>

      <MButton type="submit" block size="lg" :loading="busy">
        {{ busy ? "创建中…" : "创建账号并登录" }}
      </MButton>

      <p class="af__alt tiny muted">
        已经有账号了？<RouterLink to="/login">直接登录</RouterLink>
      </p>
      <RouterLink to="/home" class="af__skip small">先随便逛逛</RouterLink>
    </form>

    <template #footer>
      <div class="af__who">
        <Icon name="lock" :size="14" />
        <span class="grow truncate tiny">口令只在提交那一刻出发，本机不留副本</span>
      </div>
    </template>
  </AuthShell>
</template>

<style scoped>
.af { display: flex; flex-direction: column; gap: var(--space-4); }
.af__err { margin-bottom: var(--space-1); }
.field__hint { margin-top: 6px; color: var(--c-text-muted); line-height: 1.5; }
.input--bad { border-color: var(--c-danger); }
.input--bad:focus { border-color: var(--c-danger); box-shadow: 0 0 0 4px var(--c-danger-soft); }
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
.af__meter { display: flex; align-items: center; gap: var(--space-3); margin-top: var(--space-2); }
.af__bars { display: inline-flex; gap: 4px; }
.af__bars i {
  width: 26px;
  height: 3px;
  border-radius: var(--radius-pill);
  background: var(--c-surface-3);
  transition: background-color var(--dur-fast) var(--ease);
}
.af__bars i.is-on { background: var(--c-text-2); }
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
