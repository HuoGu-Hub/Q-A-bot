<script setup lang="ts">
/**
 * 板块编辑器 —— 与指令编辑器同一套两栏布局，整块替换列表。
 *
 * <p>命令名就是群里的调用名：没有单独的"显示名"。自建命令可以改名，
 * 历史记录会在同一事务里迁到新名字下；内置命令的路径是代码约定，不能改。
 */
import { computed, reactive, ref, watch } from 'vue'
import type { AdminBoard, AdminParamSpec, BoardDraft } from '@shared/api/types'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Button from '@shared/ui/Button.vue'
import Notice from '@shared/ui/Notice.vue'
import ParamTable from './ParamTable.vue'
import { ACCESS_OPTIONS, DATA_SCOPES, DELIVERY_OPTIONS, SCOPE_LABEL } from './commandOptions'

const props = defineProps<{
  /** null = 新建 */
  board: AdminBoard | null
  saving: boolean
  error: string
}>()

const emit = defineEmits<{
  save: [BoardDraft]
  cancel: []
  remove: [AdminBoard]
}>()

interface FormState {
  path: string
  params: AdminParamSpec[]
  delivery: string
  access: string
  scope: string
  showGroup: boolean
  showUser: boolean
  allowPrivate: boolean
  enabled: boolean
  listHeader: string
  listItem: string
  listEmpty: string
  listLimit: number
}

const form = reactive<FormState>({
  path: '', params: [], delivery: 'direct', access: 'all', scope: 'GROUP',
  showGroup: false, showUser: false, allowPrivate: false, enabled: true,
  listHeader: '', listItem: '', listEmpty: '', listLimit: 20,
})
const groupsText = ref('')
const openParams = ref(true)

const isEditing = computed(() => props.board !== null)
/** 内置命令的路径由代码约定，不能改名；自建命令可以改名并迁移记录 */
const pathLocked = computed(() => props.board?.builtin === true)

const hasRepeat = computed(() => form.params.some((p) => p.name === '重复'))
const hasTime = computed(() => form.params.some((p) => p.name === '时间'))
const scheduled = computed(() => form.delivery === 'scheduled')

function reset() {
  const b = props.board
  form.path = b?.path ?? ''
  form.params = (b?.params ?? []).map((p) => ({ ...p }))
  form.delivery = b?.delivery ?? 'direct'
  form.access = b?.access ?? 'all'
  form.scope = b?.scope ?? 'GROUP'
  form.showGroup = b?.showGroup ?? false
  form.showUser = b?.showUser ?? false
  form.allowPrivate = b?.allowPrivate ?? false
  form.enabled = b?.enabled ?? true
  form.listHeader = b?.listHeader ?? ''
  form.listItem = b?.listItem ?? ''
  form.listEmpty = b?.listEmpty ?? ''
  form.listLimit = b?.listLimit ?? 20
  groupsText.value = (b?.groups ?? []).join('\n')
  openParams.value = true
}

watch(() => props.board, reset, { immediate: true })

/** 到点发送的两个约定参数：缺哪个补哪个，已有的不动 */
function ensureScheduleParams() {
  if (!hasRepeat.value) {
    form.params.push({
      name: '重复', type: 'text', required: true, example: '每天',
      description: '一次 / 每天 / 每周三 / 每3天', multiple: false,
    })
  }
  if (!hasTime.value) {
    form.params.push({
      name: '时间', type: 'moment', required: true, example: '21:30',
      description: '几点发，可以写多个，用顿号隔开', multiple: true,
    })
  }
}

interface PreviewRecord {
  id: number
  when: string
  text: string
  nickname: string
  group: string
  params: Record<string, string>
}

const previewRecords: PreviewRecord[] = [
  { id: 1, when: '立即展示', text: '今晚8点缺打野', nickname: '阿明', group: '123456', params: { 时间: '今晚8点', 人数: '5' } },
  { id: 2, when: '立即展示', text: '明天8点满员', nickname: '小红', group: '123456', params: { 时间: '明天8点', 人数: '10' } },
]

