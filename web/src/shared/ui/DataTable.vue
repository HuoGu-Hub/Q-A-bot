<script setup lang="ts">
/**
 * 数据表格。
 *
 * 抽它的原因：6 个 view 各写一份 .tbl（cell padding 7/8 与 6/8 之别），
 * 行高、表头色、hover 全都不一致。这里统一一套，并补上原先普遍缺失的：
 * 表头吸顶、数字列等宽对齐、行悬浮反馈、空态。
 *
 * 用法（表结构留在调用方，样式由这里统一）：
 *   <DataTable :rows="list" :empty="'还没有记录'">
 *     <thead><tr><th>时间</th><th class="num">命中</th></tr></thead>
 *     <tbody><tr v-for="r in list"><td>…</td><td class="num">…</td></tr></tbody>
 *   </DataTable>
 */
withDefaults(
  defineProps<{
    /** 行数 —— 为 0 时显示空态。不想用空态就传 undefined */
    rows?: number
    empty?: string
    /** 空态的补充说明（怎么才能有数据） */
    emptyHint?: string
    /** 无障碍名字。默认「数据表」，同一个页面有多张表时给具体的名字 */
    label?: string
  }>(),
  { rows: undefined, empty: '暂无数据', emptyHint: '', label: '数据表' }
)
</script>

<template>
  <!--
    ⚠️ tabindex="0" 是给键盘用户的。
    宽表在窄屏上只能横向滚动，而 scrollable 区域如果不可聚焦，键盘就**永远滚不动它**
    —— 最后几列对键盘用户等于不存在（axe 判 serious：scrollable-region-focusable）。
    role="group" + aria-label 让这一块在读屏里有个名字，也不至于变成一个大 landmark。
  -->
  <div class="tbl-wrap" role="group" :aria-label="label" tabindex="0">
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
/* 横向可滚 + 到边提示：手机上宽表格必然溢出，所以必须有横向滚动，
   但不能让整页跟着横滚（overflow-x:auto 建了独立的滚动上下文）。 */
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
  letter-spacing: var(--tracking-label);
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
  调用方的模板里 <tr> 常被顺手写上 class="row"，而它们自己那份 .row 多半是
  display: flex（给卡片内的横排用的）—— 落到 <tr> 上就把整张表拆了：
  行不再是表格行，单元格各排各的，列跟表头对不上，而且每行偏得还不一样。
  这里用父级选择器比调用方的 .row[data-v] 高一级，把 display 钉住。
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
</style>
