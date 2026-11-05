<script setup lang="ts">
defineProps<{
  title: string
}>()
</script>

<template>
  <header class="ph">
    <h1 class="ph-title">{{ title }}</h1>
    <div v-if="$slots.actions" class="ph-actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.ph {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
  margin: 0 0 var(--sp-5);
  padding-bottom: var(--sp-3);
  border-bottom: 1px solid var(--hairline);
}
.ph-title {
  position: relative;
  font-size: var(--fs-2xl);
  letter-spacing: var(--tracking-ink);
  margin: 0;
  padding-left: 14px;
}
.ph-title::before {
  content: '';
  position: absolute;
  left: 0;
  top: .18em;
  bottom: .18em;
  width: 3px;
  border-radius: 1px;
  background: var(--ember);
}
.ph-actions { display: flex; align-items: center; gap: var(--sp-2); flex: none; }

@media (max-width: 760px) {
  .ph { margin-bottom: var(--sp-4); align-items: flex-start; }
}
</style>
