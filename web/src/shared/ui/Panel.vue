<script setup lang="ts">
import { onMounted, ref } from 'vue'
import Icon from './Icon.vue'

const props = withDefaults(
  defineProps<{
    title?: string
    hint?: string
    count?: number | string
    corners?: boolean
    stone?: boolean
    sunken?: boolean
    glow?: boolean
    collapsible?: boolean
    defaultOpen?: boolean
    mobileCollapsed?: boolean
    dense?: boolean
  }>(),
  {
    corners: false,
    stone: false,
    sunken: false,
    glow: false,
    collapsible: false,
    defaultOpen: true,
    mobileCollapsed: false,
    dense: false,
  }
)

const open = ref(props.collapsible ? props.defaultOpen : true)

onMounted(() => {
  if (props.collapsible && props.mobileCollapsed && window.matchMedia('(max-width: 760px)').matches) {
    open.value = false
  }
})

function toggle() {
  if (props.collapsible) open.value = !open.value
}
</script>

<template>
  <section
    class="panel texture-noise"
    :class="[
      corners && !sunken ? 'panel--corners' : '',
      stone ? 'panel--stone' : '',
      sunken ? 'panel--sunken' : '',
      glow ? 'glow-flame' : '',
      collapsible ? 'panel--collapsible' : '',
      collapsible && !open ? 'panel--closed' : '',
      dense ? 'panel--dense' : '',
    ]"
  >
    <h2 v-if="title" class="panel-title">
      <button
        v-if="collapsible"
        type="button"
        class="head head--btn"
        :aria-expanded="open"
        @click="toggle"
      >
        <span class="chev" :class="{ 'chev--open': open }" aria-hidden="true">
          <Icon name="chevron-right" :size="15" />
        </span>
        <span class="head-text">{{ title }}</span>
        <span v-if="hint" class="head-hint">{{ hint }}</span>
        <span v-if="count !== undefined && count !== ''" class="head-count num">{{ count }}</span>
      </button>
      <div v-else class="head">
        <span class="head-text">{{ title }}</span>
        <span v-if="hint" class="head-hint">{{ hint }}</span>
        <span v-if="count !== undefined && count !== ''" class="head-count num">{{ count }}</span>
      </div>
      <slot name="actions" />
    </h2>
    <div v-show="open" class="panel-body">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.panel-title {
  font-family: var(--font-body);
  font-size: var(--fs-section);
  font-weight: var(--fw-semi);
  letter-spacing: .01em;
  margin: 0 0 var(--sp-4);
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  min-height: 28px;
}
.panel--collapsible .panel-title { margin-bottom: 0; }
.panel--collapsible .panel-body { margin-top: var(--sp-4); }
.panel--closed { padding-top: var(--sp-3); padding-bottom: var(--sp-3); }

.head {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex: 1;
  min-width: 0;
}
.head--btn {
  background: none;
  border: 0;
  padding: var(--sp-2);
  margin: calc(var(--sp-2) * -1);
  font: inherit;
  color: inherit;
  cursor: pointer;
  text-align: left;
  border-radius: var(--r-sm);
  transition: background var(--dur-fast) var(--ease);
}
.head--btn:hover {
  background: var(--surface-hover);
  box-shadow: inset 0 0 0 1px var(--edge-soft);
}
.head--btn:hover .head-text { color: var(--ember-hot); }
.head--btn:active { background: var(--surface-active); }
.head--btn:focus-visible { outline: 2px solid var(--ember); outline-offset: 3px; }

.head-text {
  position: relative;
  padding-left: var(--sp-4);
  white-space: nowrap;
  transition: color var(--dur-fast) var(--ease);
}
.head-text::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 15px;
  border-radius: 1px;
  background: var(--ember);
}

.chev {
  flex: none;
  display: grid;
  place-items: center;
  width: 15px;
  color: var(--ink-4);
  transition: transform var(--dur) var(--ease), color var(--dur-fast) var(--ease);
}
.chev--open { transform: rotate(90deg); color: var(--ember); }
.head--btn:hover .chev { color: var(--ember); }

.head-hint {
  font-size: var(--fs-meta);
  font-weight: var(--fw-normal);
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.head-count {
  flex: none;
  margin-left: auto;
  font-size: var(--fs-meta);
  font-weight: var(--fw-normal);
  color: var(--ink-2);
  background: var(--stone-void);
  border-radius: var(--r-pill);
  padding: 1px 9px;
  box-shadow: var(--bevel-inset);
}

/* dense：设置页的卡片，压低标题与内边距，给网格让位 */
.panel--dense { padding: 14px 16px; border-radius: var(--r-sm); }
.panel--dense .panel-title {
  margin-bottom: var(--sp-3);
  min-height: 22px;
  font-size: var(--fs-base);
}
.panel--dense.panel--collapsible .panel-title { margin-bottom: 0; }
.panel--dense.panel--collapsible .panel-body { margin-top: var(--sp-3); }
.panel--dense.panel--closed { padding-top: var(--sp-3); padding-bottom: var(--sp-3); }
.panel--dense .head-text { padding-left: var(--sp-3); }
.panel--dense .head-text::before { height: 12px; }
.panel--dense .head-count { font-size: var(--fs-micro); padding: 0 7px; }

@media (max-width: 760px) {
  .panel { padding: var(--sp-4); }
  .head--btn { min-height: 44px; }
  .head-hint { display: none; }
}
</style>
