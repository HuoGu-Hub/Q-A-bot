<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    hint?: string
    changed?: boolean
  }>(),
  { changed: false }
)
</script>

<template>
  <label class="sf" :class="{ changed }">
    <span class="sf-copy">
      <span class="sf-label">
        {{ label }}
        <span v-if="changed" class="dot" role="img" aria-label="已修改" />
      </span>
      <span class="sf-hint" :class="{ 'sf-hint--blank': !hint }">{{ hint || '\u00A0' }}</span>
    </span>
    <span class="sf-ctrl">
      <slot />
    </span>
  </label>
</template>

<style scoped>
.sf {
  display: flex;
  flex-direction: column;
  gap: 6px;
  height: 100%;
  min-width: 0;
  box-sizing: border-box;
  padding: 10px 12px;
  border-radius: var(--r-sm);
  transition: background var(--dur-fast) var(--ease);
}
.sf.changed {
  background: var(--ember-veil);
  box-shadow: inset 2px 0 0 var(--ember);
}
.sf-copy { min-width: 0; }
.sf-label {
  display: block;
  font-size: var(--fs-sm);
  color: var(--ink);
  line-height: 1.4;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sf-hint {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 35px;
  margin-top: 2px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.45;
}
.sf-hint--blank { visibility: hidden; }
.sf-ctrl {
  display: block;
  min-width: 0;
  margin-top: auto;
}
.dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-left: 6px;
  border-radius: 50%;
  background: var(--ember);
  vertical-align: 2px;
}
</style>
