<script setup lang="ts">
import { ref } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    disabled?: boolean
    ariaLabel?: string
  }>(),
  { disabled: false, ariaLabel: '开关' }
)
defineEmits<{ 'update:modelValue': [boolean] }>()

const el = ref<HTMLInputElement | null>(null)

function onWrapClick(e: MouseEvent) {
  if ((e.target as HTMLElement)?.tagName === 'INPUT') return
  e.preventDefault()
  if (props.disabled) return
  el.value?.click()
}
</script>

<template>
  <span class="switch" :class="{ disabled }" @click.stop="onWrapClick">
    <input
      ref="el"
      type="checkbox"
      :checked="modelValue"
      :disabled="disabled"
      :aria-label="ariaLabel"
      @change="$emit('update:modelValue', ($event.target as HTMLInputElement).checked)"
    />
    <span class="slider" />
  </span>
</template>

<style scoped>
.switch {
  position: relative;
  display: inline-block;
  width: 46px;
  height: 26px;
  cursor: pointer;
  padding: 9px 0;
  margin: -9px 0;
  box-sizing: content-box;
}
.switch.disabled { cursor: not-allowed; opacity: .45; }
.switch input { opacity: 0; width: 0; height: 0; }

.slider {
  position: absolute;
  left: 0; right: 0;
  top: 50%;
  transform: translateY(-50%);
  height: 26px;
  background: var(--stone-void);
  border: 1px solid var(--edge);
  border-radius: var(--r-pill);
  box-shadow: var(--bevel-inset);
  transition: background var(--dur) var(--ease), border-color var(--dur) var(--ease),
              box-shadow var(--dur) var(--ease);
}
.slider::before {
  content: "";
  position: absolute;
  height: 18px; width: 18px; left: 3px; top: 3px;
  background: var(--ink-4);
  border-radius: 50%;
  transition: transform var(--dur) var(--ease), background var(--dur) var(--ease);
}

.switch:hover input:not(:disabled) + .slider { border-color: var(--edge-hover); }
.switch input:checked + .slider {
  background: var(--ember-veil);
  border-color: var(--ember);
  box-shadow: var(--bevel-inset), 0 0 12px -2px var(--ember-glow);
}
.switch input:checked + .slider::before { transform: translateX(20px); background: var(--ember); }
.switch:hover input:checked:not(:disabled) + .slider { background: var(--ember-glow); }
.switch input:focus-visible + .slider { outline: 2px solid var(--ember); outline-offset: 3px; }
</style>
