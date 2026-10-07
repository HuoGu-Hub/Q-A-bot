<script setup lang="ts">
/**
 * 下拉选择。
 *
 * ⚠️ 修一个静默 bug 的场景：原来设置页用 key.includes(...) 硬编码选项，
 * 白名单里 app.llm.reply-style.format 也是 enum 却没有对应分支，
 * 结果渲染成**空下拉**，用户一选就把配置写成空值 —— 页面还不报错。
 *
 * 所以这里强制：要么给 options，要么给 fallbackLabel；
 * 两者都没有时，把当前值本身作为唯一选项显示出来，至少不会写空。
 */
import { computed, useAttrs } from 'vue'

defineOptions({ inheritAttrs: false })

const props = withDefaults(
  defineProps<{
    modelValue: string
    options?: Array<{ value: string; label: string }>
    placeholder?: string
    disabled?: boolean
  }>(),
  { disabled: false }
)
defineEmits<{ 'update:modelValue': [string] }>()

/**
 * 无障碍属性必须落在 **<select>** 上，而不是外面那层 <div>。
 *
 * <p>组件根元素是一个用来放自绘下箭头的包装 div，Vue 的透传属性默认只会贴到它上面，
 * 于是 aria-label 挂在 div 上、select 自己仍然是"没有名字的下拉框"——
 * 读屏念出来就是一句光秃秃的"组合框"。这里把 aria-*、id、name 单独挑出来
 * 转交给 select，其余（class、事件等）照旧留在包装层。
 */
const attrs = useAttrs()
const PASS_TO_SELECT = ['aria-label', 'aria-labelledby', 'aria-describedby', 'aria-invalid', 'id', 'name', 'required']
const selAttrs = computed(() =>
  Object.fromEntries(Object.entries(attrs).filter(([k]) => PASS_TO_SELECT.includes(k))))
const wrapAttrs = computed(() =>
  Object.fromEntries(Object.entries(attrs).filter(([k]) => !PASS_TO_SELECT.includes(k))))

/** 没有预置选项时的兜底：当前值即唯一选项 */
const fallback = () => props.modelValue || '（空）'
</script>

<template>
  <!-- 右侧留出自绘的下箭头位置：appearance:none 之后系统箭头就没了，
       上一版直接把它去掉、也没补一个，下拉看起来和文本框一模一样 -->
  <div class="sel" v-bind="wrapAttrs">
    <select
      v-bind="selAttrs"
      class="inp"
      :value="modelValue"
      :disabled="disabled"
      @change="$emit('update:modelValue', ($event.target as HTMLSelectElement).value)"
    >
      <template v-if="options && options.length">
        <option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option>
      </template>
      <option v-else :value="modelValue">{{ fallback() }}</option>
    </select>
    <svg class="caret" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
         stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d="M6 9.5 12 15.5 18 9.5" />
    </svg>
  </div>
</template>

<style scoped>
.sel { position: relative; display: block; width: 100%; }
.inp {
  width: 100%;
  appearance: none;
  -webkit-appearance: none;
  background: var(--stone-void);
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  padding: 8px 34px 8px 12px;
  font-size: var(--fs-base);
  color: var(--ink);
  cursor: pointer;
  box-shadow: var(--bevel-inset);
  transition: border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
}
.inp:hover:not(:disabled):not(:focus) { border-color: var(--edge-hover); }
.inp:focus-visible {
  outline: none;
  border-color: var(--ember);
  box-shadow: var(--bevel-inset), 0 0 0 2px var(--stone-200), 0 0 0 4px var(--ember);
}
.inp:disabled { opacity: .45; cursor: not-allowed; }
.inp option { background: var(--stone-400); color: var(--ink); }
.caret {
  position: absolute;
  right: 11px;
  top: 50%;
  width: 15px;
  height: 15px;
  margin-top: -7.5px;
  color: var(--ink-3);
  pointer-events: none;
}
</style>
