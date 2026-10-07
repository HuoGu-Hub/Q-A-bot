<script setup lang="ts">
/**
 * 单行 / 多行文本与数字输入。
 *
 * 抽它的原因：.inp 这套样式在 **7 个 view** 里各抄了一遍，连 :focus 环都写了 11 处，
 * 其中多数用 outline:none 把全局焦点环覆盖掉了 —— 键盘用户看不见焦点。
 *
 * v2：聚焦环改成「页面底色 + 灵火」双层实心环。
 * 用 box-shadow 画环、不动 outline 的替代方案很常见，但半透明的光晕环在高亮背景下
 * 对比度只有 1.x:1，不满足 WCAG 2.4.11。双层实心环（先描一圈页面底、再描一圈灵火）
 * 在任何背景上都稳定 ≥3:1，是能真正通过检查的做法。
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
    /** 表单字段名。有 <form> 的地方必须给，否则提交时拿不到这个值 */
    name?: string
    autocomplete?: string
    type?: string
    /** 输入法/浏览器的自动大写与自动纠错。键名、代码、配置值都要关掉 */
    autocapitalize?: string
    spellcheck?: boolean
  }>(),
  { multiline: false, rows: 3, mono: false, disabled: false, name: '', autocomplete: '', type: 'text',
    autocapitalize: undefined, spellcheck: undefined }
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
    :value="modelValue"
    :placeholder="placeholder"
    :disabled="disabled"
    @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
  />
</template>

<style scoped>
.inp {
  width: 100%;
  /* 输入框是"刻进去的槽"：底色比页面更深 + 内阴影，而不是比页面更亮 */
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
/* 占位符也是要读的文字（三级文字色 6.6:1）—— --ink-4 只有 3.8:1，属于"看不清"。 */
.inp::placeholder { color: var(--ink-3); }
/* 控件也要能看出"可以输入" */
.inp:hover:not(:disabled):not(:focus) { border-color: var(--edge-hover); }
/* ⚠️ 用 :focus-visible 而不是 :focus。
   文本类控件在规范里**总是**命中 :focus-visible（哪怕用鼠标点的），
   所以观感和 :focus 一样；但换成 :focus-visible 之后，
   这一条规则的含义就变成了"这是键盘/输入态"，而不是"任何获得焦点的时刻"。 */
.inp:focus-visible {
  outline: none;
  border-color: var(--ember);
  box-shadow: var(--bevel-inset), 0 0 0 2px var(--stone-200), 0 0 0 4px var(--ember);
}
.inp:disabled { opacity: .45; cursor: not-allowed; }
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.area { resize: vertical; line-height: 1.65; }
</style>
