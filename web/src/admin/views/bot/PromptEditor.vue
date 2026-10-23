<script setup lang="ts">
/**
 * /prompt 的编辑弹窗。
 *
 * 它不是普通命令编辑器：命令本身只有启用/停用与黑白名单，
 * 真正的内容是一组群提示和个人提示，所以单独列出、单独删除。
 */
import { computed, ref, watch } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { AdminPrompt, AdminPromptList, BotCommand, CommandAcl } from '@shared/api/types'
import CommandEditorShell from './CommandEditorShell.vue'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Button from '@shared/ui/Button.vue'
import Notice from '@shared/ui/Notice.vue'

const props = defineProps<{
  open: boolean
  command: BotCommand | null
}>()

const emit = defineEmits<{
  close: []
  changed: []
}>()

const prompts = ref<AdminPrompt[]>([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const aclSaving = ref(false)
const aclSaved = ref('')
const listReply = ref('')
const helpReply = ref('')
const textsSaving = ref(false)
const textsSaved = ref('')
const acl = ref({ whiteUsers: '', whiteGroups: '', blackUsers: '', blackGroups: '' })
const accessMode = ref('all')
const ACCESS_OPTIONS = [
  { value: 'all', label: '全体成员' },
  { value: 'whitelist', label: '仅白名单' },
  { value: 'blacklist', label: '黑名单拦截' },
]

const visiblePrompts = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return prompts.value
  return prompts.value.filter((p) =>
    p.content.toLowerCase().includes(q)
    || String(p.groupId).includes(q)
    || String(p.userId).includes(q))
})

const aclCount = computed(() => {
  const f = acl.value
  return [f.whiteUsers, f.whiteGroups, f.blackUsers, f.blackGroups].filter((s) => s.trim()).length
})

function fillAcl(source: CommandAcl) {
  const pick = (type: string) => source.white.filter((x) => x.type === type).map((x) => x.value).join('\n')
  const pickBlack = (type: string) => source.black.filter((x) => x.type === type).map((x) => x.value).join('\n')
  acl.value = {
    whiteUsers: pick('uid'),
    whiteGroups: pick('gid'),
    blackUsers: pickBlack('uid'),
    blackGroups: pickBlack('gid'),
  }
}

function aclBody() {
  const toItems = (text: string, type: 'uid' | 'gid') =>
    text.split(/[\s,，]+/).map((s) => s.trim()).filter(Boolean)
      .map((value) => ({ type, value: Number(value) }))
      .filter((x) => Number.isFinite(x.value) && x.value > 0)
  return {
    white: [...toItems(acl.value.whiteUsers, 'uid'), ...toItems(acl.value.whiteGroups, 'gid')],
    black: [...toItems(acl.value.blackUsers, 'uid'), ...toItems(acl.value.blackGroups, 'gid')],
  }
}

