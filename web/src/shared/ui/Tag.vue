<script setup lang="ts">
/** 语义标签。颜色只表达「状态」，不表达「装饰」。 */
withDefaults(defineProps<{
  tone?: 'good' | 'bad' | 'warn' | 'neutral' | 'flame'
}>(), { tone: 'neutral' })
</script>

<template>
  <span class="tag" :class="`t-${tone}`"><slot /></span>
</template>

<style scoped>
.tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: var(--r-xs);
  font-size: var(--fs-xs);
  line-height: 1.55;
  border: 1px solid var(--edge);
  color: var(--ink-2);
  white-space: nowrap;
}
.t-good { color: var(--vital); border-color: color-mix(in srgb, var(--vital) 45%, transparent); background: var(--vital-veil); }
.t-bad { color: var(--blight-lift); border-color: color-mix(in srgb, var(--blight) 45%, transparent); background: var(--blight-veil); }
.t-warn { color: var(--warn); border-color: color-mix(in srgb, var(--warn) 45%, transparent); background: var(--warn-veil); }
.t-flame { color: var(--ember); border-color: color-mix(in srgb, var(--ember) 40%, transparent); background: var(--ember-veil); }
</style>
