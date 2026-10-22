<script setup lang="ts">
/**
 * 系统命令的中文别名。
 *
 * 只给 list / help / prompt 这类由代码处理的系统命令声明调用名；
 * 别名不能和现有命令、板块重名，解析发生在命令分派之前。
 */
import { ref, watch } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { SystemAliasEntry, SystemAliasList } from '@shared/api/types'
import Modal from '@shared/ui/Modal.vue'
import Input from '@shared/ui/Input.vue'
import Button from '@shared/ui/Button.vue'
import Notice from '@shared/ui/Notice.vue'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; changed: [] }>()

const rows = ref<Array<SystemAliasEntry & { text: string }>>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const saved = ref('')

async function load() {
  if (!props.open) return
  loading.value = true
  error.value = ''
  saved.value = ''
  try {
    const data = await adminApi.get<SystemAliasList>('/system-aliases')
    rows.value = data.commands.map((c) => ({ ...c, text: c.aliases.join(' ') }))
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

watch(() => props.open, (open) => { if (open) load() })

function splitAliases(text: string) {
  return text.split(/[\s,，]+/).map((s) => s.trim().replace(/^\//, '')).filter(Boolean)
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    await adminApi.post('/system-aliases', {
      commands: rows.value.map((r) => ({ target: r.target, aliases: splitAliases(r.text) })),
    })
    saved.value = '已保存'
    emit('changed')
    window.setTimeout(() => { saved.value = '' }, 1600)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Modal :open="open" title="系统命令别名" width="560px" @close="emit('close')">
    <div class="alias-page">
      <Notice v-if="error" tone="error">{{ error }}</Notice>
      <p class="alias-hint">写中文调用名，多个用空格或逗号分开。解析后会走原命令的权限与开关。</p>

      <div v-if="loading" class="alias-empty">正在读取…</div>
      <div v-else class="alias-list">
        <label v-for="row in rows" :key="row.target" class="alias-row">
          <span class="alias-cmd">/{{ row.target }}</span>
          <span class="alias-label">{{ row.label }}</span>
          <Input
            v-model="row.text"
            :placeholder="row.target === 'list' ? '列表' : row.target === 'help' ? '帮助' : '提示'"
            :aria-label="row.target + ' 的别名'"
          />
        </label>
      </div>

      <div class="alias-actions">
        <span v-if="saved" class="saved">{{ saved }}</span>
        <Button variant="primary" :disabled="saving || loading" @click="save">保存别名</Button>
      </div>
    </div>
  </Modal>
</template>

<style scoped>
.alias-page { display: flex; flex-direction: column; gap: var(--sp-4); }
.alias-hint { margin: 0; color: var(--ink-3); font-size: var(--fs-meta); line-height: 1.6; }
.alias-list { display: flex; flex-direction: column; border-top: 1px solid var(--hairline); }
.alias-row {
  display: grid; grid-template-columns: 72px 100px 1fr;
  align-items: center; gap: var(--sp-3);
  padding: 10px 0; border-bottom: 1px solid var(--hairline);
}
.alias-cmd { color: var(--ember); font-family: var(--font-mono); font-size: var(--fs-sm); }
.alias-label { color: var(--ink-3); font-size: var(--fs-xs); }
.alias-empty { padding: var(--sp-5) 0; color: var(--ink-4); }
.alias-actions { display: flex; justify-content: flex-end; align-items: center; gap: var(--sp-3); }
.saved { color: var(--vital); font-size: var(--fs-xs); }

@media (max-width: 600px) {
  .alias-row { grid-template-columns: 1fr; gap: 4px; }
}
</style>