function renderTemplate(template: string, vars: Record<string, string>): string {
  return template.replace(/\{([^{}]+)}/g, (all, raw) => {
    const key = String(raw).trim()
    return Object.prototype.hasOwnProperty.call(vars, key) ? vars[key] : all
  })
}

function previewVars(record: PreviewRecord, count: string): Record<string, string> {
  const vars: Record<string, string> = {
    id: String(record.id),
    when: record.when,
    text: record.text,
    nickname: record.nickname,
    group: record.group,
    count,
    path: form.path || '命令',
  }
  for (const [key, value] of Object.entries(record.params)) vars['params.' + key] = value
  return vars
}

const previewText = computed(() => {
  const path = form.path || '命令'
  const header = form.listHeader.trim()
    ? renderTemplate(form.listHeader, { count: String(previewRecords.length), path })
    : `「${path}」共 ${previewRecords.length} 条：`
  const items = previewRecords.map((record) => form.listItem.trim()
    ? renderTemplate(form.listItem, previewVars(record, String(previewRecords.length)))
    : `#${record.id}  ${record.when}  ${record.text}`)
  return [header, ...items].join('\n')
})

const previewEmpty = computed(() => {
  const path = form.path || '命令'
  return form.listEmpty.trim()
    ? renderTemplate(form.listEmpty, { count: '0', path })
    : `「${path}」还没有内容。要添加就用 /${path} /create`
})

function onScopeChange(value: string) {
  form.scope = value
  if (value === 'GROUP') {
    form.showGroup = false
    form.showUser = false
  }
}

function parseIds(text: string): number[] {
  return text.split(/[\s,，、]+/).map((s) => s.trim()).filter(Boolean)
    .map(Number).filter((n) => Number.isFinite(n) && n > 0)
}

function submit() {
  const draft: BoardDraft = {
    id: props.board?.id ?? 0,
    path: form.path.trim(),
    // 命令名就是调用名，title 只作为历史字段跟着 path 走
    title: form.path.trim(),
    params: form.params.map((p) => ({ ...p })),
    delivery: form.delivery,
    access: form.access,
    scope: form.scope,
    groups: parseIds(groupsText.value),
    showGroup: form.showGroup,
    showUser: form.showUser,
    allowPrivate: form.allowPrivate,
    enabled: form.enabled,
    sortOrder: props.board?.sortOrder ?? 0,
    builtin: props.board?.builtin ?? false,
    usage: props.board?.usage ?? '',
    listHeader: form.listHeader.trim(),
    listItem: form.listItem.trim(),
    listEmpty: form.listEmpty.trim(),
    listLimit: form.listLimit,
    originalPath: props.board?.path ?? null,
  }
  emit('save', draft)
}
</script>

