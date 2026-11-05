<script setup lang="ts">
import Icon from './Icon.vue'
import { useTheme } from '@shared/theme/useTheme'

const { isDay, toggle } = useTheme()
</script>

<template>
  <button
    type="button"
    class="tt"
    :aria-label="isDay ? '切换到夜间模式' : '切换到日间模式'"
    :aria-pressed="isDay"
    :title="isDay ? '日间 · 点按切到夜间' : '夜间 · 点按切到日间'"
    @click="toggle"
  >
    <span class="ic" :class="{ on: isDay }" aria-hidden="true">
      <Icon :name="isDay ? 'moon' : 'sun'" :size="17" />
    </span>
  </button>
</template>

<style scoped>
.tt {
  flex: none;
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  background: var(--stone-400);
  color: var(--ink-2);
  cursor: pointer;
  box-shadow: var(--bevel-shelf);
  transition: background var(--dur-fast) var(--ease),
              border-color var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease);
}
.tt:hover {
  background: var(--stone-500);
  border-color: var(--edge-hover);
  color: var(--ember);
}
.tt:active { background: var(--stone-300); box-shadow: var(--bevel-inset); }

.ic { display: grid; place-items: center; transition: transform var(--dur) var(--ease); }
.ic.on { transform: rotate(-15deg); }

@media (max-width: 760px) {
  .tt { width: 38px; height: 38px; }
}
</style>