async function load() {
  if (!props.open || !props.command) return
  loading.value = true
  error.value = ''
  aclSaved.value = ''
  textsSaved.value = ''
  listReply.value = props.command.listReply ?? ''
  helpReply.value = props.command.help ?? ''
  try {
    const [list, aclData] = await Promise.all([
      adminApi.get<AdminPromptList>('/prompts'),
      adminApi.get<CommandAcl>('/commands/' + props.command.id + '/acl'),
    ])
    prompts.value = list.prompts
    fillAcl(aclData)
    accessMode.value = props.command.access || 'all'
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

watch(() => props.open, (open) => { if (open) load() })
watch(() => props.command?.id, () => { if (props.open) load() })

async function saveAcl() {
  if (!props.command) return
  aclSaving.value = true
  error.value = ''
  try {
    await adminApi.post('/commands/' + props.command.id + '/access', { access: accessMode.value })
    fillAcl(await adminApi.post<CommandAcl>('/commands/' + props.command.id + '/acl', aclBody()))
    aclSaved.value = '名单已保存'
    emit('changed')
    window.setTimeout(() => { aclSaved.value = '' }, 1600)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    aclSaving.value = false
  }
}

async function saveTexts() {
  if (!props.command) return
  textsSaving.value = true
  error.value = ''
  try {
    await adminApi.post('/commands/' + props.command.id + '/texts', {
      help: helpReply.value,
      listReply: listReply.value,
    })
    textsSaved.value = '回复已保存'
    emit('changed')
    window.setTimeout(() => { textsSaved.value = '' }, 1600)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    textsSaving.value = false
  }
}

async function deletePrompt(prompt: AdminPrompt) {
  if (!window.confirm('删除这条补充提示？')) return
  try {
    await adminApi.post('/prompts/delete', { id: prompt.id })
    prompts.value = prompts.value.filter((p) => p.id !== prompt.id)
    emit('changed')
  } catch (e) {
    error.value = describeError(e)
  }
}

function scopeText(prompt: AdminPrompt) {
  return prompt.scope === 'group' ? '本群 ' + prompt.groupId : 'QQ ' + prompt.userId
}
</script>

<template>
  <CommandEditorShell :open="open" title="补充提示" width="760px" @close="emit('close')">
    <template #actions>
      <Button size="sm" @click="emit('close')">关闭</Button>
    </template>

    <template #head>
      <div class="prompt-head">
        <div>
          <span class="prompt-command">/prompt</span>
          <span class="prompt-state">{{ command?.enabled ? '启用' : '停用' }}</span>
        </div>
        <div class="prompt-tools">
          <Input v-model="keyword" placeholder="搜索提示" aria-label="搜索提示" />
        </div>
      </div>
    </template>

    <div class="prompt-page">
      <Notice v-if="error" tone="error">{{ error }}</Notice>

      <section class="prompt-section">
        <header class="section-head">
          <h3>生效中的提示</h3>
          <span>{{ visiblePrompts.length }} 条</span>
        </header>

        <div v-if="loading" class="prompt-empty">正在读取…</div>
        <div v-else-if="visiblePrompts.length" class="prompt-list">
          <article v-for="p in visiblePrompts" :key="p.id" class="prompt-row">
            <span class="prompt-scope">{{ scopeText(p) }}</span>
            <p class="prompt-content">{{ p.content }}</p>
            <button type="button" class="prompt-remove" @click="deletePrompt(p)">删除</button>
          </article>
        </div>
        <p v-else class="prompt-empty">还没有补充提示。</p>
      </section>

      <section class="prompt-section">
        <header class="section-head">
          <h3>本命令名单</h3>
          <span>{{ aclCount ? aclCount + ' 组' : '未设置' }}</span>
        </header>

        <label class="access-row">
          <span>名单模式</span>
          <Select v-model="accessMode" :options="ACCESS_OPTIONS" aria-label="名单模式" />
        </label>

        <div class="acl-grid">
          <div>
            <div class="acl-k good">白名单 · QQ</div>
            <Input :model-value="acl.whiteUsers" multiline :rows="2" placeholder="一行一个"
              aria-label="prompt 白名单 QQ" @update:model-value="acl.whiteUsers = $event" />
          </div>
          <div>
            <div class="acl-k good">白名单 · 群</div>
            <Input :model-value="acl.whiteGroups" multiline :rows="2" placeholder="整群"
              aria-label="prompt 白名单群" @update:model-value="acl.whiteGroups = $event" />
          </div>
          <div>
            <div class="acl-k bad">黑名单 · QQ</div>
            <Input :model-value="acl.blackUsers" multiline :rows="2" placeholder="一行一个"
              aria-label="prompt 黑名单 QQ" @update:model-value="acl.blackUsers = $event" />
          </div>
          <div>
            <div class="acl-k bad">黑名单 · 群</div>
            <Input :model-value="acl.blackGroups" multiline :rows="2" placeholder="整群"
              aria-label="prompt 黑名单群" @update:model-value="acl.blackGroups = $event" />
          </div>
        </div>
        <div class="acl-actions">
          <span v-if="aclSaved" class="saved">{{ aclSaved }}</span>
          <Button size="sm" variant="primary" :disabled="aclSaving" @click="saveAcl">保存名单</Button>
        </div>
      </section>

      <section class="prompt-section">
        <header class="section-head">
          <h3>list 回复</h3>
          <span>{{ listReply.trim() ? '手写' : '自动' }}</span>
        </header>
        <Input
          v-model="listReply"
          multiline
          :rows="4"
          mono
          placeholder="留空自动，/提示词 /list 返回这段"
          aria-label="prompt list 回复"
        />
        <div class="reply-preview">
          <div class="reply-label">实际回复</div>
          <pre>{{ listReply.trim() || command?.defaultList || '保存后显示默认回复' }}</pre>
        </div>
      </section>

      <section class="prompt-section">
        <header class="section-head">
          <h3>help 回复</h3>
          <span>{{ helpReply.trim() ? '手写' : '全局 help' }}</span>
        </header>
        <Input
          v-model="helpReply"
          multiline
          :rows="4"
          mono
          placeholder="留空使用全局 help"
          aria-label="prompt help 回复"
        />
        <div class="reply-preview">
          <div class="reply-label">实际回复</div>
          <pre>{{ helpReply.trim() || command?.defaultHelp || '保存后显示全局 help' }}</pre>
        </div>
      </section>

      <div class="acl-actions">
        <span v-if="textsSaved" class="saved">{{ textsSaved }}</span>
        <Button size="sm" variant="primary" :disabled="textsSaving" @click="saveTexts">保存回复</Button>
      </div>
    </div>
  </CommandEditorShell>
</template>

<style scoped>
.prompt-page { display: flex; flex-direction: column; gap: var(--sp-5); }
.prompt-head { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-4); }
.prompt-command { color: var(--ember); font-family: var(--font-mono); font-size: var(--fs-md); }
.prompt-state { margin-left: 10px; color: var(--ink-4); font-size: var(--fs-xs); }
.prompt-tools :deep(.inp) { width: 220px; }

.prompt-section { display: flex; flex-direction: column; gap: var(--sp-2); }
.section-head {
  display: flex; align-items: baseline; justify-content: space-between;
  padding-bottom: 6px; border-bottom: 1px solid var(--hairline);
}
.section-head h3 { margin: 0; color: var(--ink-2); font-size: var(--fs-sm); font-weight: var(--fw-medium); }
.section-head span { color: var(--ink-4); font-family: var(--font-mono); font-size: 10.5px; }

.prompt-list { display: flex; flex-direction: column; }
.prompt-row {
  display: grid; grid-template-columns: 90px 1fr auto; gap: var(--sp-3);
  align-items: start; padding: 9px 4px; border-bottom: 1px solid var(--hairline);
}
.prompt-scope { color: var(--ink-4); font-family: var(--font-mono); font-size: 10.5px; white-space: nowrap; }
.prompt-content {
  margin: 0; color: var(--ink-2); font-size: var(--fs-meta);
  line-height: 1.6; overflow-wrap: anywhere; white-space: pre-wrap;
}
.prompt-remove {
  padding: 2px 6px; background: none; border: 1px solid transparent;
  border-radius: var(--r-xs); color: var(--blight-lift); font-size: 11px; cursor: pointer;
}
.prompt-remove:hover { border-color: var(--edge); background: var(--stone-500); }
.prompt-empty { margin: 0; padding: var(--sp-4) 0; color: var(--ink-4); font-size: var(--fs-meta); }

.access-row { display: flex; align-items: center; gap: var(--sp-3); margin-bottom: var(--sp-2); }
.access-row span { flex: 0 0 76px; color: var(--ink-3); font-size: var(--fs-meta); }
.access-row :deep(.sel) { flex: 1; }
.acl-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); }
.acl-k { margin-bottom: 5px; font-size: var(--fs-xs); letter-spacing: .04em; }
.acl-k.good { color: var(--vital); }
.acl-k.bad { color: var(--blight-lift); }
.acl-actions { display: flex; justify-content: flex-end; align-items: center; gap: var(--sp-3); }
.saved { color: var(--vital); font-size: var(--fs-xs); }
.reply-preview { display: flex; flex-direction: column; gap: 6px; }
.reply-label { color: var(--ink-4); font-size: 10.5px; letter-spacing: .12em; }
.reply-preview pre {
  max-height: 220px; margin: 0; padding: var(--sp-3); overflow: auto;
  white-space: pre-wrap; word-break: break-word;
  background: var(--stone-400); border: 1px solid var(--edge);
  border-radius: var(--r-sm); color: var(--ink-2);
  font-family: var(--font-mono); font-size: var(--fs-xs); line-height: 1.7;
}

@media (max-width: 700px) {
  .prompt-head { flex-direction: column; align-items: stretch; }
  .prompt-tools :deep(.inp) { width: 100%; }
  .prompt-row { grid-template-columns: 1fr auto; }
  .prompt-content { grid-column: 1 / -1; grid-row: 2; }
  .acl-grid { grid-template-columns: 1fr; }
}
</style>
