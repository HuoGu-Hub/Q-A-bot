<script setup lang="ts">
/**
 * 新建命令的类型选择。
 *
 * <p>板块和指令是同一张命令表里的不同形态，所以不再并排两个「新建」按钮；
 * 先选类型，再进对应编辑器。
 */
import Modal from '@shared/ui/Modal.vue'

defineProps<{ open: boolean }>()
const emit = defineEmits<{
  pick: [string]
  close: []
}>()
</script>

<template>
  <Modal :open="open" title="新建命令" width="660px" @close="emit('close')">
    <div class="type-grid">
      <button type="button" class="type" @click="emit('pick', 'board')">
        <span class="type-mark">#</span>
        <span class="type-name">板块</span>
        <span class="type-hint">记录、列表与定时发送</span>
      </button>
      <button type="button" class="type" @click="emit('pick', 'template')">
        <span class="type-mark">"</span>
        <span class="type-name">话术</span>
        <span class="type-hint">固定文本回复</span>
      </button>
      <button type="button" class="type" @click="emit('pick', 'agent')">
        <span class="type-mark">?</span>
        <span class="type-name">问答</span>
        <span class="type-hint">知识库回答</span>
      </button>
      <button type="button" class="type" @click="emit('pick', 'action')">
        <span class="type-mark">&gt;</span>
        <span class="type-name">动作</span>
        <span class="type-hint">调用已注册执行器</span>
      </button>
    </div>
  </Modal>
</template>

<style scoped>
.type-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  border-top: 1px solid var(--hairline-lit);
  border-left: 1px solid var(--hairline);
}
.type {
  display: grid;
  grid-template-columns: 42px 1fr;
  grid-template-rows: auto auto;
  column-gap: var(--sp-3);
  align-items: center;
  min-height: 118px;
  padding: var(--sp-4);
  background: transparent;
  border: 0;
  border-right: 1px solid var(--hairline);
  border-bottom: 1px solid var(--hairline);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease);
}
.type:hover { background: var(--surface-hover); }
.type:focus-visible { outline: 2px solid var(--flame); outline-offset: -2px; }
.type-mark {
  grid-row: 1 / 3;
  color: var(--ember);
  font-family: var(--font-mono);
  font-size: 25px;
  line-height: 1;
}
.type-name { color: var(--ink); font-size: var(--fs-md); }
.type-hint { color: var(--ink-3); font-size: var(--fs-xs); line-height: 1.5; }
@media (max-width: 560px) {
  .type-grid { grid-template-columns: 1fr; }
  .type { min-height: 92px; }
}
@media (prefers-reduced-motion: reduce) {
  .type { transition: none; }
}
</style>
