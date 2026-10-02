<script setup lang="ts">
/** 指标卡：灵火光晕 + 等宽数字。看板的 KPI 区用它。 */
withDefaults(defineProps<{
  label: string
  value: string | number
  hint?: string
  tone?: 'flame' | 'moss' | 'mist' | 'rust'
}>(), { tone: 'flame' })
</script>

<template>
  <div class="stat glow-flame texture-noise" :class="`tone-${tone}`">
    <div class="v num">{{ value }}</div>
    <div class="l">{{ label }}</div>
    <div v-if="hint" class="h">{{ hint }}</div>
  </div>
</template>

<style scoped>
.stat {
  background: var(--bg-shroud);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  padding: var(--sp-3) var(--sp-4);
  box-shadow: var(--shadow-inset);
}
.v {
  font-size: var(--fs-2xl);
  line-height: 1.15;
  color: var(--flame-bright);
  font-weight: 500;
}
.tone-moss .v { color: var(--moss); }
.tone-mist .v { color: var(--mist); }
.tone-rust .v { color: var(--rust); }
.l { font-size: var(--fs-xs); color: var(--ink-dim); margin-top: 2px; }
.h { font-size: var(--fs-xs); color: var(--ink-faint); margin-top: 2px; }
</style>
