<script setup lang="ts">
/**
 * 按钮。
 *
 * 三档变体：primary 只给「这一屏最该做的事」，ghost 是默认，danger 只给删除。
 * 主按钮不再是「橙色渐变方块」—— 渐变在日间主题会变成一块脏棕色。
 * 现在用实色 + 上缘一道高光 + 外圈一圈热光表达「被点亮的石头」，
 * 两套主题下都是同一个物理逻辑。
 *
 * <p>给 <a> 留了出口：传 to 就渲染成 RouterLink，不要再用 RouterLink 套 Button。
 */
import { RouterLink } from 'vue-router'

withDefaults(defineProps<{
  variant?: 'primary' | 'ghost' | 'danger'
  size?: 'sm' | 'md'
  disabled?: boolean
  type?: 'button' | 'submit'
  /** 给了就渲染成路由链接 */
  to?: string | null
  /** 满宽：移动端底部动作条、表单主按钮 */
  block?: boolean
}>(), { variant: 'ghost', size: 'md', disabled: false, type: 'button', to: null, block: false })
</script>

<template>
  <component
    :is="to ? RouterLink : 'button'"
    :to="to || undefined"
    :type="to ? undefined : type"
    :disabled="to ? undefined : disabled"
    :aria-disabled="to && disabled ? 'true' : undefined"
    class="btn"
    :class="['v-' + variant, 's-' + size, { 'is-link': !!to, 'is-disabled': disabled, block }]"
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
.block { width: 100%; }
.is-link { text-decoration: none; }
.is-link:hover { text-decoration: none; }

.btn:hover:not(:disabled):not(.is-disabled) {
  background: var(--stone-500);
  border-color: var(--edge-hover);
  color: var(--ink);
}
/* 按下：往回压一点，给「确实点到了」的触感 */
.btn:active:not(:disabled):not(.is-disabled) {
  transform: translateY(1px);
  background: var(--stone-300);
  box-shadow: var(--bevel-inset);
}
.btn:disabled, .btn.is-disabled { opacity: .42; cursor: not-allowed; }

/* 主按钮：实色灵火 + 上缘高光 + 一圈热光。
   ⚠️ 不用 linear-gradient —— 日间主题的 ember 是深赭，渐变会糊成一块脏棕色。 */
.v-primary {
  background: var(--ember);
  border-color: transparent;
  color: var(--ink-on-ember);
  font-weight: var(--fw-semi);
  box-shadow: inset 0 1px 0 color-mix(in srgb, var(--ink-on-ember) 22%, transparent),
              var(--shadow-flame);
}
.v-primary:hover:not(:disabled):not(.is-disabled) {
  background: var(--ember-hot);
  border-color: transparent;
  color: var(--ink-on-ember);
  box-shadow: inset 0 1px 0 color-mix(in srgb, var(--ink-on-ember) 26%, transparent),
              0 0 0 1px color-mix(in srgb, var(--ember) 45%, transparent),
              0 8px 22px -8px var(--ember-glow);
}
.v-primary:active:not(:disabled):not(.is-disabled) {
  background: var(--ember-deep);
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, .28);
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
  /* 小号按钮在手机上更小一点，但高度仍守着 36：
     吸顶栏里四颗按钮要挤进 366px，字号 15 会多出半颗的宽度 */
  .s-sm { min-height: 36px; padding: 6px 10px; font-size: var(--fs-sm); }
}
</style>