<script setup lang="ts">
import { computed, useSlots } from 'vue'

const props = withDefaults(
  defineProps<{
    width?: 'narrow' | 'wide'
    title?: string
    count?: number | string
  }>(),
  { width: 'wide', title: '', count: '' }
)

const slots = useSlots()
const hasBar = computed(() => !!(props.title || slots.tabs || slots.actions))
</script>

<template>
  <div class="page" :class="'w-' + width">
    <div v-if="hasBar" class="page-bar">
      <div v-if="title" class="bar-title">
        <h2>{{ title }}</h2>
        <span v-if="count !== ''" class="bar-count num">{{ count }}</span>
      </div>
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
.bar-tabs { min-width: 0; flex: 0 1 auto; }
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
  .page-bar { flex-wrap: wrap; align-items: center; gap: var(--sp-2); padding-top: var(--sp-2); }
  .spacer { display: none; }
  .bar-tabs { flex: 1 1 100%; }
  .bar-actions {
    flex: 1 1 auto;
    margin-left: auto;
    justify-content: flex-end;
    flex-wrap: wrap;
    padding-bottom: var(--sp-2);
  }
}
</style>
