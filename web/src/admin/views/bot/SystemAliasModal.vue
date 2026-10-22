<script setup lang="ts">
/**
 * 命令别名。
 *
 * 给任意规范命令声明中文调用名：系统命令、板块、板块动作、普通命令。
 * 别名在分派前展开，因此 /新增预约 和 /预约开黑 /create 走同一条路径。
 */
import { ref, watch } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { SystemAliasGroup, SystemAliasList } from '@shared/api/types'
import Modal from '@shared/ui/Modal.vue'
import Input from '@shared/ui/Input.vue'
import Button from '@shared/ui/Button.vue'
import Notice from '@shared/ui/Notice.vue'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; changed: [] }>()

interface EditItem { kind: 'command' | 'action'; target: string; label: string; text: string }
interface EditGroup { label: string; items: EditItem[] }

const groups = ref<EditGroup[]>([])
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
    groups.value = data.groups.map((g: SystemAliasGroup) => ({
      label: g.label,
      items: g.items.map((item) => ({
        kind: item.kind,
        target: item.target,
        label: item.label,
        text: item.aliases.join(' '),
      })),
    }))
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
      entries: groups.value.flatMap((g) => g.items.map((item) => ({
        kind: item.kind,
        target: item.target,
        aliases: splitAliases(item.text),
      }))),
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

function commandText(item: EditItem) {
  return item.kind === 'action' ? '/' + item.target : '/' + item.target.replaceAll('/', ' /')
}
</script>

<template>
  <Modal :open="open" title="命令别名" width="680px" @close="emit('close')">
    <div class="alias-page">
      <Notice v-if="error" tone="error">{{ error }}</Notice>
      <p class="alias-hint">命令名和动作词分开声明。动作词只配一次，所有模块共用，例如“新建”对 /prompt /create 和 /预约 /create 都生效。</p>

      <div v-if="loading" class="alias-empty">正在读取…</div>
      <div v-else class="alias-groups">
        <section v-for="group in groups" :key="group.label" class="alias-group">
          <h3>{{ group.label }}</h3>
          <label v-for="item in group.items" :key="item.target" class="alias-row">
            <span class="alias-cmd">{{ commandText(item) }}</span>
            <span class="alias-label">{{ item.label }}</span>
            <Input
              v-model="item.text"
              :placeholder="item.kind === 'action' ? '如 新建' : '如 列表'"
              :aria-label="item.kind + ' ' + item.target + ' 的别名'"
            />
          </label>
        </section>
      </div>

      <div class="alias-actions">
        <span v-if="saved" class="saved">{{ saved }}</span>
        <Button variant="primary" :disabled="saving || loading" @click="save">保存别名</Button>
      </div>
    </div>
  </Modal>
</template>

<style scoped>
.alias-page { display: flex; flex-direction: column; gap: var(--sp-3); max-height: 70vh; }
.alias-hint { margin: 0; color: var(--ink-3); font-size: var(--fs-meta); line-height: 1.6; }
.alias-groups { overflow: auto; padding-right: 4px; }
.alias-group { display: flex; flex-direction: column; }
.alias-group h3 {
  position: sticky; top: 0; z-index: 1;
  margin: 0; padding: 8px 0 6px;
  background: var(--surface);
  border-bottom: 1px solid var(--hairline);
  color: var(--ink-4);
  font-size: 10.5px;
  font-weight: var(--fw-medium);
  letter-spacing: .14em;
}
.alias-row {
  display: grid; grid-template-columns: minmax(120px, 1.3fr) minmax(80px, .7fr) minmax(150px, 1.6fr);
  align-items: center; gap: var(--sp-3);
  padding: 7px 0; border-bottom: 1px solid var(--hairline);
}
.alias-cmd { overflow: hidden; color: var(--ember); font-family: var(--font-mono); font-size: var(--fs-xs); text-overflow: ellipsis; white-space: nowrap; }
.alias-label { color: var(--ink-3); font-size: var(--fs-xs); }
.alias-empty { padding: var(--sp-5) 0; color: var(--ink-4); }
.alias-actions { display: flex; justify-content: flex-end; align-items: center; gap: var(--sp-3); }
.saved { color: var(--vital); font-size: var(--fs-xs); }

@media (max-width: 700px) {
  .alias-row { grid-template-columns: 1fr; gap: 4px; }
}
</style>
