<script setup lang="ts">
/**
 * 手机底栏。
 *
 * ⚠️ 这里修的是一个真实事故：上一版用
 *   grid-template-columns: repeat(4, 1fr)
 * 而导航有 5 项，于是「关于」被挤到第二行、左对齐，底栏高度从 58 变成 117，
 * 每一页的底部内容都被它压住。
 *
 * 现在用 flex，每项 flex:1 1 0 + min-width:0：**项数变了也不会换行**，
 * 加第 6 项时只会变窄，不会掉行。
 */
import { RouterLink } from 'vue-router'
import Icon from './Icon.vue'

withDefaults(defineProps<{
  items: Array<{ to: string; label: string; icon: string; active: boolean }>
  label?: string
}>(), { label: '主导航' })
</script>

<template>
  <nav class="bn" :aria-label="label">
    <RouterLink
      v-for="n in items"
      :key="n.to"
      :to="n.to"
      class="tab"
      :class="{ active: n.active }"
      :aria-current="n.active ? 'page' : undefined"
    >
      <span v-if="n.active" class="wick" aria-hidden="true" />
      <Icon :name="n.icon" :size="21" />
      <span class="lbl">{{ n.label }}</span>
    </RouterLink>
  </nav>
</template>

<style scoped>
.bn {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: var(--z-sticky);
  display: flex;
  align-items: stretch;
  /* 内容滚到下面时不该透出来 */
  background: var(--stone-300);
  background: color-mix(in srgb, var(--stone-300) 94%, transparent);
  backdrop-filter: saturate(1.3) blur(16px);
  -webkit-backdrop-filter: saturate(1.3) blur(16px);
  border-top: 1px solid var(--hairline);
  /* 全面屏的 Home 指示条占位，否则最后一排按钮会被它压住 */
  padding-bottom: var(--safe-b);
}

.tab {
  position: relative;
  flex: 1 1 0;
  /* ⚠️ 必须写 min-width:0，否则长标签会把这一项撑宽、把别人挤扁 */
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  /* 44px 是拇指能稳定点中的最小高度 */
  min-height: var(--tabbar-h);
  color: var(--ink-3);
  text-decoration: none;
  font-size: var(--fs-micro);
  letter-spacing: .02em;
  transition: color var(--dur-fast) var(--ease);
}
.tab:hover { text-decoration: none; color: var(--ink-2); }
.tab.active { color: var(--ember); }

/* 火芯：激活项顶上的一小段亮线，全站只有它和标题刻痕用灵火色 */
.wick {
  position: absolute;
  top: 0;
  left: 50%;
  width: 22px;
  height: 2px;
  margin-left: -11px;
  border-radius: 0 0 2px 2px;
  background: var(--ember);
  box-shadow: 0 0 10px var(--ember-glow);
}
.lbl {
  line-height: 1;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 360px 下：图标 20、字号 10，仍然一行 */
@media (max-width: 380px) {
  .tab { font-size: 10px; gap: 2px; }
}
</style>
