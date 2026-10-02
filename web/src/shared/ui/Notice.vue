<script setup lang="ts">
/**
 * 提示条。
 *
 * 抽它的原因：错误/成功提示原先在 12 个 view 里各写一份 `<p class="err">`，
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
  font-size: var(--fs-sm);
  line-height: 1.6;
}
.mark {
  flex: none;
  width: 3px;
  align-self: stretch;
  border-radius: 2px;
  background: currentColor;
  opacity: .9;
}
.body { min-width: 0; }

.t-error { color: var(--rust); background: var(--rust-veil); border-color: var(--rust-deep); }
.t-ok { color: var(--moss); background: var(--moss-veil); border-color: var(--moss-deep); }
.t-warn { color: var(--amber); background: var(--amber-veil); border-color: var(--amber); }
.t-info { color: var(--ink-dim); background: var(--surface-inset); }
</style>
