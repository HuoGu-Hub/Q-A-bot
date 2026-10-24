<script setup lang="ts">
/**
 * 板块编辑器 —— 与指令编辑器同一套两栏布局，整块替换列表。
 *
 * <p>命令名就是群里的调用名：没有单独的"显示名"。自建命令可以改名，
 * 历史记录会在同一事务里迁到新名字下；内置命令的路径是代码约定，不能改。
 */
import { computed, nextTick, reactive, ref, watch } from 'vue'
import type { AdminBoard, AdminParamSpec, BoardDraft, CommandAcl, CommandVariables } from '@shared/api/types'
import { adminApi } from '@shared/api/client'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Button from '@shared/ui/Button.vue'
import Notice from '@shared/ui/Notice.vue'
import CommandEditorShell from './CommandEditorShell.vue'
import Icon from '@shared/ui/Icon.vue'
import ParamTable from './ParamTable.vue'
import VariablePanel from './VariablePanel.vue'
import AclEditor from './AclEditor.vue'
import { useVariableInsert } from './useVariableInsert'
import { ACCESS_OPTIONS, DATA_SCOPES, DELIVERY_OPTIONS } from './commandOptions'
import { aclBody, aclCount, emptyAclForm, fillAcl } from './aclForm'
import type { AclBody, AclForm } from './aclForm'

const props = defineProps<{
  /** null = 新建 */
  board: AdminBoard | null
  saving: boolean
  error: string
  /** 全局通用变量，与板块专用变量合并后一起展示 */
  variables?: CommandVariables | null
}>()

const emit = defineEmits<{
  /** 第二项是名单：保存板块成功后由父组件按板块 id 落到 /boards/{id}/acl */
  save: [BoardDraft, AclBody]
  cancel: []
  remove: [AdminBoard]
}>()

interface FormState {
  path: string
  description: string
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
  listReply: string
  helpReply: string
}

const form = reactive<FormState>({
  path: '', description: '', params: [], delivery: 'direct', access: 'all', scope: 'GROUP',
  showGroup: false, showUser: false, allowPrivate: false, enabled: true,
  listHeader: '', listItem: '', listEmpty: '', listLimit: 20, listReply: '', helpReply: '',
})
const groupsText = ref('')
const openParams = ref(true)
const openOutput = ref(true)
const openList = ref(false)
const openHelp = ref(false)

const { insert: insertVariable, activeField } = useVariableInsert({
  listHeader: (value) => { form.listHeader = value },
  listItem: (value) => { form.listItem = value },
  listEmpty: (value) => { form.listEmpty = value },
  listReply: (value) => { form.listReply = value },
  helpReply: (value) => { form.helpReply = value },
})

/**
 * 列表字段走 BoardListFormatter 的变量，list/help 回复走全局变量渲染 ——
 * 两套变量不通用，所以变量面板按当前编辑区切换，不给点不出来的按钮。
 */
const TEXT_FIELDS = ['listReply', 'helpReply']

const rowVars = computed(() => {
  const vars = [
    { name: 'count', label: '总条数', example: '3' },
    { name: 'path', label: '命令名', example: '预约开黑' },
    { name: 'id', label: '记录编号', example: '12' },
    { name: 'when', label: '时间', example: '立即展示' },
    { name: 'nickname', label: '发起人昵称', example: '阿明' },
    { name: 'text', label: '内容', example: '缺打野' },
    { name: 'group', label: '记录所属群号', example: '269127588' },
    { name: 'params.参数名', label: '参数值', example: '今晚8点' },
  ]
  // QQ 号跟「显示 QQ」开关走：没勾就不会渲染，面板里就不该出现
  if (form.scope !== 'GROUP' && form.showUser) {
    vars.push({ name: 'user.id', label: '发起人 QQ 号', example: '100000005' })
  }
  return vars
})

const textVars = computed(() => (props.variables?.variables ?? [])
  .filter((v) => v.inText)
  .map((v) => ({ name: v.name, label: v.label, example: v.example, advanced: v.advanced })))

const boardVars = computed(() =>
  TEXT_FIELDS.includes(activeField.value) ? textVars.value : rowVars.value)

const paramTableRef = ref<InstanceType<typeof ParamTable> | null>(null)

/** 参数块的“+ 参数”在标题行：先展开，再让参数表插入一行 */
function addParam() {
  openParams.value = true
  nextTick(() => paramTableRef.value?.addParam())
}

/** 内置命令的路径由代码约定，不能改名；自建命令可以改名并迁移记录 */
const pathLocked = computed(() => props.board?.builtin === true)

