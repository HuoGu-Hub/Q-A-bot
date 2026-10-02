<script setup lang="ts">
/**
 * 开关。
 *
 * 抽它的原因：设置页和资料投递页各写了一套几乎相同的开关，
 * 而且都只在 `input:checked` 上做样式、**没有 hover 反馈** ——
 * 鼠标移上去不知道能不能点。
 */
withDefaults(
  defineProps<{
    modelValue: boolean
    disabled?: boolean
    /** 无障碍标签；没有可见 label 时必须给 */
    ariaLabel?: string
  }>(),
  { disabled: false, ariaLabel: '开关' }
)
defineEmits<{ 'update:modelValue': [boolean] }>()
</script>

<template>
  <label class="switch" :class="{ disabled }">
    <input
      type="checkbox"
      :checked="modelValue"
      :disabled="disabled"
      :aria-label="ariaLabel"
      @change="$emit('update:modelValue', ($event.target as HTMLInputElement).checked)"
    />
    <span class="slider" />
  </label>
</template>

<style scoped>
.switch { position: relative; display: inline-block; width: 46px; height: 24px; cursor: pointer; }
.switch.disabled { cursor: not-allowed; opacity: .5; }
.switch input { opacity: 0; width: 0; height: 0; }

.slider {
  position: absolute;
  inset: 0;
  background: var(--surface-inset);
  border: 1px solid var(--edge);
  border-radius: var(--r-pill);
  transition: background var(--dur) var(--ease), border-color var(--dur) var(--ease);
}
.slider::before {
  content: "";
  position: absolute;
  height: 16px; width: 16px; left: 3px; bottom: 3px;
  background: var(--ink-faint);
  border-radius: 50%;
  transition: transform var(--dur) var(--ease), background var(--dur) var(--ease);
}

/* 悬浮反馈：能看出"可以点" */
.switch:hover input:not(:disabled) + .slider { border-color: var(--edge-hover); background: var(--bg-abyss); }
.switch input:checked + .slider { background: var(--flame-veil); border-color: var(--flame); }
.switch input:checked + .slider::before { transform: translateX(22px); background: var(--flame-bright); }
.switch:hover input:checked:not(:disabled) + .slider { background: var(--flame-glow); }
.switch input:focus-visible + .slider { outline: 2px solid var(--flame); outline-offset: 2px; }
</style>
