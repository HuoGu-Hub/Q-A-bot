<script setup lang="ts">
/**
 * 药丸按钮 —— 标签云、图层筛选、快捷筛选共用。
 *
 * 三个页面各写过一遍 .tag-chip / .chip / .hot-item，圆角、内边距、选中态都不一样。
 * 这里定死：6px 圆角、min-height 32、选中等于灵火描边 + 淡底。
 */
import Icon from './Icon.vue'

withDefaults(defineProps<{
  active?: boolean
  count?: string | number | ''
  icon?: string
  /** 小一号：用于行内的次级筛选 */
  small?: boolean
}>(), { active: false, count: '', icon: '', small: false })

defineEmits<{ click: [] }>()
</script>

<template>
  <button
    type="button"
    class="chip"
    :class="{ on: active, small }"
    :aria-pressed="active"
    @click="$emit('click')"
  >
    <Icon v-if="icon" :name="icon" :size="14" />
    <slot />
    <span v-if="count !== ''" class="n num">{{ count }}</span>
  </button>
</template>

<style scoped>
.chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 32px;
  padding: 4px 12px;
  border-radius: var(--r-sm);
  border: 1px solid var(--edge);
  background: var(--stone-400);
  color: var(--ink-2);
  font-size: var(--fs-sm);
  cursor: pointer;
  box-shadow: var(--bevel-shelf);
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease);
}
.chip:hover { background: var(--stone-500); border-color: var(--edge-hover); color: var(--ink); }
.chip.on {
  border-color: var(--ember);
  background: var(--ember-veil);
  color: var(--ember-hot);
}
.chip.small { min-height: 26px; padding: 2px 9px; font-size: var(--fs-xs); }
.n { font-size: var(--fs-micro); color: var(--ink-3); }
.chip.on .n { color: var(--ember); }

@media (max-width: 760px) {
  .chip { min-height: 36px; }
}
</style>
