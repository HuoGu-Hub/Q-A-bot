<script setup lang="ts">
/**
 * 指标卡。看板的 KPI 区用它。
 *
 * 顶部一道与 tone 同色的刻线，取代右上角的橙色光晕 ——
 * 光晕在 8 张卡上会变成一片橙雾，等于没有重点；等长刻线反而能把「哪一类指标」读出来。
 *
 * <p>数值一律 nowrap + 等宽：手机上 8 张卡并排时，"34,821 / 21" 折成两行
 * 会让每张卡高度不一致，整片网格跟着参差。
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
  <div class="stat" :class="'tone-' + tone">
    <div class="head">
      <Icon v-if="icon" :name="icon" :size="14" class="ic" />
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
  min-width: 0;
}
/* 刻线：一条 2px 实色，颜色 = 这一类指标的语义 */
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
  white-space: nowrap;
}
.tone-moss .v { color: var(--vital); }
.tone-rust .v { color: var(--blight-lift); }
.h { font-size: var(--fs-xs); color: var(--ink-3); margin-top: 3px; line-height: 1.5; }

@media (max-width: 760px) {
  /* 两列排布时卡片内宽只有约 145px，「640 ms / 2.0 s」这种组合值在 19px 下会被切掉。
     降到 16px 并略收字距，数字仍然清楚。 */
  .v { font-size: var(--fs-lg); letter-spacing: -.01em; }
}
</style>