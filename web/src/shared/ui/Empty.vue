<script setup lang="ts">
/** 空态。空白页会让人以为坏了，所以要明确说「这里本来就没东西」。 */
import Icon from './Icon.vue'

withDefaults(defineProps<{ text?: string; hint?: string; icon?: string }>(), {
  text: '这里还没有内容',
  hint: '',
  icon: 'spark',
})
</script>

<template>
  <div class="empty">
    <span class="glyph" aria-hidden="true"><Icon :name="icon" :size="22" /></span>
    <div class="t">{{ text }}</div>
    <div v-if="hint" class="h">{{ hint }}</div>
  </div>
</template>

<style scoped>
.empty { text-align: center; padding: var(--sp-6) var(--sp-4); }
.glyph {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  margin: 0 auto var(--sp-3);
  border-radius: 50%;
  background: var(--stone-void);
  box-shadow: var(--bevel-inset);
  color: var(--ink-4);
}
.t { color: var(--ink-2); font-size: var(--fs-base); }
.h { color: var(--ink-3); font-size: var(--fs-xs); margin-top: var(--sp-1); line-height: 1.7; }
</style>
