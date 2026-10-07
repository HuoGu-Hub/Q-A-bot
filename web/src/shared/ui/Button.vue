<script setup lang="ts">
/**
 * 按钮。
 *
 * v2 改了三处：
 *  1. **圆角从 3px 提到 6px**，和输入框、下拉统一（上一版搜索框 6px 而紧挨着的按钮 3px，
 *     并排的圆角不一致是最容易被眼睛抓到、又最难说清"哪里别扭"的那种低级）。
 *  2. **给 <a> 留了出口**。上一版在 5 个地方写成 <RouterLink><Button/></RouterLink>，
 *     渲染出来是 <a><button> —— 这是无效 HTML，读屏会念两遍，也会被 Web Interface
 *     Guidelines 直接判为问题。现在传 to 就渲染成 RouterLink，样式一模一样。
 *  3. 交互反馈依旧必须同时动【背景 + 边缘 + 位移】：深色底上只改边框色，
 *     用户根本看不出能不能点（这是实测反馈过的）。
 */
import { RouterLink } from 'vue-router'

withDefaults(defineProps<{
  variant?: 'primary' | 'ghost' | 'danger'
  size?: 'sm' | 'md'
  disabled?: boolean
  type?: 'button' | 'submit'
  /** 给了就渲染成路由链接 —— 别再用 <RouterLink> 套 <Button> */
  to?: string | null
}>(), { variant: 'ghost', size: 'md', disabled: false, type: 'button', to: null })
</script>

<template>
  <component
    :is="to ? RouterLink : 'button'"
    :to="to || undefined"
    :type="to ? undefined : type"
    :disabled="to ? undefined : disabled"
    :aria-disabled="to && disabled ? 'true' : undefined"
    class="btn"
    :class="[`v-${variant}`, `s-${size}`, { 'is-link': !!to, 'is-disabled': disabled }]"
  >
    <slot />
  </component>
</template>

<style scoped>
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--sp-2);
  font-family: var(--font-body);
  border-radius: var(--r-sm);
  border: 1px solid var(--edge);
  background: var(--stone-400);
  color: var(--ink-2);
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
  box-shadow: var(--bevel-shelf);
  transition: background var(--dur-fast) var(--ease),
              border-color var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease),
              box-shadow var(--dur-fast) var(--ease),
              transform var(--dur-fast) var(--ease);
}
.s-md { padding: 8px 16px; min-height: 38px; font-size: var(--fs-base); }
.s-sm { padding: 5px 12px; min-height: 30px; font-size: var(--fs-sm); }
.is-link { text-decoration: none; }
.is-link:hover { text-decoration: none; }

.btn:hover:not(:disabled):not(.is-disabled) {
  background: var(--stone-500);
  border-color: var(--edge-hover);
  color: var(--ink);
}
/* 按下：往回压一点，给"确实点到了"的触感 */
.btn:active:not(:disabled):not(.is-disabled) {
  transform: translateY(1px);
  background: var(--stone-300);
  box-shadow: var(--bevel-inset);
}
.btn:disabled, .btn.is-disabled { opacity: .42; cursor: not-allowed; }

.v-primary {
  background: linear-gradient(180deg, var(--ember), var(--ember-deep));
  border-color: transparent;
  color: var(--ink-on-ember);
  font-weight: var(--fw-semi);
  box-shadow: var(--bevel-shelf), var(--shadow-flame);
}
.v-primary:hover:not(:disabled):not(.is-disabled) {
  background: linear-gradient(180deg, var(--ember-hot), var(--ember));
  border-color: transparent;
  color: var(--ink-on-ember);
  box-shadow: var(--bevel-shelf), 0 0 0 1px rgba(255, 208, 138, .35), 0 8px 24px -6px var(--ember-glow);
}
.v-primary:active:not(:disabled):not(.is-disabled) {
  background: linear-gradient(180deg, var(--ember), var(--ember-deep));
  box-shadow: var(--bevel-inset);
}

.v-danger { border-color: var(--edge); }
.v-danger:hover:not(:disabled):not(.is-disabled) {
  border-color: var(--blight);
  color: var(--blight-lift);
  background: var(--blight-veil);
}

@media (max-width: 760px) {
  /* 拇指点得中的下限 */
  .s-md { min-height: 44px; padding: 10px 18px; }
  .s-sm { min-height: 38px; padding: 7px 14px; font-size: var(--fs-base); }
}
</style>
