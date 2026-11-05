<script setup lang="ts">
import { RouterLink } from 'vue-router'
import Icon from './Icon.vue'

withDefaults(defineProps<{
  items: Array<{ to: string; label: string; icon: string; active: boolean }>
  label?: string
}>(), { label: '主导航' })
</script>

<template>
  <nav class="bn" :aria-label="label">
    <RouterLink
      v-for="n in items"
      :key="n.to"
      :to="n.to"
      class="tab"
      :class="{ active: n.active }"
      :aria-current="n.active ? 'page' : undefined"
    >
      <span v-if="n.active" class="wick" aria-hidden="true" />
      <Icon :name="n.icon" :size="21" />
      <span class="lbl">{{ n.label }}</span>
    </RouterLink>
  </nav>
</template>

<style scoped>
.bn {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: var(--z-sticky);
  display: flex;
  align-items: stretch;
  background: var(--stone-300);
  background: color-mix(in srgb, var(--stone-300) 94%, transparent);
  backdrop-filter: saturate(1.3) blur(16px);
  -webkit-backdrop-filter: saturate(1.3) blur(16px);
  border-top: 1px solid var(--hairline);
  padding-bottom: var(--safe-b);
}

@media (min-width: 761px) {
  .bn { display: none; }
}

.tab {
  position: relative;
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  min-height: var(--tabbar-h);
  color: var(--ink-3);
  text-decoration: none;
  font-size: var(--fs-micro);
  letter-spacing: .02em;
  transition: color var(--dur-fast) var(--ease);
}
.tab:hover { text-decoration: none; color: var(--ink-2); }
.tab.active { color: var(--ember); }

.wick {
  position: absolute;
  top: 0;
  left: 50%;
  width: 22px;
  height: 2px;
  margin-left: -11px;
  border-radius: 0 0 2px 2px;
  background: var(--ember);
  box-shadow: 0 0 10px var(--ember-glow);
}
.lbl {
  line-height: 1;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 380px) {
  .tab { font-size: 10px; gap: 2px; }
}
</style>
