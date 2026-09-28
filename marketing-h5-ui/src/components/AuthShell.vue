<script setup>
import Icon from "./Icon.vue";

/**
 * 登录/注册共用的骨架。桌面（≥1024px）是真正的两栏：左栏是用来说服人的编辑型
 * 陈述（标题 + 三条价值点 + 凭证说明），右栏才是表单——不是把手机页拉宽。
 * <1024px 收起左栏的铺陈，只留一句主张，把竖向空间让给表单。
 */
defineProps({
  eyebrow: { type: String, default: "消费者账号" },
  pitch: { type: String, default: "" },
  headline: { type: String, default: "" },
  points: { type: Array, default: () => [] },
  title: { type: String, default: "" },
  note: { type: String, default: "" },
});
</script>

<template>
  <div class="auth">
    <section class="auth__pitch">
      <div class="auth__eyebrow">
        <Icon name="sparkles" :size="14" />
        {{ eyebrow }}
      </div>
      <h1 class="auth__h1">{{ headline }}</h1>
      <p v-if="pitch" class="auth__lede">{{ pitch }}</p>

      <ul v-if="points.length" class="auth__points">
        <li v-for="p in points" :key="p.title">
          <span class="auth__ico"><Icon name="check" :size="14" /></span>
          <span class="grow">
            <span class="auth__pt">{{ p.title }}</span>
            <span class="auth__pd">{{ p.desc }}</span>
          </span>
        </li>
      </ul>

      <p v-if="note" class="auth__note tiny muted">{{ note }}</p>
    </section>

    <section class="auth__panel card card--pad">
      <h2 class="auth__title">{{ title }}</h2>
      <slot />
      <slot name="footer" />
    </section>
  </div>
</template>

<style scoped>
.auth {
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
  max-width: 520px;
}
.auth__eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--c-text-muted);
  margin-bottom: var(--space-3);
}
.auth__h1 {
  font-size: var(--fs-xl);
  line-height: 1.12;
  letter-spacing: var(--tracking-display);
}
.auth__lede {
  margin-top: var(--space-3);
  font-size: var(--fs-base);
  color: var(--c-text-muted);
  line-height: 1.6;
  max-width: 34rem;
}
.auth__points {
  display: none;
  list-style: none;
  margin: var(--space-7) 0 0;
  padding: 0;
}
.auth__points li {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  padding: var(--space-3) 0;
}
.auth__points li + li { border-top: 1px solid var(--hairline); }
.auth__ico {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--c-surface-3);
  color: var(--c-text-2);
  flex: none;
  margin-top: 1px;
}
.auth__pt { display: block; font-size: var(--fs-base); font-weight: 600; }
.auth__pd { display: block; font-size: var(--fs-sm); color: var(--c-text-muted); margin-top: 2px; line-height: 1.5; }
.auth__note { display: none; margin-top: var(--space-7); line-height: 1.65; max-width: 36rem; }

.auth__panel { padding: var(--space-5); }
.auth__title { font-size: var(--fs-lg); letter-spacing: var(--tracking-head); margin-bottom: var(--space-5); }

/* 桌面：左陈述右表单。表单列固定宽度，正文列吃满剩余——这才像桌面页。 */
@media (min-width: 1024px) {
  .auth {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 400px;
    gap: var(--space-9);
    align-items: start;
    max-width: 1080px;
    padding-top: var(--space-5);
  }
  .auth__h1 { font-size: var(--fs-3xl); }
  .auth__lede { font-size: var(--fs-md); margin-top: var(--space-4); }
  .auth__points { display: block; }
  .auth__note { display: block; }
  .auth__panel {
    padding: var(--space-6);
    position: sticky;
    top: calc(var(--header-h) + var(--space-5));
  }
  .auth__title { font-size: var(--fs-xl); margin-bottom: var(--space-6); }
}
</style>
