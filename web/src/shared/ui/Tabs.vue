<script setup lang="ts">
/**
 * 二级目录（Tab）。
 *
 * 抽它的原因：项目里原有 **3 套**互不相同的 Tab 写法 —— 知识库页与广场页是下划线式，
 * 日志页是分段控件式，还有的页面直接没有。
 *
 * 统一成下划线式：激活项下方一道灵火线。理由 —— 下划线式在深色底上比分段控件更轻，
 * 也不会和面板边缘打架。
 *
 * v2：+ 键盘左右键可以在 tab 之间移动（role="tablist" 的应有行为，
 * 上一版只有 role 没有键盘实现，读屏会告诉用户"这是 tablist"然后什么也做不了）。
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
</style>
