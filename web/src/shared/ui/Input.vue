<script setup lang="ts">
/**
 * 单行 / 多行文本与数字输入。
 *
 * 视觉身份：刻进去的槽 —— 底色比页面更深、内阴影暗在上亮在下。
 * 聚焦环用双层实心环：先描一圈页面底、再描一圈灵火。
 * 半透明的光晕环在亮底上只有 1.x:1，不满足 WCAG 2.4.11，所以不用。
 */
withDefaults(
  defineProps<{
    modelValue: string
    multiline?: boolean
    rows?: number
    /** 等宽字体（数字、配置键、代码） */
    mono?: boolean
    placeholder?: string
    disabled?: boolean
    /** 表单字段名。有 form 的地方必须给，否则提交时拿不到这个值 */
    name?: string
    autocomplete?: string
    type?: string
    /** 输入法/浏览器的自动大写与自动纠错。键名、代码、配置值都要关掉 */
    autocapitalize?: string
    spellcheck?: boolean
    maxlength?: number | string
    inputmode?: 'none' | 'text' | 'decimal' | 'numeric' | 'tel' | 'search' | 'email' | 'url'
  }>(),
  { multiline: false, rows: 3, mono: false, disabled: false, name: '', autocomplete: '',
    type: 'text', autocapitalize: undefined, spellcheck: undefined }
)
defineEmits<{ 'update:modelValue': [string] }>()
</script>

<template>
  <textarea
    v-if="multiline"
    class="inp area"
    :class="{ mono }"
    :rows="rows"
    :name="name || undefined"
    :value="modelValue"
    :placeholder="placeholder"
    :disabled="disabled"
    @input="$emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
  />
  <input
    v-else
    class="inp"
    :class="{ mono }"
    :type="type"
    :name="name || undefined"
    :autocomplete="autocomplete || undefined"
    :autocapitalize="autocapitalize"
    :spellcheck="spellcheck === undefined ? (mono ? false : undefined) : spellcheck"
    :maxlength="maxlength"
    :inputmode="inputmode"
    :value="modelValue"
    :placeholder="placeholder"
    :disabled="disabled"
    @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
  />
</template>

<style scoped>
.inp {
  width: 100%;
  min-height: 38px;
  background: var(--stone-void);
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  padding: 8px 12px;
  font-size: var(--fs-base);
  color: var(--ink);
  box-shadow: var(--bevel-inset);
  transition: border-color var(--dur-fast) var(--ease),
              box-shadow var(--dur-fast) var(--ease);
}
/* 占位符也是要读的文字（三级文字色）—— --ink-4 属于「看不清」 */
.inp::placeholder { color: var(--ink-3); }
.inp:hover:not(:disabled):not(:focus) { border-color: var(--edge-hover); }
/* ⚠️ 用 :focus-visible 而不是 :focus。
   文本控件在规范里总是命中 :focus-visible，观感与 :focus 一致；
   但语义变成「这是键盘/输入态」，而不是「任何获得焦点的时刻」。 */
.inp:focus-visible {
  outline: none;
  border-color: var(--ember);
  box-shadow: var(--bevel-inset), var(--focus-ring);
}
.inp:disabled { opacity: .45; cursor: not-allowed; }
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.area { resize: vertical; line-height: 1.65; min-height: 72px; }

@media (max-width: 760px) {
  .inp { min-height: 44px; }
}
</style>
