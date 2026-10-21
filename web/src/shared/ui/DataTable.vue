<script setup lang="ts">
/**
 * 数据表格。
 *
 * 统一原先 6 份各写一遍的 .tbl：表头吸顶、数字列等宽、行悬浮、空态。
 *
 * cards 模式：窄屏（≤680px）下把每行变成一张卡，用表头的 th 文本做字段名。
 * ⚠️ 需要调用方在 td 上写 data-label，否则卡片里只有值、没有「这是什么」。
 * 没写 data-label 的表不要开 cards —— 宁可横向滚动，也不要一张看不懂的卡。
 */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    /** 行数 —— 为 0 时显示空态。不想用空态就传 undefined */
    rows?: number
    empty?: string
    emptyHint?: string
    /** 无障碍名字。同一页多张表时给具体的名字 */
    label?: string
    /** 窄屏下转为卡片列表 */
    cards?: boolean
    /** 无视 30 行上限，强制卡片化。只给「行内容短、且已经有筛选/分页」的表用 */
    forceCards?: boolean
  }>(),
  { rows: undefined, empty: '暂无数据', emptyHint: '', label: '数据表', cards: false, forceCards: false }
)

/**
 * 卡片模式的上限：30 行。
 *
 * <p>小程序卡只在「一屏能看完」的表上划算。100 条记录、198 份文档这种长表，
 * 每行一张卡会变成一万多像素的页面 —— 比横向滚动难用得多。
 * 所以调用方可以无脑开 cards，由这里按行数兜底：长表自动回到表格 + 横向滚动。
 */
const CARD_ROW_LIMIT = 30
const useCards = computed(() =>
  props.cards && (props.forceCards || props.rows === undefined || props.rows <= CARD_ROW_LIMIT))
</script>

<template>
  <!--
    ⚠️ tabindex="0" 是给键盘用户的：宽表在窄屏上只能横向滚动，
    而可滚动区域如果不可聚焦，键盘就永远滚不动它。
  -->
  <div
    class="tbl-wrap"
    :class="{ 'is-cards': useCards }"
    role="group"
    :aria-label="label"
    tabindex="0"
  >
    <table v-if="rows === undefined || rows > 0" class="tbl">
      <slot />
    </table>
    <p v-else class="empty">
      <span class="empty-title">{{ empty }}</span>
      <span v-if="emptyHint" class="empty-hint">{{ emptyHint }}</span>
    </p>
  </div>
</template>

<style scoped>
.tbl-wrap { overflow-x: auto; overscroll-behavior-x: contain; }

.tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
}
.tbl :deep(th) {
  text-align: left;
  font-weight: var(--fw-medium);
  font-size: var(--fs-xs);
  letter-spacing: .04em;
  color: var(--ink-3);
  padding: var(--sp-2) var(--sp-3);
  border-bottom: 1px solid var(--edge);
  white-space: nowrap;
  position: sticky;
  top: 0;
  z-index: 1;
  /* 表头要有自己的底：吸顶时它下面会压着行 */
  background: var(--stone-400);
}
.tbl :deep(td) {
  padding: var(--sp-3);
  border-bottom: 1px solid var(--hairline);
  vertical-align: top;
}
.tbl :deep(tbody tr:last-child td) { border-bottom: 0; }
/*
  ⚠️ 行必须钉死成 table-row。
  调用方的 <tr> 常被顺手写上 class="row"，而它们自己那份 .row 多半是
  display:flex —— 落到 <tr> 上就把整张表拆了。
*/
.tbl :deep(tr) { display: table-row; }
.tbl :deep(td) { display: table-cell; }
.tbl :deep(tbody tr) { transition: background var(--dur-fast) var(--ease); }
.tbl :deep(tbody tr:hover) { background: var(--surface-hover); }
/* 数字一律等宽 + 右对齐，多行之间才能上下对齐着看 */
.tbl :deep(.num),
.tbl :deep(td.num),
.tbl :deep(th.num) {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
}
.tbl :deep(td.num),
.tbl :deep(th.num) { text-align: right; }
.tbl :deep(code) { font-size: var(--fs-xs); }

.empty {
  margin: 0;
  padding: var(--sp-7) var(--sp-4);
  text-align: center;
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.empty-title { color: var(--ink-2); font-size: var(--fs-base); }
.empty-hint { color: var(--ink-3); font-size: var(--fs-xs); }

@media (max-width: 760px) {
  .tbl { font-size: var(--fs-xs); }
  .tbl :deep(th) { padding: var(--sp-2); }
  .tbl :deep(td) { padding: var(--sp-2); }
}

/* ==================== 卡片模式 ====================
   每行一条。字段名来自 td 的 data-label，值单独一行 —— 手机上横着扫一行
   五个字段是不可能的，竖着读才读得下去。 */
@media (max-width: 680px) {
  .is-cards { overflow-x: visible; }
  .is-cards .tbl,
  .is-cards .tbl :deep(tbody),
  .is-cards .tbl :deep(tr),
  .is-cards .tbl :deep(td) { display: block; }
  .is-cards .tbl :deep(thead) { display: none; }
  .is-cards .tbl :deep(tbody tr) {
    background: var(--stone-300);
    border-radius: var(--r-md);
    box-shadow: var(--bevel-raised);
    padding: var(--sp-3) var(--sp-4);
    margin-bottom: var(--sp-2);
  }
  .is-cards .tbl :deep(tbody tr:hover) { background: var(--stone-400); }
  .is-cards .tbl :deep(td) {
    display: grid;
    grid-template-columns: 84px minmax(0, 1fr);
    gap: var(--sp-2);
    padding: 3px 0;
    border: 0;
    text-align: left !important;
  }
  .is-cards .tbl :deep(td)::before {
    content: attr(data-label);
    color: var(--ink-3);
    font-size: var(--fs-xs);
    line-height: 1.6;
  }
  .is-cards .tbl :deep(td:not([data-label])) { grid-template-columns: 1fr; }
  .is-cards .tbl :deep(td:not([data-label]))::before { display: none; }
}
</style>