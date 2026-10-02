<script setup lang="ts">
/**
 * 数据表格。
 *
 * 抽它的原因：6 个 view 各写一份 `.tbl`（cell padding 7/8 与 6/8 之别），
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
  }>(),
  { rows: undefined, empty: '暂无数据', emptyHint: '' }
)
</script>

<template>
  <div class="tbl-wrap">
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
.tbl-wrap { overflow-x: auto; }

.tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
}
.tbl :deep(th) {
  text-align: left;
  font-weight: var(--fw-medium);
  font-size: var(--fs-meta);
  letter-spacing: var(--tracking-label);
  color: var(--ink-faint);
  padding: var(--sp-2) var(--sp-3);
  border-bottom: 1px solid var(--hairline);
  white-space: nowrap;
  position: sticky;
  top: 0;
  background: var(--surface-panel);
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
  `display: flex`（给卡片内的横排用的）—— 落到 <tr> 上就把整张表拆了：
  行不再是表格行，单元格各排各的，列跟表头对不上，而且每行偏得还不一样。
  这里用 `[data-v] tr` 比调用方的 `.row[data-v]` 高一级的选择器把 display 钉住，
  调用方起什么 class 都拆不掉这张表。
*/
.tbl :deep(tr) { display: table-row; }
.tbl :deep(td) { display: table-cell; }
/* 行悬浮：表格里"光标在哪一行"必须看得出来 */
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
.tbl :deep(code) { font-size: 11px; }

.empty {
  margin: 0;
  padding: var(--sp-7) var(--sp-4);
  text-align: center;
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.empty-title { color: var(--ink-dim); font-size: var(--fs-sm); }
.empty-hint { color: var(--ink-faint); font-size: var(--fs-meta); }
</style>
