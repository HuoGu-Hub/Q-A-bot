<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    rows?: number
    empty?: string
    emptyHint?: string
    label?: string
    cards?: boolean
    forceCards?: boolean
  }>(),
  { rows: undefined, empty: '暂无数据', emptyHint: '', label: '数据表', cards: false, forceCards: false }
)

const CARD_ROW_LIMIT = 30
const useCards = computed(() =>
  props.cards && (props.forceCards || props.rows === undefined || props.rows <= CARD_ROW_LIMIT))
</script>

<template>
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
  background: var(--stone-400);
}
.tbl :deep(td) {
  padding: var(--sp-3);
  border-bottom: 1px solid var(--hairline);
  vertical-align: top;
}
.tbl :deep(tbody tr:last-child td) { border-bottom: 0; }
.tbl :deep(tr) { display: table-row; }
.tbl :deep(td) { display: table-cell; }
.tbl :deep(tbody tr) { transition: background var(--dur-fast) var(--ease); }
.tbl :deep(tbody tr:hover) { background: var(--surface-hover); }
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