/**
 * 调度用的重复周期参数：按类型认，名字随意；老配置按「重复」这个名字认。
 * 时间点优先认「时间」，没有时用唯一的 moment —— 与服务端 Board 的判定一致。
 */
const repeatParam = computed(() => form.params.find((p) => p.type === 'repeat')
  ?? form.params.find((p) => p.name === '重复') ?? null)
const timeParam = computed(() => {
  const named = form.params.find((p) => p.name === '时间')
  if (named) return named
  const moments = form.params.filter((p) => p.type === 'moment')
  return moments.length === 1 ? moments[0] : null
})
const hasRepeat = computed(() => repeatParam.value !== null)
const hasTime = computed(() => timeParam.value !== null)
const scheduled = computed(() => form.delivery === 'scheduled')

/** 名单只在看名单的档位下才有意义；和指令编辑器同一份规则 */
const needsAcl = computed(() =>
  ['whitelist', 'blacklist', 'admin_whitelist'].includes(form.access))
const acl = ref<AclForm>(emptyAclForm())
const openAcl = ref(false)
const aclSummary = computed(() => aclCount(acl.value))

function reset() {
  const b = props.board
  form.path = b?.path ?? ''
  form.description = b?.description ?? ''
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
  form.listReply = b?.listReply ?? ''
  form.helpReply = b?.helpReply ?? ''
  form.listLimit = b?.listLimit ?? 20
  groupsText.value = (b?.groups ?? []).join('\n')
  upgradeRepeatType()
  // 名单按板块 id 存；新建时还没有 id，等保存成功后再由父组件写入
  acl.value = emptyAclForm()
  if (b?.id) {
    adminApi.get<CommandAcl>('/boards/' + b.id + '/acl')
      .then((saved) => fillAcl(acl.value, saved))
      .catch(() => { /* 读不到名单不挡编辑 */ })
  }
  openParams.value = true
  openOutput.value = true
}

watch(() => props.board, reset, { immediate: true })
// 同一次编辑里把投递改成「到点发送」时，老的文本「重复」也要跟着换类型
watch(() => form.delivery, (value) => {
  if (value === 'scheduled') upgradeRepeatType()
})

/**
 * 「重复」是调度约定参数，不是普通文本。
 *
 * <p>重复周期类型上线前建的定时板块把它存成了 text，读进来时统一换成
 * repeat：值不变、解析规则不变，但落库形态会规范成 once / daily / weekly:3。
 */
function upgradeRepeatType() {
  if (form.delivery !== 'scheduled') return
  const legacy = form.params.find((p) => p.name === '重复' && p.type === 'text')
  if (legacy) legacy.type = 'repeat'
}

