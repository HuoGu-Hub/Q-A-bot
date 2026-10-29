<script setup lang="ts">
/**
 * 新建命令的类型选择。
 *
 * <p>板块和指令是同一张命令表里的不同形态，所以不再并排两个「新建」按钮；
 * 先选类型，再进对应编辑器。
 */
import Modal from '@shared/ui/Modal.vue'
import Icon from '@shared/ui/Icon.vue'

withDefaults(defineProps<{ open: boolean; showAction?: boolean }>(), { showAction: true })
const emit = defineEmits<{
  pick: [string]
  close: []
}>()
</script>

<template>
  <Modal :open="open" title="新建命令" width="520px" @close="emit('close')">
    <div class="type-list">
      <button type="button" class="type" @click="emit('pick', 'board')">
        <span class="type-name">板块</span>
        <Icon name="chevron-right" :size="15" class="type-arrow" />
      </button>
      <button type="button" class="type" @click="emit('pick', 'template')">
        <span class="type-name">话术</span>
        <Icon name="chevron-right" :size="15" class="type-arrow" />
      </button>
      <button type="button" class="type" @click="emit('pick', 'agent')">
        <span class="type-name">问答</span>
        <Icon name="chevron-right" :size="15" class="type-arrow" />
      </button>
      <button v-if="showAction" type="button" class="type" @click="emit('pick', 'action')">
        <span class="type-name">动作</span>
        <Icon name="chevron-right" :size="15" class="type-arrow" />
      </button>
    </div>
  </Modal>
</template>

<style scoped>
.type-list { border-top: 1px solid var(--hairline-lit); }
.type {
  display: grid;
  grid-template-columns: 1fr 18px;
  align-items: center;
  gap: var(--sp-3);
  width: 100%;
  min-height: 52px;
  padding: 0 6px;
  background: none;
  border: 0;
  border-bottom: 1px solid var(--hairline);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease);
}
.type:hover { background: var(--surface-hover); box-shadow: inset 2px 0 0 var(--ember); }
.type:hover .type-arrow { color: var(--ember); }
.type:focus-visible { outline: 2px solid var(--flame); outline-offset: -2px; }
.type-name { color: var(--ink); font-size: var(--fs-md); }
.type-arrow { color: var(--ink-4); transition: color var(--dur-fast) var(--ease); }
@media (prefers-reduced-motion: reduce) {
  .type, .type-arrow { transition: none; }
}
</style>
