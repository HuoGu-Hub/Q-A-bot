<script setup lang="ts">
/**
 * 新建命令的类型选择。
 *
 * <p>板块和指令是同一张命令表里的不同形态，所以不再并排两个「新建」按钮；
 * 先选类型，再进对应编辑器。
 */
import Modal from '@shared/ui/Modal.vue'
import ActionOption from '@shared/ui/ActionOption.vue'

defineProps<{ open: boolean }>()
const emit = defineEmits<{
  pick: [string]
  close: []
}>()
</script>

<template>
  <Modal :open="open" title="新建命令" width="620px" @close="emit('close')">
    <p class="lead">
      板块也是命令的一种，只是多了一层投递方式。先选一种形态，后面都能改。
    </p>
    <div class="opts">
      <ActionOption
        title="板块"
        hint="收一条内容，立即展示或到点发送。公告、任务、开黑都是它。"
        @select="emit('pick', 'board')"
      />
      <ActionOption
        title="话术"
        hint="命中就回一段固定文本，不调模型、不计费。"
        @select="emit('pick', 'template')"
      />
      <ActionOption
        title="智能问答"
        hint="把尾部文本交给大模型，会走知识库与成本预算。"
        @select="emit('pick', 'agent')"
      />
      <ActionOption
        title="动作"
        hint="交给已注册的执行器写库或查库，不调模型。"
        @select="emit('pick', 'action')"
      />
    </div>
  </Modal>
</template>

<style scoped>
.lead {
  margin: 0 0 var(--sp-4);
  font-size: var(--fs-meta);
  color: var(--ink-3);
  line-height: 1.7;
}
.opts { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); }
@media (max-width: 560px) {
  .opts { grid-template-columns: 1fr; }
}
</style>