/** 到点发送的两个约定参数：缺哪个补哪个，已有的不动类型除外 —— 老的文本「重复」就地升级 */
function ensureScheduleParams() {
  upgradeRepeatType()
  if (!hasRepeat.value) {
    form.params.push({
      name: '重复', type: 'repeat', required: true, example: '每天',
      description: '一次 / 3次（共几条）/ 3天 / 每天 / 每周1-7 / 每2-30天', multiple: false,
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
  qq: string
  group: string
  params: Record<string, string>
}

const previewRecords: PreviewRecord[] = [
  { id: 1, when: '立即展示', text: '缺打野', nickname: '阿明', qq: '100000005', group: '123456', params: { 时间: '今晚8点', 人数: '5' } },
  { id: 2, when: '立即展示', text: '满员了', nickname: '小红', qq: '100000006', group: '123456', params: { 时间: '明天8点', 人数: '10' } },
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
  // QQ 号只在「显示 QQ」打开时可取，和 BoardListFormatter 的判定一致
  if (form.scope !== 'GROUP' && form.showUser) vars['user.id'] = record.qq
  for (const [key, value] of Object.entries(record.params)) vars['params.' + key] = value
  return vars
}

/** 跨群记录才标来源，本群记录不标 —— 与服务端 BoardListFormatter.markOrigin 一致 */
function previewOrigin(record: PreviewRecord): string {
  if (form.scope === 'GROUP') return ''
  let out = ''
  if (form.showGroup) out += ' · 来自 ' + record.group
  if (form.showUser) out += ' · QQ ' + record.qq
  return out
}

const previewText = computed(() => {
  const path = form.path || '命令'
  const header = form.listHeader.trim()
    ? renderTemplate(form.listHeader, { count: String(previewRecords.length), path })
    : `「${path}」共 ${previewRecords.length} 条：`
  const items = previewRecords.map((record) => {
    const line = form.listItem.trim()
      ? renderTemplate(form.listItem, previewVars(record, String(previewRecords.length)))
      : `#${record.id}  ${record.when}  ${record.text}`
    return line + previewOrigin(record)
  })
  return [header, ...items].join('\n')
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
    description: form.description.trim(),
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
    listReply: form.listReply.trim(),
    helpReply: form.helpReply.trim(),
    originalPath: props.board?.path ?? null,
    defaultList: props.board?.defaultList ?? '',
    defaultHelp: props.board?.defaultHelp ?? '',
  }
  emit('save', draft, aclBody(acl.value))
}
</script>

<template>
  <CommandEditorShell
    :open="true"
    :title="board ? '编辑板块' : '新建板块'"
    width="1400px"
    :saving="saving"
    @close="emit('cancel')"
    @save="submit"
  >
    <template #head>
      <div class="editor-ident">
        <div class="editor-kicker">
          <span class="type-badge">板块</span>
          <span class="editor-mode">{{ board?.builtin ? '内置' : board ? '编辑' : '新建' }}</span>
        </div>
        <div class="editor-path" :class="{ 'is-empty': !form.path.trim() }">/{{ form.path || '命令名' }}</div>
      </div>
    </template>

    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <div class="editor-grid">
      <section class="editor-col">
        <div class="col-title">内容</div>

        <label class="row">
          <span class="row-label">命令名</span>
          <Input
            v-model="form.path"
            :disabled="pathLocked"
            placeholder="公告"
            aria-label="命令名"
          />
        </label>

        <label class="row">
          <span class="row-label">说明</span>
          <Input
            v-model="form.description"
            placeholder="一句话说明这个板块"
            aria-label="板块说明"
          />
        </label>

        <div class="block">
          <div class="block-head block-head--with-action">
            <button class="block-toggle" type="button" :aria-expanded="openParams" @click="openParams = !openParams">
              <Icon name="chevron-right" :size="14" class="block-chev" :class="{ open: openParams }" />
              <span>参数</span>
              <span class="block-count">{{ form.params.length }}</span>
            </button>
            <button class="head-add" type="button" @click="addParam">+ 参数</button>
          </div>
          <div v-if="openParams" class="block-body">
            <ParamTable
              ref="paramTableRef"
              :params="form.params"
              empty-hint="无参数"
              @update:params="form.params = $event"
            />
            <div v-if="scheduled" class="schedule-hint">
              <span>需要「重复周期」与「时间点」各一个，名字可以改</span>
              <Button v-if="!hasRepeat || !hasTime" size="sm" @click="ensureScheduleParams">补全</Button>
            </div>
          </div>
        </div>

        <div class="block">
          <button class="block-head" type="button" :aria-expanded="openOutput" @click="openOutput = !openOutput">
            <Icon name="chevron-right" :size="14" class="block-chev" :class="{ open: openOutput }" />
            <span>输出</span>
            <span class="block-count">3</span>
          </button>
          <div v-if="openOutput" class="block-body output-body">
          <label class="row">
            <span class="row-label">列表头</span>
            <Input
              v-model="form.listHeader"
              placeholder="预约开黑 · 共 {count} 条"
              aria-label="列表头"
              data-var-field="listHeader"
            />
          </label>
          <label class="row row--top">
            <span class="row-label">每行</span>
            <Input
              v-model="form.listItem"
              multiline
              :rows="2"
              placeholder="#{id} {params.时间} {text} / {nickname}"
              aria-label="每条格式"
              data-var-field="listItem"
            />
          </label>
          <label class="row">
            <span class="row-label">无记录时</span>
            <Input
              v-model="form.listEmpty"
              :placeholder="'还没有内容，发 /' + (form.path || '命令') + ' /create 添加'"
              aria-label="空列表文案"
              data-var-field="listEmpty"
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
              <span class="faint limit-hint">0 = 全部</span>
            </div>
          </label>
          <div class="preview-box">
            <div class="col-title">预览</div>
            <pre class="format-preview">{{ previewText }}</pre>
          </div>
          </div>
        </div>
    <div class="block">
      <button class="block-head" type="button" :aria-expanded="openList" @click="openList = !openList">
        <Icon name="chevron-right" :size="14" class="block-chev" :class="{ open: openList }" />
        <span>list 回复</span>
        <span class="block-count">{{ form.listReply.trim() ? '手写' : '自动' }}</span>
      </button>
      <div v-if="openList" class="block-body">
        <Input
          v-model="form.listReply"
          multiline
          :rows="4"
          mono
          placeholder="留空自动，默认列出本板块动作"
          aria-label="板块 list 回复"
          data-var-field="listReply"
        />
        <div class="reply-preview">
          <div class="reply-label">实际回复</div>
          <pre>{{ form.listReply.trim() || board?.defaultList || '保存后显示默认回复' }}</pre>
        </div>
      </div>
    </div>

    <div class="block">
      <button class="block-head" type="button" :aria-expanded="openHelp" @click="openHelp = !openHelp">
        <Icon name="chevron-right" :size="14" class="block-chev" :class="{ open: openHelp }" />
        <span>help 回复</span>
        <span class="block-count">{{ form.helpReply.trim() ? '手写' : '全局 help' }}</span>
      </button>
      <div v-if="openHelp" class="block-body">
        <Input
          v-model="form.helpReply"
          multiline
          :rows="4"
          mono
          placeholder="留空使用全局 help"
          aria-label="板块 help 回复"
          data-var-field="helpReply"
        />
        <div class="reply-preview">
          <div class="reply-label">实际回复</div>
          <pre>{{ form.helpReply.trim() || board?.defaultHelp || '保存后显示默认回复' }}</pre>
        </div>
      </div>
    </div>
      </section>

      <section class="editor-col editor-col--side">
        <div class="col-title">规则</div>

        <VariablePanel :vars="boardVars" @insert="insertVariable" />

        <label class="row">
          <span class="row-label">投递</span>
          <Select v-model="form.delivery" :options="DELIVERY_OPTIONS" aria-label="投递方式" />
        </label>

        <label class="row">
          <span class="row-label">权限</span>
          <Select v-model="form.access" :options="ACCESS_OPTIONS" aria-label="谁能用" />
        </label>

        <label class="row">
          <span class="row-label">范围</span>
          <Select :model-value="form.scope" :options="DATA_SCOPES" aria-label="数据范围" @update:model-value="onScopeChange" />
        </label>

        <div v-if="form.scope === 'SOME'" class="row row--block">
          <span class="row-label">群号</span>
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
            <span>私聊</span>
          </label>
          <template v-if="form.scope !== 'GROUP'">
            <label class="check" title="别群的记录后面标「来自 群号」，本群的不标">
              <input v-model="form.showGroup" type="checkbox" />
              <span>显示群</span>
            </label>
            <label class="check" title="别群的记录后面标「QQ 号」，并开放 {user.id} 变量">
              <input v-model="form.showUser" type="checkbox" />
              <span>显示 QQ</span>
            </label>
          </template>
        </div>
        <p v-if="form.scope !== 'GROUP'" class="faint origin-hint">
          跨群记录才标来源；本群记录不标
        </p>

        <div v-if="needsAcl" class="block">
          <button class="block-head" type="button" :aria-expanded="openAcl" @click="openAcl = !openAcl">
            <Icon name="chevron-right" :size="14" class="block-chev" :class="{ open: openAcl }" />
            <span>名单</span>
            <span class="block-count">{{ aclSummary }}</span>
          </button>
          <div v-if="openAcl" class="block-body">
            <AclEditor v-model="acl" />
          </div>
        </div>

        <div class="checks">
          <label class="check">
            <input v-model="form.enabled" type="checkbox" />
            <span>启用</span>
          </label>
        </div>

        <div v-if="board && !board.builtin" class="delete-line">
          <Button size="sm" variant="danger" @click="emit('remove', board)">删除命令</Button>
        </div>
      </section>
    </div>

  </CommandEditorShell>
</template>

<style scoped>
.editor {
  background: var(--stone-300);
  border-top: 2px solid var(--ember);
  border-radius: var(--r-md);
  padding: 22px 24px;
}
.editor-head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--sp-4); margin-bottom: var(--sp-5); }
.editor-ident { min-width: 0; }
.editor-kicker { display: flex; align-items: center; gap: var(--sp-2); }
.type-badge {
  padding: 2px 7px;
  border: 1px solid var(--hairline-lit);
  border-radius: var(--r-xs);
  color: var(--ember);
  font-size: 10.5px;
  letter-spacing: .12em;
}
.editor-mode { color: var(--ink-4); font-size: 10.5px; letter-spacing: .16em; }
.editor-path.is-empty { color: var(--ink-4); }
.editor-path {
  margin-top: 4px;
  color: var(--ink);
  font-family: var(--font-mono);
  font-size: 24px;
  line-height: 1.2;
  overflow-wrap: anywhere;
}
.editor-actions { display: flex; gap: var(--sp-2); flex: none; }
.editor-grid { display: grid; grid-template-columns: 1.35fr .85fr; gap: var(--sp-7); align-items: start; }
.origin-hint { margin: -4px 0 0; font-size: 11px; line-height: 1.4; }
.editor-col { display: flex; flex-direction: column; gap: var(--sp-3); min-width: 0; }
.editor-col--side { position: sticky; top: 0; align-self: start; gap: var(--sp-2); }
.editor-col--side .row { gap: var(--sp-2); }
.editor-col--side .row-label { flex-basis: 64px; }
.editor-col--side .col-title { padding-bottom: 4px; }
.editor-col--side .checks { gap: var(--sp-3); }
@media (max-width: 1000px) { .editor-col--side { position: static; } }
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
  padding: 9px 10px; margin-top: 4px;
  background: var(--stone-400); border: 1px solid var(--edge); border-radius: var(--r-sm);
  color: var(--ink-2); font-size: var(--fs-meta); cursor: pointer; text-align: left;
}
.block-head:hover { background: var(--stone-500); border-color: var(--edge-hover); color: var(--ink); }
.block-chev { color: var(--ember); transition: transform var(--dur-fast) var(--ease); }
.block-chev.open { transform: rotate(90deg); }
.block-count { margin-left: auto; color: var(--ink-4); font-family: var(--font-mono); }
.block-head:focus-visible { outline: 2px solid var(--flame); outline-offset: 2px; }
.block-head--with-action { padding: 0; cursor: default; }
.block-toggle {
  display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0;
  padding: 9px 10px; background: none; border: 0;
  color: inherit; font: inherit; text-align: left; cursor: pointer;
}
.block-toggle:focus-visible { outline: 2px solid var(--flame); outline-offset: -2px; }
.head-add {
  margin-right: 8px; padding: 2px 7px;
  background: none; border: 1px solid transparent; border-radius: var(--r-xs);
  color: var(--ember); font-size: 11px; cursor: pointer;
}
.head-add:hover { background: var(--stone-500); border-color: var(--edge); }
.head-add:focus-visible { outline: 2px solid var(--flame); outline-offset: 2px; }
.block-body {
  display: flex; flex-direction: column; gap: var(--sp-2);
  min-width: 0; overflow-x: auto;
  margin-top: 8px; padding: 12px 14px;
  background: var(--stone-400); border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  box-shadow: inset 0 1px 0 var(--hairline-lit);
}

