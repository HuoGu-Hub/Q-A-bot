<script setup lang="ts">
withDefaults(
  defineProps<{
    modelValue: string
    multiline?: boolean
    rows?: number
    mono?: boolean
    placeholder?: string
    disabled?: boolean
    name?: string
    autocomplete?: string
    type?: string
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
.inp::placeholder { color: var(--ink-3); }
.inp:hover:not(:disabled):not(:focus) { border-color: var(--edge-hover); }
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
