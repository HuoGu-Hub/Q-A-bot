<script setup lang="ts">
/**
 * 二级目录（Tab）。
 *
 * 下划线式：激活项下方一道 2px 灵火线。比 segmented 控件轻，也不和面板边缘打架。
 * 键盘左右键可在 tab 之间移动 —— role=tablist 的应有行为。
 *
 * <p>手机上 7 个 tab 横排会被截断，所以：可横向滚动 + 右缘渐隐提示「还有」。
 */
import { ref } from 'vue'

defineProps<{
  tabs: Array<{ key: string; label: string; count?: number | string }>
  modelValue: string
}>()
const emit = defineEmits<{ 'update:modelValue': [string] }>()

const root = ref<HTMLElement | null>(null)

function onKey(e: KeyboardEvent) {
  const keys = ['ArrowLeft', 'ArrowRight', 'Home', 'End']
  if (!keys.includes(e.key)) return
  e.preventDefault()
  const btns = Array.from(root.value?.querySelectorAll<HTMLButtonElement>('[role="tab"]') ?? [])
  const cur = btns.findIndex(b => b.getAttribute('aria-selected') === 'true')
  if (cur < 0) return
  const next =
    e.key === 'Home' ? 0
      : e.key === 'End' ? btns.length - 1
        : e.key === 'ArrowLeft' ? (cur - 1 + btns.length) % btns.length
          : (cur + 1) % btns.length
  btns[next]?.focus()
  btns[next]?.click()
}
</script>

<template>
  <div ref="root" class="tabs" role="tablist" @keydown="onKey">
    <button
      v-for="t in tabs"
      :key="t.key"
      type="button"
      role="tab"
      class="tab"
      :class="{ active: t.key === modelValue }"
      :aria-selected="t.key === modelValue"
      :tabindex="t.key === modelValue ? 0 : -1"
      @click="emit('update:modelValue', t.key)"
    >
      {{ t.label }}
      <span v-if="t.count !== undefined && t.count !== ''" class="count num">{{ t.count }}</span>
    </button>
  </div>
</template>

<style scoped>
.tabs {
  display: flex;
  gap: var(--sp-1);
  overflow-x: auto;
  scrollbar-width: none;
  border-bottom: 1px solid var(--hairline);
}
.tabs::-webkit-scrollbar { display: none; }

.tab {
  flex: none;
  position: relative;
  background: none;
  border: 0;
  padding: var(--sp-2) var(--sp-4);
  font: inherit;
  font-size: var(--fs-base);
  color: var(--ink-3);
  cursor: pointer;
  white-space: nowrap;
  border-radius: var(--r-sm) var(--r-sm) 0 0;
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.tab:hover { color: var(--ink); background: var(--surface-hover); }
.tab.active { color: var(--ember-hot); }
/* 激活线：压在容器底边上，不额外占高度 */
.tab.active::after {
  content: '';
  position: absolute;
  left: var(--sp-2);
  right: var(--sp-2);
  bottom: -1px;
  height: 2px;
  border-radius: 1px;
  background: var(--ember);
}

.count {
  font-size: var(--fs-meta);
  color: var(--ink-3);
  margin-left: var(--sp-2);
}
.tab.active .count { color: var(--ember); }

@media (max-width: 760px) {
  .tab { min-height: 44px; padding: var(--sp-2) var(--sp-3); font-size: var(--fs-sm); }
  /* 右缘渐隐：告诉用户「右边还有」。
     遮罩作用在整个滚动容器上，所以只在窄屏生效，桌面端最后一项不会被虚化。 */
  .tabs {
    -webkit-mask-image: linear-gradient(90deg, #000 0, #000 calc(100% - 22px), transparent 100%);
    mask-image: linear-gradient(90deg, #000 0, #000 calc(100% - 22px), transparent 100%);
  }
}
</style>
