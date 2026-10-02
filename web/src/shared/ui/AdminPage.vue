<script setup lang="ts">
/**
 * 管理页外壳。
 *
 * 结构只有两段：**一条吸顶栏** + 页面内容。
 *
 * <p><b>为什么是一行而不是两行</b>：原先这里有一个 `page-head`（页面标题 + 一句说明），
 * 下面各面板又各自带一条 `panel-bar`（放刷新/保存/新建这类按钮）。结果是
 * 页面上并排堆着两条工具栏，而 tabs 那一行右边明明空着 —— 既浪费竖向空间，
 * 又让「保存在哪」随页面而变。
 *
 * <p>现在合成一条：左边是二级目录（tabs），右边是本页动作按钮，整条**吸顶**。
 * 这样滚到任何位置，「切换 tab」和「保存」都在同一个地方，不用往上翻。
 *
 * <p><b>页面标题也去掉了</b>：顶端导航已经标出你在哪个页面，再写一遍是重复；
 * 而那类「设置与指令在一处 · 二级目录切换」的说明文字，讲的是我们怎么实现的，
 * 对使用者没有价值。
 *
 * <p>宽度只有两档：`narrow` 1100（表单类）、`wide` 1400（默认）。
 * 原来是三档，第三档 `full` 1720 只服务于「数据大屏」—— 那一页已并入看板，
 * 档位跟着撤掉，免得留一个没人用、注释还指向已删页面的选项。
 */
import { computed, useSlots } from 'vue'

withDefaults(
  defineProps<{
    width?: 'narrow' | 'wide'
  }>(),
  { width: 'wide' }
)

const slots = useSlots()
/** 既没有二级目录也没有动作的页面，不该凭空多一条空栏 */
const hasBar = computed(() => !!(slots.tabs || slots.actions))
</script>

<template>
  <div class="page" :class="`w-${width}`">
    <div v-if="hasBar" class="page-bar">
      <div class="bar-tabs"><slot name="tabs" /></div>
      <span class="spacer" />
      <div class="bar-actions"><slot name="actions" /></div>
    </div>
    <slot name="notice" />
    <slot />
  </div>
</template>

<style scoped>
.page {
  margin: 0 auto;
  padding: 0 var(--sp-4) var(--sp-8);
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.w-narrow { max-width: 1100px; }
.w-wide { max-width: 1400px; }

/*
  吸顶栏。
  - top 用 --admin-bar-h（顶端导航的高度）：导航本身也是 sticky 的，两层的偏移必须对齐，
    否则滚动时栏目会钻到导航底下去。
  - 背景必须不透明：半透明会让下面的内容透出来，滚动时看着像重影。
  - 下边缘就是 tabs 那条线（tabs 自带 border-bottom），两者位置重合、颜色一致，
    所以不会出现双线；没有 tabs 的页面则靠这条 border 分隔。
*/
.page-bar {
  position: sticky;
  top: var(--admin-bar-h, 0px);
  z-index: var(--z-sticky);
  display: flex;
  align-items: flex-end;
  gap: var(--sp-3);
  background: var(--surface-page);
  padding-top: var(--sp-3);
  border-bottom: 1px solid var(--hairline);
  min-height: 0;
}
.spacer { flex: 1; }
.bar-tabs { min-width: 0; flex: 0 1 auto; }
.bar-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  padding-bottom: var(--sp-2);
}

@media (max-width: 720px) {
  /* 窄屏：目录与按钮分两行，否则按钮会被挤到看不见 */
  .page-bar { flex-wrap: wrap; align-items: center; }
  .spacer { display: none; }
  .bar-tabs { flex: 1 1 100%; }
  .bar-actions { flex: 1 1 100%; justify-content: flex-end; }
}
</style>
