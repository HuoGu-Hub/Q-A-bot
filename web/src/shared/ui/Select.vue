<script setup lang="ts">
/**
 * 下拉选择。
 *
 * ⚠️ 修一个静默 bug 的场景：原来设置页用 `key.includes(...)` 硬编码选项，
 * 白名单里 `app.llm.reply-style.format` 也是 enum 却没有对应分支，
 * 结果渲染成**空下拉**，用户一选就把配置写成空值 —— 页面还不报错。
 *
 * 所以这里强制：要么给 options，要么给 fallbackLabel；
 * 两者都没有时，把当前值本身作为唯一选项显示出来，至少不会写空。
 */
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

/** 没有预置选项时的兜底：当前值即唯一选项 */
const fallback = () => props.modelValue || '（空）'
</script>

<template>
  <select
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
</template>

<style scoped>
.inp {
  width: 100%;
  background: var(--surface-inset);
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  padding: 7px 11px;
  font-size: var(--fs-sm);
  color: var(--ink);
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease),
              background var(--dur-fast) var(--ease),
              box-shadow var(--dur-fast) var(--ease);
}
.inp:hover:not(:disabled) { border-color: var(--edge-hover); background: var(--bg-abyss); }
.inp:focus { outline: none; border-color: var(--flame); box-shadow: 0 0 0 3px var(--flame-glow); }
.inp:disabled { opacity: .5; cursor: not-allowed; }
</style>
