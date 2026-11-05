<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    hint?: string
    changed?: boolean
    controlWidth?: string
  }>(),
  { changed: false, controlWidth: '340px' }
)
</script>

<template>
  <label class="field" :class="{ changed }">
    <span class="meta">
      <span class="label">
        {{ label }}
        <span v-if="changed" class="dot" role="img" aria-label="已修改" />
      </span>
      <span v-if="hint" class="hint">{{ hint }}</span>
    </span>
    <span class="ctrl">
      <slot />
    </span>
  </label>
</template>

<style scoped>
.field + .field { border-top: 1px solid var(--hairline); }

.field {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, v-bind(controlWidth));
  gap: var(--sp-4);
  align-items: start;
  padding: var(--sp-3);
  border-radius: var(--r-sm);
  transition: background var(--dur-fast) var(--ease);
}
.field:first-child { padding-top: var(--sp-1); }
.field:last-child { padding-bottom: var(--sp-1); }

.field.changed {
  background: var(--ember-veil);
  box-shadow: inset 2px 0 0 var(--ember);
}

.meta { min-width: 0; }
.label { display: block; font-size: var(--fs-base); color: var(--ink); }
.dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-left: 6px;
  border-radius: 50%;
  background: var(--ember);
  vertical-align: 2px;
}
.hint { display: block; font-size: var(--fs-xs); color: var(--ink-3); margin-top: 2px; line-height: 1.65; }
.ctrl { display: block; min-width: 0; }

@media (max-width: 760px) {
  .field { grid-template-columns: minmax(0, 1fr); gap: var(--sp-2); padding: var(--sp-3) 0; }
  .field + .field { border-top: 1px solid var(--hairline); }
}
</style>
