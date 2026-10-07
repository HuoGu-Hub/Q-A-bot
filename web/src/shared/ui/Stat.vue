<script setup lang="ts">
/**
 * 指标卡。看板的 KPI 区用它。
 *
 * v2：顶部一道与 tone 同色的刻线，取代上一版右上角的橙色光晕。
 * 光晕在 8 张卡上会变成一片橙雾（根本没有重点），刻线是**等长度**的位置信号，
 * 8 张并排时反而能把"哪一类指标"读出来。
 */
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
  <div class="stat" :class="`tone-${tone}`">
    <div class="head">
      <Icon v-if="icon" :name="icon" :size="15" class="ic" />
      <span class="l">{{ label }}</span>
    </div>
    <div class="v num">{{ value }}</div>
    <div v-if="hint" class="h">{{ hint }}</div>
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
}
/* 刻线：不是渐变、不是光晕，就是一条 2px 的实色，颜色 = 这一类指标的语义 */
.stat::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 2px;
  background: var(--ember);
  opacity: .55;
}
.tone-moss::before { background: var(--vital); }
.tone-mist::before { background: var(--mist); }
.tone-rust::before { background: var(--blight); }

.head { display: flex; align-items: center; gap: 6px; }
.ic { color: var(--ink-3); }
.l { font-size: var(--fs-xs); color: var(--ink-3); letter-spacing: .03em; }
.v {
  font-size: var(--fs-2xl);
  line-height: 1.15;
  margin-top: 2px;
  color: var(--ink);
  font-weight: var(--fw-medium);
}
.tone-moss .v { color: var(--vital); }
.tone-rust .v { color: var(--blight-lift); }
.h { font-size: var(--fs-xs); color: var(--ink-3); margin-top: 3px; line-height: 1.5; }
</style>