<template>
  <section class="editor">
    <div class="editor-head">
      <div>
        <div class="editor-eyebrow">编辑命令 · 板块</div>
        <div class="editor-path">/{{ form.path || '新板块' }}</div>
      </div>
      <div class="editor-actions">
        <Button size="sm" @click="emit('cancel')">取消</Button>
        <Button size="sm" variant="primary" :disabled="saving" @click="submit">
          {{ saving ? '保存中…' : '保存' }}
        </Button>
      </div>
    </div>

    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <div class="editor-grid">
      <section class="editor-col">
        <div class="col-title">内容</div>

        <label class="row">
          <span class="row-label">命令名</span>
          <Input
            v-model="form.path"
            :disabled="pathLocked"
            placeholder="公告（群里的调用名，不带斜杠）"
            aria-label="命令名"
          />
        </label>
        <p class="path-hint">
          群友发 <span class="path-demo">/{{ form.path || '…' }}</span> 查看，
          发 <span class="path-demo">/{{ form.path || '…' }} /create</span> 添加。
          命令名就是调用名。
          <template v-if="pathLocked">内置命令不能改名。</template>
          <template v-else-if="isEditing">改名会把已有记录一起迁到新名字下。</template>
        </p>

        <div class="block">
          <button class="block-head" type="button" @click="openParams = !openParams">
            <span class="caret">{{ openParams ? '−' : '+' }}</span>
            参数表
            <span class="faint">{{ form.params.length ? form.params.length + ' 个' : '没有' }}</span>
          </button>
          <div v-if="openParams" class="block-body">
            <ParamTable
              :params="form.params"
              empty-hint="没有参数时，整段文本就是这一条的内容。"
              @update:params="form.params = $event"
            />
            <div v-if="scheduled" class="schedule-hint">
              <span>到点发送需要 <span class="path-demo">#重复#</span> 与 <span class="path-demo">#时间#</span> 两个参数。</span>
              <Button v-if="!hasRepeat || !hasTime" size="sm" @click="ensureScheduleParams">补上定时参数</Button>
            </div>
          </div>
        </div>

        <div class="format-block">
          <div class="col-title">列表格式</div>
          <label class="row">
            <span class="row-label">列表头</span>
            <Input
              v-model="form.listHeader"
              placeholder="预约开黑 · 共 {count} 条"
              aria-label="列表头"
            />
          </label>
          <label class="row row--block">
            <span class="row-label">每条</span>
            <Input
              v-model="form.listItem"
              multiline
              :rows="2"
              placeholder="#{id} {params.时间} {text}（{nickname}）"
              aria-label="每条格式"
            />
          </label>
          <label class="row row--block">
            <span class="row-label">空列表</span>
            <Input
              v-model="form.listEmpty"
              :placeholder="'还没有内容，发 /' + (form.path || '命令') + ' /create 添加'"
              aria-label="空列表文案"
            />
          </label>
          <label class="row">
            <span class="row-label">显示条数</span>
            <div class="limit-line">
              <Input
                :model-value="String(form.listLimit)"
                type="number"
                aria-label="显示条数"
                @update:model-value="form.listLimit = Math.max(0, Math.min(200, Number($event) || 0))"
              />
              <span class="faint">0 = 全部，最多 200</span>
            </div>
          </label>
          <p class="format-hint">
            列表头可用 <code>{count}</code> <code>{path}</code>；每条可用
            <code>{id}</code> <code>{when}</code> <code>{text}</code>
            <code>{nickname}</code> <code>{group}</code>
            <code>{params.参数名}</code>。不认识的变量会原样保留。
          </p>
          <div class="preview-box">
            <div class="col-title">预览</div>
            <pre class="format-preview">{{ previewText }}</pre>
            <div class="faint preview-empty">空列表：{{ previewEmpty }}</div>
          </div>
        </div>
      </section>

      <section class="editor-col">
        <div class="col-title">规则</div>

        <label class="row">
          <span class="row-label">投递方式</span>
          <Select v-model="form.delivery" :options="DELIVERY_OPTIONS" aria-label="投递方式" />
        </label>

        <label class="row">
          <span class="row-label">谁能用</span>
          <Select v-model="form.access" :options="ACCESS_OPTIONS" aria-label="谁能用" />
        </label>

        <label class="row">
          <span class="row-label">数据范围</span>
          <Select :model-value="form.scope" :options="DATA_SCOPES" aria-label="数据范围" @update:model-value="onScopeChange" />
        </label>

        <div v-if="form.scope === 'SOME'" class="row row--block">
          <span class="row-label">共享群</span>
          <Input
            v-model="groupsText"
            multiline
            :rows="2"
            placeholder="一行一个群号"
            aria-label="共享群号"
          />
        </div>

        <div class="checks">
          <label class="check">
            <input v-model="form.allowPrivate" type="checkbox" />
            <span>允许私聊</span>
          </label>
          <template v-if="form.scope !== 'GROUP'">
            <label class="check">
              <input v-model="form.showGroup" type="checkbox" />
              <span>标出来源群</span>
            </label>
            <label class="check">
              <input v-model="form.showUser" type="checkbox" />
              <span>标出来源 QQ</span>
            </label>
          </template>
        </div>

        <div class="checks">
          <label class="check">
            <input v-model="form.enabled" type="checkbox" />
            <span>启用</span>
          </label>
          <span v-if="form.scope !== 'GROUP'" class="faint note">
            数据范围：{{ SCOPE_LABEL[form.scope] ?? form.scope }}
          </span>
        </div>

        <div v-if="board && !board.builtin" class="delete-line">
          <Button size="sm" variant="danger" @click="emit('remove', board)">删除命令</Button>
          <span class="faint note">板块记录不会被删除。</span>
        </div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.editor { background: var(--stone-300); border-radius: 10px; padding: 20px 22px; }
