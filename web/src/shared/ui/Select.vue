<script setup lang="ts">
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

const attrs = useAttrs()
const PASS_TO_SELECT = ['aria-label', 'aria-labelledby', 'aria-describedby', 'aria-invalid', 'id', 'name', 'required']
const selAttrs = computed(() =>
  Object.fromEntries(Object.entries(attrs).filter(([k]) => PASS_TO_SELECT.includes(k))))
const wrapAttrs = computed(() =>
  Object.fromEntries(Object.entries(attrs).filter(([k]) => !PASS_TO_SELECT.includes(k))))

const fallback = () => props.modelValue || '（空）'
</script>

<template>
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
  min-height: 38px;
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
  box-shadow: var(--bevel-inset), var(--focus-ring);
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
@media (max-width: 760px) { .inp { min-height: 44px; } }
</style>
