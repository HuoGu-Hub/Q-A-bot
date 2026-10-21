<script setup lang="ts">
/**
 * 复选框 —— 给多选清单用。
 *
 * 为什么不用 Switch：开关表达「这个功能开着还是关着」，多选清单表达
 * 「这一项在这次操作里选没选中」。语义不同，视觉也不该一样。
 */
withDefaults(defineProps<{
  modelValue: boolean
  disabled?: boolean
  /** 无障碍标签；清单里没有可见 label 时必须给 */
  ariaLabel?: string
}>(), { disabled: false, ariaLabel: '选择' })

defineEmits<{ 'update:modelValue': [boolean] }>()
</script>

<template>
  <label class="cb" :class="{ on: modelValue, dis: disabled }">
    <input
      type="checkbox"
      :checked="modelValue"
      :disabled="disabled"
      :aria-label="ariaLabel"
      @change="$emit('update:modelValue', ($event.target as HTMLInputElement).checked)"
    />
    <span class="box" aria-hidden="true">
      <svg viewBox="0 0 12 12" width="10" height="10">
        <path d="M2 6.2 4.6 8.8 10 3.4" fill="none" stroke="currentColor"
              stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </span>
  </label>
</template>

<style scoped>
.cb { display: inline-flex; cursor: pointer; }
/* 原生复选框藏起来但保留语义：键盘、读屏、表单行为一样不少 */
.cb input { position: absolute; width: 1px; height: 1px; opacity: 0; margin: 0; }
.box {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border: 1px solid var(--edge);
  border-radius: var(--r-xs);
  background: var(--stone-void);
  color: transparent;
  box-shadow: var(--bevel-inset);
  transition: background var(--dur-fast) var(--ease),
              border-color var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease);
}
.cb:hover .box { border-color: var(--ember); }
.cb input:focus-visible + .box { outline: 2px solid var(--ember); outline-offset: 2px; }
/* 勾的颜色必须是 --ink-on-ember：日间主题的 --stone-void 是深赭，勾在深棕底上看不见 */
.cb.on .box { background: var(--ember); border-color: var(--ember); color: var(--ink-on-ember); }
.cb.dis { cursor: not-allowed; opacity: .45; }

@media (prefers-reduced-motion: reduce) {
  .box { transition: none; }
}
</style>
