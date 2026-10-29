<script setup lang="ts">
/**
 * 管理页外壳。结构只有两段：一条吸顶栏 + 页面内容。
 *
 * <p>为什么是一行而不是两行：原先这里有一个 page-head（页面标题 + 说明），
 * 下面各面板又各自带一条 panel-bar（刷新/保存/新建）。结果是并排堆着两条工具栏，
 * 而 tabs 那行右边明明空着 —— 既浪费竖向空间，又让「保存在哪」随页面而变。
 *
 * <p>现在合成一条：左边二级目录，右边本页动作，整条吸顶。
 *
 * <p>手机端：目录一行、动作一行，都不换行。动作行可横滚，
 * 但不会再折成三行把吸顶栏撑高一倍。
 */
import { computed, useSlots } from 'vue'

const props = withDefaults(
  defineProps<{
    width?: 'narrow' | 'wide'
    /** 页面标题。没有二级目录的页面用它占住左边，标题与动作就落在同一行 */
    title?: string
    /** 标题右侧的计数，例如「4」 */
    count?: number | string
  }>(),
  { width: 'wide', title: '', count: '' }
)

const slots = useSlots()
/** 既没有标题、二级目录，也没有动作的页面，不该凭空多一条空栏 */
const hasBar = computed(() => !!(props.title || slots.tabs || slots.actions))
</script>

<template>
  <div class="page" :class="'w-' + width">
    <div v-if="hasBar" class="page-bar">
      <div v-if="title" class="bar-title">
        <h2>{{ title }}</h2>
        <span v-if="count !== ''" class="bar-count num">{{ count }}</span>
      </div>
      <!-- 没有二级目录的页面不渲染这一格：它在手机上占满整行，会把标题和动作挤到两行 -->
      <div v-if="$slots.tabs" class="bar-tabs"><slot name="tabs" /></div>
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
  padding: 0 var(--sp-5) var(--sp-8);
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.w-narrow { max-width: 1100px; }
.w-wide { max-width: 1400px; }

/*
  吸顶栏：
  - top 用 --admin-bar-h：顶端导航也是 sticky，两层偏移必须对齐
  - 背景用不透明的页面底，半透明会让滚动内容透出来像重影
  - 下边缘就是 tabs 那条线，位置与颜色重合，所以不会出现双线
*/
.page-bar {
  position: sticky;
  top: var(--admin-bar-h, 0px);
  z-index: var(--z-sticky);
  display: flex;
  align-items: flex-end;
  gap: var(--sp-3);
  background: var(--stone-200);
  padding-top: var(--sp-3);
  border-bottom: 1px solid var(--hairline);
  min-height: 0;
}
/* 没有二级目录的页面：标题占左边，动作靠右，同一行 */
.bar-title {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  flex: none;
  padding-bottom: var(--sp-2);
}
.bar-title h2 {
  margin: 0;
  font-family: var(--font-title);
  font-size: var(--fs-xl);
  font-weight: var(--fw-semi);
  letter-spacing: var(--tracking-ink);
  color: var(--ink);
}
.bar-count {
  flex: none;
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--stone-void);
  border-radius: var(--r-pill);
  padding: 1px 9px;
  box-shadow: var(--bevel-inset);
}

.spacer { flex: 1; }
/* 目录吃掉剩余宽度；不够时它自己横向滚 */
.bar-tabs { min-width: 0; flex: 0 1 auto; }
/*
  动作按钮不参与压缩，也不换行。
  腾地方是目录的事，按钮只负责待在一条线上。
*/
.bar-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex: none;
  flex-wrap: nowrap;
  padding-bottom: var(--sp-2);
}

@media (max-width: 760px) {
  .page { padding: 0 var(--sp-3) var(--sp-6); gap: var(--sp-3); }
  /* 窄屏：目录与动作各占一行；动作行自身可横滚，绝不折行 */
  .page-bar { flex-wrap: wrap; align-items: center; gap: var(--sp-2); padding-top: var(--sp-2); }
  .spacer { display: none; }
  .bar-tabs { flex: 1 1 100%; }
  /*
    动作行允许换行，但**不允许横滚**。
    横滚 + 右对齐会让第一颗按钮被裁在屏幕外（实测「全部展开」只剩「部展开」），
    用户既看不到开头、也不知道能滑。按钮在手机上已经收小，四颗通常正好一行，
    五个以上的页面折成两行 —— 比被裁掉一颗好。
  */
  .bar-actions {
    /* auto 而不是 100%：没有二级目录的页面，标题与动作就能共用一行；
       有目录的页面目录已经占了整行，动作自然落到下一行 */
    flex: 1 1 auto;
    margin-left: auto;
    justify-content: flex-end;
    flex-wrap: wrap;
    padding-bottom: var(--sp-2);
  }
}
</style>