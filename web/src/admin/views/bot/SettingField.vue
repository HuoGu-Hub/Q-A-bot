<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    hint?: string
    changed?: boolean
    inline?: boolean
  }>(),
  { changed: false, inline: false }
)
</script>

<template>
  <label class="sf" :class="{ 'sf--inline': inline, changed }">
    <span class="sf-copy">
      <span class="sf-label">
        {{ label }}
        <span v-if="changed" class="dot" role="img" aria-label="已修改" />
      </span>
      <span v-if="hint" class="sf-hint">{{ hint }}</span>
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
  min-width: 0;
  padding: 8px 10px;
  border-radius: var(--r-sm);
  transition: background var(--dur-fast) var(--ease);
}
.sf.changed {
  background: var(--ember-veil);
  box-shadow: inset 2px 0 0 var(--ember);
}
.sf--inline {
  flex-direction: row;
  align-items: center;
  gap: 10px;
}
.sf-copy { min-width: 0; }
.sf--inline .sf-copy { flex: 1; }
.sf--inline .sf-ctrl { flex: none; }
.sf-label {
  display: block;
  font-size: var(--fs-sm);
  color: var(--ink);
  line-height: 1.4;
}
.sf-hint {
  display: block;
  margin-top: 2px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.45;
}
.sf-ctrl { display: block; min-width: 0; }
.dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-left: 6px;
  border-radius: 50%;
  background: var(--ember);
  vertical-align: 2px;
}
@media (max-width: 760px) {
  .sf { padding: 8px 0; }
}
</style>
