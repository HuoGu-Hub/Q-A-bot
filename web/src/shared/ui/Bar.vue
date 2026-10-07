<script setup lang="ts">
/** 横向条形：用于排行。宽度按最大值归一化。 */
const props = withDefaults(defineProps<{
  label: string
  value: number
  max: number
  tone?: 'flame' | 'moss' | 'rust' | 'mist'
  showValue?: boolean
}>(), { tone: 'flame', showValue: true })

const width = () => {
  if (!props.max || props.max <= 0) return '0%'
  const p = Math.round((props.value / props.max) * 100)
  return Math.max(props.value > 0 ? 2 : 0, p) + '%'
}
</script>

<template>
  <div class="bar-row">
    <span class="lbl">{{ label }}</span>
    <span class="track"><span class="fill" :class="`t-${tone}`" :style="{ width: width() }" /></span>
    <span v-if="showValue" class="val num">{{ value }}</span>
  </div>
</template>

<style scoped>
.bar-row {
  display: grid;
  grid-template-columns: minmax(70px, 22%) 1fr auto;
  align-items: center;
  gap: var(--sp-3);
  font-size: var(--fs-xs);
}
.lbl { color: var(--ink-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 轨道是刻进去的槽：底色更深 + 内阴影 */
.track {
  height: 8px;
  background: var(--stone-void);
  border-radius: var(--r-pill);
  overflow: hidden;
  box-shadow: var(--bevel-inset);
}
.fill {
  display: block;
  height: 100%;
  border-radius: var(--r-pill);
  background: linear-gradient(90deg, var(--ember-deep), var(--ember));
  transition: width var(--dur-slow) var(--ease);
}
.t-moss { background: linear-gradient(90deg, var(--vital-deep), var(--vital)); }
.t-rust { background: linear-gradient(90deg, var(--blight-deep), var(--blight)); }
.t-mist { background: linear-gradient(90deg, var(--stone-600), var(--mist)); }
.val { color: var(--ink-2); min-width: 30px; text-align: right; }
</style>
