<script setup lang="ts">
withDefaults(defineProps<{
  title: string
  hint?: string
  disabled?: boolean
}>(), { hint: '', disabled: false })

defineEmits<{ select: [] }>()
</script>

<template>
  <button type="button" class="opt" :disabled="disabled" @click="$emit('select')">
    <span class="opt-title">{{ title }}</span>
    <span v-if="hint" class="opt-hint">{{ hint }}</span>
  </button>
</template>

<style scoped>
.opt {
  display: flex;
  flex-direction: column;
  gap: 3px;
  text-align: left;
  padding: var(--sp-3) var(--sp-4);
  background: var(--surface-raised);
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  color: inherit;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease),
              border-color var(--dur-fast) var(--ease),
              transform var(--dur-fast) var(--ease);
}
.opt:hover:not(:disabled) { background: var(--bg-raised); border-color: var(--edge-hover); transform: translateY(-1px); }
.opt:active:not(:disabled) { background: var(--surface-active); transform: translateY(0); }
.opt:focus-visible { outline: 2px solid var(--ember); outline-offset: 2px; }
.opt:disabled { opacity: .45; cursor: not-allowed; }
.opt-title { font-size: var(--fs-body); color: var(--ink); }
.opt-hint { font-size: var(--fs-meta); color: var(--ink-3); line-height: 1.6; }

@media (prefers-reduced-motion: reduce) {
  .opt:hover:not(:disabled) { transform: none; }
}
</style>
