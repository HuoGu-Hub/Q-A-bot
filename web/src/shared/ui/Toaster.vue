<script setup lang="ts">
/**
 * 软弹窗的容器。挂在管理端根组件下一次即可。
 *
 * <p>固定居中偏上，`pointer-events: none`（只有关闭按钮自己可点）——
 * 这样它永远不会挡住底下的操作，也不会因为占位把页面顶下去。
 */
import { dismiss, useToasts } from './toast'

const { items } = useToasts()
</script>

<template>
  <div class="toaster" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="t in items" :key="t.id" class="toast" :class="`t-${t.tone}`" role="status">
        <span class="mark" aria-hidden="true" />
        <span class="body">{{ t.text }}</span>
        <button type="button" class="x" title="关闭" @click="dismiss(t.id)">×</button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toaster {
  position: fixed;
  top: calc(var(--admin-bar-h) + var(--sp-5));
  left: 50%;
  transform: translateX(-50%);
  z-index: var(--z-modal, 300);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-2);
  pointer-events: none;
}
.toast {
  pointer-events: auto;
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  min-width: 220px;
  max-width: min(560px, 88vw);
  padding: var(--sp-3) var(--sp-4);
  border-radius: 4px;
  background: var(--surface-raised);
  border: 1px solid var(--edge);
  box-shadow: 0 8px 24px rgb(0 0 0 / 45%);
  font-size: var(--fs-body);
  color: var(--ink);
}
/* 左侧状态竖条 —— 与站内其它状态色用法一致 */
.mark { width: 2px; align-self: stretch; border-radius: 1px; background: var(--ink-dim); }
.t-ok .mark { background: var(--moss); }
.t-error .mark { background: var(--rust); }
.t-warn .mark { background: var(--flame); }
.t-info .mark { background: var(--mist); }
.body { flex: 1; line-height: 1.5; }
.x {
  flex: none;
  width: 20px;
  height: 20px;
  border: 0;
  background: transparent;
  color: var(--ink-dim);
  font-size: var(--fs-section);
  line-height: 1;
  cursor: pointer;
  border-radius: 3px;
}
.x:hover { background: var(--surface-hover); color: var(--ink); }

.toast-enter-active, .toast-leave-active { transition: opacity .18s ease, transform .18s ease; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(-6px); }
</style>
