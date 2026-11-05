<script setup lang="ts">
import { RouterLink, type RouteLocationRaw } from 'vue-router'
import Icon from './Icon.vue'

withDefaults(defineProps<{
  title: string
  to?: RouteLocationRaw | null
  sub?: string
  meta?: string | number
  icon?: string
  chevron?: boolean
  monoMeta?: boolean
}>(), {
  to: null,
  sub: '',
  meta: '',
  icon: '',
  chevron: false,
  monoMeta: true,
})

defineEmits<{ click: [] }>()
</script>

<template>
  <component
    :is="to ? RouterLink : 'button'"
    :to="to || undefined"
    :type="to ? undefined : 'button'"
    class="lr"
    @click="to ? undefined : $emit('click')"
  >
    <span v-if="icon" class="icon" aria-hidden="true"><Icon :name="icon" :size="18" /></span>
    <span class="main">
      <span class="title">{{ title }}</span>
      <span v-if="sub" class="sub">{{ sub }}</span>
      <slot name="sub" />
    </span>
    <slot name="trailing">
      <span v-if="meta !== ''" class="meta" :class="{ num: monoMeta }">{{ meta }}</span>
      <Icon v-if="chevron" name="chevron-right" :size="16" class="go" />
    </slot>
  </component>
</template>

<style scoped>
.lr {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  width: 100%;
  min-height: 52px;
  padding: 10px var(--sp-3);
  background: none;
  border: 0;
  border-radius: var(--r-sm);
  text-align: left;
  text-decoration: none;
  color: inherit;
  font: inherit;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease);
}
.lr:hover { background: var(--surface-hover); text-decoration: none; }
.lr:active { background: var(--surface-active); }
.lr:focus-visible { outline: 2px solid var(--ember); outline-offset: -2px; }

.icon {
  flex: none;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: var(--r-sm);
  background: var(--stone-void);
  box-shadow: var(--bevel-inset);
  color: var(--ink-3);
}
.lr:hover .icon { color: var(--ember); }

.main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.title {
  font-size: var(--fs-base);
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.lr:hover .title { color: var(--ember-hot); }
.sub {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  line-height: 1.6;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.meta { flex: none; font-size: var(--fs-xs); color: var(--ink-3); }
.go { flex: none; color: var(--ink-4); transition: transform var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease); }
.lr:hover .go { color: var(--ember); transform: translateX(2px); }

@media (max-width: 760px) {
  .lr { min-height: 60px; padding: var(--sp-3) var(--sp-2); }
}
</style>
