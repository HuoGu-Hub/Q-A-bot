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
.lbl { color: var(--ink-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.track { height: 9px; background: var(--bg-sunken); border-radius: var(--r-xs); overflow: hidden; }
.fill {
  display: block;
  height: 100%;
  border-radius: var(--r-xs);
  background: linear-gradient(90deg, var(--flame-deep), var(--flame-bright));
  transition: width var(--dur-slow) var(--ease);
}
.t-moss { background: linear-gradient(90deg, var(--moss-deep), var(--moss)); }
.t-rust { background: linear-gradient(90deg, var(--rust-deep), var(--rust)); }
.t-mist { background: linear-gradient(90deg, var(--line-strong), var(--mist)); }
.val { color: var(--ink-dim); min-width: 28px; text-align: right; }
</style>