.editor-head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--sp-4); margin-bottom: var(--sp-5); }
.editor-eyebrow { font-size: 11.5px; letter-spacing: .14em; text-transform: uppercase; color: var(--ink-4); }
.editor-path { font-family: var(--font-mono); font-size: 17px; color: var(--flame); margin-top: 2px; }
.editor-actions { display: flex; gap: var(--sp-2); }
.editor-grid { display: grid; grid-template-columns: 1.15fr 1fr; gap: var(--sp-6); align-items: start; }
.editor-col { display: flex; flex-direction: column; gap: var(--sp-3); }
.col-title {
  font-size: 11.5px; letter-spacing: .14em; text-transform: uppercase; color: var(--ink-4);
  padding-bottom: var(--sp-2); border-bottom: 1px solid var(--hairline);
}

.row { display: flex; align-items: center; gap: var(--sp-3); }
.row-label { flex: 0 0 76px; font-size: var(--fs-meta); color: var(--ink-3); }
.row > :not(.row-label) { flex: 1; min-width: 0; }
.row--block { flex-direction: column; align-items: stretch; gap: var(--sp-2); }
.row--block .row-label { flex: none; }

.checks { display: flex; flex-wrap: wrap; gap: var(--sp-4); }
.check { display: flex; align-items: center; gap: 6px; font-size: var(--fs-meta); color: var(--ink-2); }
.check input { accent-color: var(--flame); }

.block { border-top: 1px solid var(--hairline); padding-top: var(--sp-2); }
.block-head {
  display: flex; align-items: center; gap: 8px; width: 100%;
  background: none; border: 0; padding: 6px 0;
  color: var(--ink-2); font-size: var(--fs-meta); cursor: pointer; text-align: left;
}
.block-head:hover { color: var(--ink); }
.caret { width: 12px; color: var(--flame); font-family: var(--font-mono); }
.block-body { padding: var(--sp-2) 0 var(--sp-1); display: flex; flex-direction: column; gap: var(--sp-2); }

.format-block {
  display: flex; flex-direction: column; gap: var(--sp-2);
  border-top: 1px solid var(--hairline); padding-top: var(--sp-3);
}
.limit-line { display: flex; align-items: center; gap: var(--sp-3); }
.limit-line :deep(.inp) { max-width: 120px; }
.format-hint { margin: 0; font-size: var(--fs-meta); color: var(--ink-4); line-height: 1.7; }
.format-hint code {
  font-family: var(--font-mono); color: var(--flame);
  background: var(--surface-inset); border: 1px solid var(--edge-soft);
  border-radius: 4px; padding: 0 4px;
}
.preview-box { display: flex; flex-direction: column; gap: 6px; }
.format-preview {
  margin: 0; padding: var(--sp-3); max-height: 220px; overflow: auto;
  background: var(--stone-void); border: 1px solid var(--edge);
  border-radius: var(--r-sm); color: var(--ink-2);
  font-family: var(--font-mono); font-size: var(--fs-meta);
  line-height: 1.7; white-space: pre-wrap;
}
.preview-empty { font-size: var(--fs-meta); }

.path-hint { margin: var(--sp-1) 0 0 88px; font-size: var(--fs-meta); color: var(--ink-4); line-height: 1.7; }
.path-demo { font-family: var(--font-mono); color: var(--flame); }
.schedule-hint {
  display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap;
  margin-top: var(--sp-2); padding: var(--sp-2) var(--sp-3);
  background: var(--surface-inset); border: 1px solid var(--edge-soft); border-radius: var(--r-sm);
  font-size: var(--fs-meta); color: var(--ink-3);
}
.delete-line { display: flex; align-items: center; gap: var(--sp-3); margin-top: var(--sp-2); }
.note { font-size: var(--fs-meta); margin: 0; }
.faint { color: var(--ink-4); }

@media (max-width: 1000px) {
  .editor-grid { grid-template-columns: 1fr; }
  .row { flex-direction: column; align-items: stretch; gap: 4px; }
  .row-label { flex: none; }
  .path-hint { margin-left: 0; }
}
</style>
