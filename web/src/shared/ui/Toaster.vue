<script setup lang="ts">
/**
 * 软弹窗的容器。挂在管理端根组件下一次即可。
 *
 * 固定居中偏上，pointer-events: none（只有关闭按钮自己可点）——
 * 这样它永远不会挡住底下的操作，也不会因为占位把页面顶下去。
 */
import Icon from './Icon.vue'
import { dismiss, useToasts } from './toast'

const { items } = useToasts()
</script>

<template>
  <!--
    Teleport 到 body：.admin 自己是一个 z-index: 2 的 stacking context，
    toast 留在里面时 z-index 再高也只能压过页面，压不过同样挂到 body 的 Modal。
  -->
  <Teleport to="body">
    <div class="toaster" aria-live="polite">
      <TransitionGroup name="toast">
        <div v-for="t in items" :key="t.id" class="toast" :class="'t-' + t.tone" role="status">
          <span class="mark" aria-hidden="true" />
          <span class="body">{{ t.text }}</span>
          <button type="button" class="x" aria-label="关闭" @click="dismiss(t.id)">
            <Icon name="close" :size="14" />
          </button>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.toaster {
  position: fixed;
  top: calc(var(--admin-bar-h, 56px) + var(--sp-5));
  left: 50%;
  transform: translateX(-50%);
  z-index: var(--z-toast);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-2);
  pointer-events: none;
  width: max-content;
  max-width: 100%;
}
.toast {
  pointer-events: auto;
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  min-width: 220px;
  max-width: min(560px, 92vw);
  padding: var(--sp-3) var(--sp-4);
  border-radius: var(--r-md);
  background: var(--stone-400);
  border: 1px solid var(--edge);
  box-shadow: var(--shadow-pop);
  font-size: var(--fs-sm);
  color: var(--ink);
}
/* 左侧状态竖条 —— 与站内其它状态色用法一致 */
.mark { width: 3px; align-self: stretch; border-radius: var(--r-pill); background: var(--ink-3); }
.t-ok .mark { background: var(--vital); }
.t-error .mark { background: var(--blight); }
.t-warn .mark { background: var(--ember); }
.t-info .mark { background: var(--mist); }
.body { flex: 1; line-height: 1.5; }
.x {
  flex: none;
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border: 0;
  background: transparent;
  color: var(--ink-3);
  cursor: pointer;
  border-radius: var(--r-sm);
}
.x:hover { background: var(--stone-500); color: var(--ink); }

.toast-enter-active, .toast-leave-active { transition: opacity .18s ease, transform .18s ease; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(-8px); }

@media (max-width: 760px) {
  .toaster { top: calc(var(--admin-bar-h, 0px) + var(--sp-3)); }
  .toast { min-width: 0; }
}
</style>
