<script setup lang="ts">
import Icon from './Icon.vue'

withDefaults(defineProps<{
  label: string
  value: string | number
  hint?: string
  tone?: 'flame' | 'moss' | 'mist' | 'rust'
  icon?: string
}>(), { tone: 'flame', icon: '' })
</script>

<template>
  <div class="stat" :class="'tone-' + tone" :title="hint || undefined">
    <div class="head">
      <Icon v-if="icon" :name="icon" :size="14" class="ic" />
      <span class="l">{{ label }}</span>
    </div>
    <div class="v num">{{ value }}</div>
  </div>
</template>

<style scoped>
.stat {
  position: relative;
  background: var(--stone-300);
  border-radius: var(--r-md);
  padding: var(--sp-4) var(--sp-4) var(--sp-3);
  box-shadow: var(--bevel-raised);
  overflow: hidden;
  min-width: 0;
}
.stat::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 2px;
  background: var(--ember);
  opacity: .6;
}
.tone-moss::before { background: var(--vital); }
.tone-mist::before { background: var(--mist); }
.tone-rust::before { background: var(--blight); }

.head { display: flex; align-items: center; gap: 6px; min-width: 0; }
.ic { color: var(--ink-3); }
.l {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  letter-spacing: .03em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.v {
  font-size: var(--fs-2xl);
  line-height: 1.15;
  margin-top: 2px;
  color: var(--ink);
  font-weight: var(--fw-medium);
  white-space: normal;
}
.tone-moss .v { color: var(--vital); }
.tone-rust .v { color: var(--blight-lift); }

@media (max-width: 760px) {
  .v { font-size: var(--fs-lg); letter-spacing: -.01em; white-space: nowrap; }
}
</style>