.output-body { gap: var(--sp-3); }
.row--top { align-items: flex-start; }
.row--top .row-label { padding-top: 8px; }
.limit-line { position: relative; flex: 1; min-width: 0; }
.limit-line :deep(.inp) { width: 100%; max-width: none; padding-right: 64px; }
.limit-hint {
  position: absolute; right: 10px; top: 50%;
  transform: translateY(-50%);
  pointer-events: none;
}
.preview-box { display: flex; flex-direction: column; gap: 6px; }
.format-preview {
  margin: 0; padding: var(--sp-3); max-height: 220px; overflow: auto;
  background: var(--stone-void); border: 1px solid var(--edge);
  border-radius: var(--r-sm); color: var(--ink-2);
  font-family: var(--font-mono); font-size: var(--fs-meta);
  line-height: 1.7; white-space: pre-wrap;
}

.schedule-hint {
  display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap;
  margin-top: var(--sp-2); padding: var(--sp-2) var(--sp-3);
  background: var(--surface-inset); border: 1px solid var(--edge-soft); border-radius: var(--r-sm);
  font-size: var(--fs-meta); color: var(--ink-3);
}
.delete-line { display: flex; align-items: center; gap: var(--sp-3); margin-top: var(--sp-2); }
.faint { color: var(--ink-4); }

@media (max-width: 1000px) {
  .editor-grid { grid-template-columns: 1fr; }
  .row { flex-direction: column; align-items: stretch; gap: 4px; }
  .row-label { flex: none; }
}
.reply-preview { display: flex; flex-direction: column; gap: 6px; }
.reply-label { color: var(--ink-4); font-size: 10.5px; letter-spacing: .12em; }
.reply-preview pre {
  max-height: 220px; margin: 0; padding: var(--sp-3); overflow: auto;
  white-space: pre-wrap; word-break: break-word;
  background: var(--stone-400); border: 1px solid var(--edge);
  border-radius: var(--r-sm); color: var(--ink-2);
  font-family: var(--font-mono); font-size: var(--fs-xs); line-height: 1.7;
}
</style>
