<script setup lang="ts">
/**
 * 单行 / 多行文本与数字输入。
 *
 * 抽它的原因：`.inp` 这套样式在 **7 个 view** 里各抄了一遍，
 * 连 `:focus` 环都写了 11 处，其中多数用 `outline:none` 把全局焦点环
 * 覆盖掉了 —— 键盘用户看不见焦点。
 *
 * 这里统一：hover 提亮边缘、focus 用火焰边缘 + 光晕（不吞掉无障碍焦点）。
 */
withDefaults(
  defineProps<{
    modelValue: string
    /** 多行（自动用 textarea） */
    multiline?: boolean
    rows?: number
    /** 等宽字体（数字、配置键、代码） */
    mono?: boolean
    placeholder?: string
    disabled?: boolean
  }>(),
  { multiline: false, rows: 3, mono: false, disabled: false }
)
defineEmits<{ 'update:modelValue': [string] }>()
</script>

<template>
  <textarea
    v-if="multiline"
    class="inp area"
    :class="{ mono }"
    :rows="rows"
    :value="modelValue"
    :placeholder="placeholder"
    :disabled="disabled"
    @input="$emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
  />
  <input
    v-else
    class="inp"
    :class="{ mono }"
    :value="modelValue"
    :placeholder="placeholder"
    :disabled="disabled"
    @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
  />
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
  transition: border-color var(--dur-fast) var(--ease),
              background var(--dur-fast) var(--ease),
              box-shadow var(--dur-fast) var(--ease);
}
.inp::placeholder { color: var(--ink-faint); }
/* 控件也要能看出"可以输入" */
.inp:hover:not(:focus):not(:disabled) { border-color: var(--edge-hover); background: var(--bg-abyss); }
.inp:focus { outline: none; border-color: var(--flame); box-shadow: 0 0 0 3px var(--flame-glow); }
.inp:disabled { opacity: .5; cursor: not-allowed; }
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.area { resize: vertical; line-height: 1.6; }
</style>
