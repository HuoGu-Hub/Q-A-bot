<script setup lang="ts">
/**
 * 全局 /list、/help 的回复编辑。
 *
 * 留空 = 继续用自动目录；编辑后群里发 /list 或 /help 直接返回这段文本。
 * 下方预览永远显示最终会发出去的内容，空内容时就是当前自动生成版本。
 */
import { computed, ref, watch } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { SystemReplyList } from '@shared/api/types'
import CommandEditorShell from './CommandEditorShell.vue'
import Input from '@shared/ui/Input.vue'
import Notice from '@shared/ui/Notice.vue'

const props = defineProps<{ open: boolean; kind: 'list' | 'help' }>()
const emit = defineEmits<{ close: []; changed: [] }>()

const content = ref('')
const defaultContent = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')

const title = computed(() => props.kind === 'list' ? '编辑 /list 回复' : '编辑 /help 回复')
const preview = computed(() => content.value.trim() || defaultContent.value)

async function load() {
  if (!props.open) return
  loading.value = true
  error.value = ''
  try {
    const data = await adminApi.get<SystemReplyList>('/system-replies')
    const item = data[props.kind]
    content.value = item.content
    defaultContent.value = item.defaultContent
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

watch(() => props.open, (open) => { if (open) load() }, { immediate: true })

async function save() {
  saving.value = true
  error.value = ''
  try {
    await adminApi.post('/system-replies', { key: props.kind, content: content.value })
    emit('changed')
    emit('close')
  } catch (e) {
    error.value = describeError(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <CommandEditorShell
    :open="open"
    :title="title"
    width="760px"
    :saving="saving"
    @close="emit('close')"
    @save="save"
  >
    <template #head>
      <div class="reply-head">
        <span class="reply-command">/{{ kind }}</span>
        <span class="reply-note">留空使用自动生成</span>
      </div>
    </template>

    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <div v-if="loading" class="reply-empty">正在读取…</div>
    <template v-else>
      <Input
        v-model="content"
        multiline
        :rows="10"
        mono
        :placeholder="kind === 'list' ? '自定义 /list 回复' : '自定义 /help 回复'"
        :aria-label="kind + ' 回复'"
      />
      <div class="reply-preview">
        <div class="preview-label">实际回复</div>
        <pre>{{ preview }}</pre>
      </div>
    </template>
  </CommandEditorShell>
</template>

<style scoped>
.reply-head { display: flex; align-items: baseline; gap: var(--sp-3); }
.reply-command { color: var(--ember); font-family: var(--font-mono); font-size: var(--fs-md); }
.reply-note { color: var(--ink-4); font-size: var(--fs-xs); }
.reply-empty { color: var(--ink-4); }
.reply-preview { display: flex; flex-direction: column; gap: 6px; }
.preview-label { color: var(--ink-4); font-size: 10.5px; letter-spacing: .12em; }
.reply-preview pre {
  max-height: 220px; margin: 0; padding: var(--sp-3);
  overflow: auto; white-space: pre-wrap; word-break: break-word;
  background: var(--stone-400); border: 1px solid var(--edge);
  border-radius: var(--r-sm); color: var(--ink-2);
  font-family: var(--font-mono); font-size: var(--fs-xs); line-height: 1.7;
}
</style>
