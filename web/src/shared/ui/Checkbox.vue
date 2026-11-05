<script setup lang="ts">
withDefaults(defineProps<{
  modelValue: boolean
  disabled?: boolean
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
.cb.on .box { background: var(--ember); border-color: var(--ember); color: var(--ink-on-ember); }
.cb.dis { cursor: not-allowed; opacity: .45; }

@media (prefers-reduced-motion: reduce) {
  .box { transition: none; }
}
</style>
