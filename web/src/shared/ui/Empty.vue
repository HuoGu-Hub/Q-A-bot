<script setup lang="ts">
/** 空态。空白页会让人以为坏了，所以要明确说「这里本来就没东西」。 */
withDefaults(defineProps<{ text?: string; hint?: string }>(), {
  text: '这里还没有内容',
  hint: '',
})
</script>

<template>
  <div class="empty">
    <div class="glyph" aria-hidden="true">✦</div>
    <div class="t">{{ text }}</div>
    <div v-if="hint" class="h">{{ hint }}</div>
  </div>
</template>

<style scoped>
.empty { text-align: center; padding: var(--sp-6) var(--sp-4); }
.glyph { color: var(--copper-dim); font-size: 22px; margin-bottom: var(--sp-2); }
.t { color: var(--ink-dim); font-size: var(--fs-sm); }
.h { color: var(--ink-faint); font-size: var(--fs-xs); margin-top: var(--sp-1); }
</style>
