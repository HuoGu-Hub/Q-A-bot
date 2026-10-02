<script setup lang="ts">
/**
 * 二级目录（Tab）。
 *
 * 抽它的原因：项目里原有 **3 套**互不相同的 Tab 写法 ——
 * 知识库页与广场页是下划线式，日志页是分段控件式，还有的页面直接没有。
 * 再加「页面信息」「Bot 配置」两个二级目录就会变成 4~5 套。
 *
 * 这里统一下划线式：激活项下方一道灵火线。理由 ——
 * 下划线式在深色底上比分段控件更轻，也不会和面板边缘打架。
 */
withDefaults(
  defineProps<{
    tabs: Array<{ key: string; label: string; count?: number | string }>
    modelValue: string
  }>(),
  {}
)
defineEmits<{ 'update:modelValue': [string] }>()
</script>

<template>
  <div class="tabs" role="tablist">
    <button
      v-for="t in tabs"
      :key="t.key"
      type="button"
      role="tab"
      class="tab"
      :class="{ active: t.key === modelValue }"
      :aria-selected="t.key === modelValue"
      @click="$emit('update:modelValue', t.key)"
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
  font-size: var(--fs-sm);
  color: var(--ink-dim);
  cursor: pointer;
  white-space: nowrap;
  border-radius: var(--r-sm) var(--r-sm) 0 0;
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.tab:hover { color: var(--ink); background: var(--surface-hover); }

.tab.active { color: var(--flame-bright); }
/* 激活线：压在容器底边上，不额外占高度 */
.tab.active::after {
  content: '';
  position: absolute;
  left: var(--sp-2);
  right: var(--sp-2);
  bottom: -1px;
  height: 2px;
  border-radius: 1px;
  background: linear-gradient(90deg, transparent, var(--flame), transparent);
}

.count {
  font-size: var(--fs-meta);
  color: var(--ink-faint);
  margin-left: var(--sp-2);
}
.tab.active .count { color: var(--flame); }
</style>
