<script setup lang="ts">
/**
 * 板块编辑器 —— 与指令编辑器同一套两栏布局，整块替换列表。
 *
 * <p>板块名不可改：记录按板块名归属，改名会让历史记录失去来源。
 * 要换名字就新建一个，旧记录留在旧板块里。
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
  title: string
  params: AdminParamSpec[]
  delivery: string
  access: string
  scope: string
  showGroup: boolean
  showUser: boolean
  allowPrivate: boolean
  enabled: boolean
}

const form = reactive<FormState>({
  path: '', title: '', params: [], delivery: 'direct', access: 'all', scope: 'GROUP',
  showGroup: false, showUser: false, allowPrivate: false, enabled: true,
})
const groupsText = ref('')
const openParams = ref(true)

const isEditing = computed(() => props.board !== null)

const hasRepeat = computed(() => form.params.some((p) => p.name === '重复'))
const hasTime = computed(() => form.params.some((p) => p.name === '时间'))
const scheduled = computed(() => form.delivery === 'scheduled')

function reset() {
  const b = props.board
  form.path = b?.path ?? ''
  form.title = b?.title ?? ''
  form.params = (b?.params ?? []).map((p) => ({ ...p }))
  form.delivery = b?.delivery ?? 'direct'
  form.access = b?.access ?? 'all'
  form.scope = b?.scope ?? 'GROUP'
  form.showGroup = b?.showGroup ?? false
  form.showUser = b?.showUser ?? false
  form.allowPrivate = b?.allowPrivate ?? false
  form.enabled = b?.enabled ?? true
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
    title: form.title.trim(),
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
    originalPath: props.board?.path ?? null,
  }
  emit('save', draft)
}
</script>

<template>
  <section class="editor">
    <div class="editor-head">
      <div>
        <div class="editor-eyebrow">编辑板块</div>
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
          <span class="row-label">板块名</span>
          <Input
            v-model="form.path"
            :disabled="isEditing"
            placeholder="公告（一级路径，不带斜杠）"
            aria-label="板块名"
          />
        </label>
        <p class="path-hint">
          群友发 <span class="path-demo">/{{ form.path || '…' }}</span> 查看，
          发 <span class="path-demo">/{{ form.path || '…' }} /create</span> 添加。
          <template v-if="isEditing">板块名不可改；要换名字请新建一个。</template>
        </p>

        <label class="row">
          <span class="row-label">名称</span>
          <Input v-model="form.title" placeholder="群友看到的名字；留空就用板块名" aria-label="板块名称" />
        </label>

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
          <Button size="sm" variant="danger" @click="emit('remove', board)">删除板块</Button>
          <span class="faint note">板块里的记录不会被删除。</span>
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
