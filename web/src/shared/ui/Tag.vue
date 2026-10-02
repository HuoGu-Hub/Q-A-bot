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
  padding: 1px 8px;
  border-radius: var(--r-xs);
  font-size: var(--fs-xs);
  line-height: 1.7;
  border: 1px solid var(--line-strong);
  color: var(--ink-dim);
  white-space: nowrap;
}
.t-good { color: var(--moss); border-color: var(--moss); background: var(--moss-veil); }
.t-bad { color: var(--rust); border-color: var(--rust); background: var(--rust-veil); }
.t-warn { color: var(--amber); border-color: var(--amber); background: var(--amber-veil); }
.t-flame { color: var(--flame); border-color: var(--copper); background: var(--flame-veil); }
</style>
