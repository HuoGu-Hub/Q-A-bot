<script setup lang="ts">
/**
 * 提示条。
 *
 * 抽它的原因：错误/成功提示原先在 12 个 view 里各写一份 <p class="err">，
 * 样式和语义都不统一（有的纯文字、有的加颜色、有的只是 faint）。
 * 而且纯文字提示在深底上容易被忽略 —— 失败和成功都需要一眼看见。
 */
withDefaults(
  defineProps<{
    tone?: 'error' | 'ok' | 'info' | 'warn'
  }>(),
  { tone: 'info' }
)
</script>

<template>
  <p class="notice" :class="`t-${tone}`" role="status">
    <span class="mark" aria-hidden="true" />
    <span class="body"><slot /></span>
  </p>
</template>

<style scoped>
.notice {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-3);
  margin: 0;
  padding: var(--sp-3) var(--sp-4);
  border-radius: var(--r-sm);
  border: 1px solid var(--edge-soft);
  font-size: var(--fs-base);
  line-height: 1.65;
}
.mark {
  flex: none;
  width: 3px;
  align-self: stretch;
  border-radius: var(--r-pill);
  background: currentColor;
  opacity: .85;
}
.body { min-width: 0; }

.t-error { color: var(--blight-lift); background: var(--blight-veil); border-color: color-mix(in srgb, var(--blight) 40%, transparent); }
.t-ok { color: var(--vital); background: var(--vital-veil); border-color: color-mix(in srgb, var(--vital) 40%, transparent); }
.t-warn { color: var(--warn); background: var(--warn-veil); border-color: color-mix(in srgb, var(--warn) 40%, transparent); }
.t-info { color: var(--ink-2); background: var(--stone-400); }
</style>
