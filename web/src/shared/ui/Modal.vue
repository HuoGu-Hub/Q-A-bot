<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import Icon from './Icon.vue'

const props = withDefaults(defineProps<{
  open: boolean
  title?: string
  ariaLabel?: string
  width?: string
}>(), { title: '', ariaLabel: '', width: '560px' })

const emit = defineEmits<{ close: [] }>()

const box = ref<HTMLElement | null>(null)
let lastActive: HTMLElement | null = null

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.stopPropagation()
    emit('close')
  }
}

function release() {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKey)
}

watch(() => props.open, async (open) => {
  if (open) {
    lastActive = document.activeElement instanceof HTMLElement ? document.activeElement : null
    document.body.style.overflow = 'hidden'
    window.addEventListener('keydown', onKey)
    await nextTick()
    box.value?.focus()
  } else {
    release()
    lastActive?.focus?.()
  }
})

onBeforeUnmount(release)
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="layer">
      <div class="mask" @click="emit('close')" />
      <div
        ref="box"
        class="box"
        role="dialog"
        aria-modal="true"
        :aria-label="title || ariaLabel || undefined"
        tabindex="-1"
        :style="{ width }"
      >
        <span class="grab" aria-hidden="true" />
        <header class="head">
          <span class="title">{{ title }}</span>
          <span class="head-spacer" />
          <div class="head-actions"><slot name="actions" /></div>
          <button type="button" class="x" aria-label="关闭" @click="emit('close')">
            <Icon name="close" :size="16" />
          </button>
        </header>
        <div class="body">
          <slot />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.layer {
  position: fixed;
  inset: 0;
  z-index: var(--z-drawer);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-4);
}
.mask {
  position: absolute;
  inset: 0;
  background: var(--backdrop-mask);
  backdrop-filter: blur(3px);
  -webkit-backdrop-filter: blur(3px);
}
.box {
  position: relative;
  max-width: 100%;
  max-height: min(84svh, 760px);
  display: flex;
  flex-direction: column;
  background: var(--stone-400);
  border: 1px solid var(--edge);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-pop);
  outline: none;
}
.grab { display: none; }
.head {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  border-bottom: 1px solid var(--hairline);
  min-height: 56px;
}
.head-spacer { flex: 1; }
.title { font-size: var(--fs-lg); font-weight: var(--fw-semi); }
.head-actions { display: flex; align-items: center; gap: var(--sp-2); flex: none; }
.x {
  flex: none;
  display: grid;
  place-items: center;
  margin-left: auto;
  width: 36px;
  height: 36px;
  background: none;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  color: var(--ink-3);
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease);
}
.x:hover { background: var(--stone-500); border-color: var(--edge); color: var(--ink); }
.body { padding: var(--sp-4); overflow: auto; overscroll-behavior: contain; }

@media (max-width: 560px) {
  .layer { align-items: flex-end; padding: 0; }
  .box {
    width: 100% !important;
    max-height: 88svh;
    border-radius: var(--r-lg) var(--r-lg) 0 0;
    border-bottom: 0;
    padding-bottom: var(--safe-b);
  }
  .grab {
    display: block;
    width: 40px;
    height: 4px;
    margin: 8px auto 0;
    border-radius: var(--r-pill);
    background: var(--edge-hover);
  }
  .head { padding-top: var(--sp-2); min-height: 48px; }
  .body { padding: var(--sp-4) var(--sp-4) var(--sp-5); }
}
</style>
