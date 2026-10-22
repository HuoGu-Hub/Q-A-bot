<script setup lang="ts">
/**
 * 命令编辑器外壳。
 *
 * 普通命令、板块、/prompt 都用同一个弹窗容器、同一套标题/关闭/保存动作。
 * 具体字段由各编辑器通过默认插槽提供，避免三套页面各写一套壳。
 */
import Modal from '@shared/ui/Modal.vue'
import Button from '@shared/ui/Button.vue'

withDefaults(defineProps<{
  open: boolean
  title: string
  width?: string
  saving?: boolean
  saveLabel?: string
}>(), { width: '980px', saving: false, saveLabel: '保存' })

const emit = defineEmits<{ close: []; save: [] }>()
</script>

<template>
  <Modal :open="open" :title="title" :width="width" @close="emit('close')">
    <template #actions>
      <slot name="actions">
        <Button size="sm" @click="emit('close')">取消</Button>
        <Button size="sm" variant="primary" :disabled="saving" @click="emit('save')">
          {{ saving ? '保存中…' : saveLabel }}
        </Button>
      </slot>
    </template>

    <div class="editor-shell">
      <header v-if="$slots.head" class="shell-head">
        <slot name="head" />
      </header>
      <slot />
    </div>
  </Modal>
</template>

<style scoped>
.editor-shell { display: flex; flex-direction: column; gap: var(--sp-4); }
.shell-head {
  padding-bottom: var(--sp-3);
  border-bottom: 1px solid var(--hairline-lit);
}
</style>
