<script setup lang="ts">
/**
 * 「指令」Tab 面板。
 *
 * 为什么是面板而不是页面：管理端要把「设置」和「指令」并进同一个「Bot 配置」页，
 * 页面内用二级目录切换。页面级外壳（AdminPage、Tabs、页面标题）由父页面提供，
 * 所以这里不接收 props、不 emit 事件，也不渲染 AdminPage ——
 * 只负责本 tab 的内容。页面栏上的「刷新」「+ 新建命令」由父页面
 * BotConfigView 渲染，动作经 defineExpose 抛出（按钮调的是面板内部状态，视图拿不到）。
 *
 * 板块和指令是同一件事的两种形态：对外只有一张命令表，类型列区分
 * 「板块 / 话术 / 问答 / 动作」；底层仍分两张表 —— 板块多一层投递与记录。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type {
  CommandList, BotCommand, CommandVariables, CommandStats, CommandActions, AdminParamSpec,
  CommandPolicy, CommandPolicies, CommandAcl, AdminBoard, AdminBoardList, BoardDraft,
} from '@shared/api/types'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Notice from '@shared/ui/Notice.vue'
import Button from '@shared/ui/Button.vue'
import Icon from '@shared/ui/Icon.vue'
import ParamTable from './ParamTable.vue'
import BoardEditor from './BoardEditor.vue'
import BoardRecordsModal from './BoardRecordsModal.vue'
import NewCommandModal from './NewCommandModal.vue'
import PromptEditor from './PromptEditor.vue'
import {
  KIND_OPTIONS, ACCESS_OPTIONS, MODE_OPTIONS, ACCESS_LABEL, DATA_SCOPES, SCOPE_LABEL,
} from './commandOptions'

const loading = ref(false)
const error = ref('')
const data = ref<CommandList | null>(null)
/** 板块（含停用的）。与群里 /list 读同一个 BoardStore */
const boards = ref<AdminBoard[]>([])
const vars = ref<CommandVariables | null>(null)
const stats = ref<CommandStats | null>(null)
/** 已注册的动作：只有 action 类命令才用得上 */
const actions = ref<CommandActions | null>(null)

/** 编辑中的指令（null = 没在编辑） */
const editing = ref<Partial<BotCommand> | null>(null)
/** 板块编辑状态：undefined = 没在编辑，null = 新建，对象 = 编辑这一条 */
const boardEditing = ref<AdminBoard | null | undefined>(undefined)
const boardSaving = ref(false)
const boardError = ref('')
/** 正在看记录的板块；null = 弹窗关闭 */
const recordsBoard = ref<AdminBoard | null>(null)
/** /prompt 的自定义编辑器：不是普通命令表单 */
const promptEditing = ref<BotCommand | null>(null)
/** 新建命令的类型选择弹窗 */
const newOpen = ref(false)
/** 命令表筛选 */
const search = ref('')
const kindFilter = ref('all')
/** 使用情况统计范围（天） */
const statsDays = ref(30)
/** 实时预览结果 */
const preview = ref('')
/** 预览 {args} 用的示例参数 */
const sampleArgs = ref('')
/** 变量默认只露前几个，避免一屏全是胶囊 */
const showAllVars = ref(false)

const RANGE_OPTIONS: Array<{ value: string; label: string }> = [
  { value: '7', label: '近 7 天' },
  { value: '30', label: '近 30 天' },
  { value: '90', label: '近 90 天' },
]

/** 统一命令表的一行：source 决定用哪个编辑器、有没有「记录」 */
interface CommandRow {
  source: 'board' | 'command' | 'system' | 'prompt'
  key: string
  path: string
  usage: string
  kind: string
  description: string
  access: string
  enabled: boolean
  builtin: boolean
  board: AdminBoard | null
  command: BotCommand | null
}

/** 10 天统计里按命令路径查次数；日志里的 trigger 是小写 */
const usageMap = computed<Record<string, number>>(() => {
  const map: Record<string, number> = {}
  for (const item of stats.value?.usage ?? []) {
    map[item.trigger.toLowerCase()] = item.count
  }
  return map
})

/** 全局系统命令：由路由层直接处理，不在 bot_command 里，也不能编辑 */
const SYSTEM_ROWS: CommandRow[] = [
  {
    source: 'system', key: 'system-list', path: 'list', usage: '', kind: 'system',
    description: '全部命令', access: 'all', enabled: true, builtin: true,
    board: null, command: null,
  },
  {
    source: 'system', key: 'system-help', path: 'help', usage: '', kind: 'system',
    description: '格式与说明', access: 'all', enabled: true, builtin: true,
    board: null, command: null,
  },
]

/** 系统命令在前，板块次之，普通指令最后；与群里 /list 的顺序一致 */
const rows = computed<CommandRow[]>(() => [
  ...SYSTEM_ROWS,
  ...boards.value.map((b): CommandRow => ({
    source: 'board', key: 'board-' + b.id, path: b.path, usage: b.usage, kind: 'board',
    description: '—',
    access: b.access, enabled: b.enabled, builtin: b.builtin, board: b, command: null,
  })),
  ...(data.value?.commands ?? []).map((c): CommandRow => {
    const isPrompt = c.trigger.toLowerCase() === 'prompt'
    return {
      source: isPrompt ? 'prompt' : 'command', key: 'command-' + c.id, path: c.trigger,
      usage: c.usage, kind: isPrompt ? 'system' : c.kind,
      description: c.description || '—', access: c.access, enabled: c.enabled, builtin: c.builtin,
      board: null, command: c,
    }
  }),
])

/** 指令类型与准入选项在 commandOptions.ts —— 板块编辑器也要用同一份 */

const FILTERS = [
  { value: 'all', label: '全部' },
  { value: 'board', label: '板块' },
  { value: 'template', label: '话术' },
  { value: 'agent', label: '问答' },
  { value: 'action', label: '动作' },
  { value: 'system', label: '系统' },
]

const boardCount = computed(() => boards.value.length)

const visibleRows = computed(() => {
  const q = search.value.trim().toLowerCase()
  return rows.value.filter((row) => {
    if (kindFilter.value !== 'all' && row.kind !== kindFilter.value) return false
    if (!q) return true
    return row.path.toLowerCase().includes(q)
      || row.description.toLowerCase().includes(q)
  })
})

const maxUsed = computed(() => Math.max(1, ...(stats.value?.topUsed ?? []).map((x) => x.count)))
const maxMiss = computed(() => Math.max(1, ...(stats.value?.unmatched ?? []).map((x) => x.count)))

const globalAclCount = computed(() => {
  const f = globalForm.value
  return [f.whiteUsers, f.whiteGroups, f.blackUsers, f.blackGroups]
    .filter((s) => s.trim()).length
})

const isAgent = computed(() => editing.value?.kind === 'agent')
const isAction = computed(() => editing.value?.kind === 'action')
/** 动作下拉：值是动作 id，直接存进库里那一列 */
const actionOptions = computed(() =>
  (actions.value?.actions ?? []).map((a) => ({ value: a.id, label: a.label || a.id }))
)

/** 编辑中的参数表。新建时是空数组，模板里靠它渲染行 */
const paramRows = computed<AdminParamSpec[]>(() => editing.value?.params ?? [])

async function loadStats() {
  try {
    stats.value = await adminApi.get<CommandStats>('/commands/stats?days=' + statsDays.value)
  } catch { /* 统计失败不挡配置 */ }
}

watch(statsDays, loadStats)

function onRangeChange(value: string) {
  statsDays.value = Number(value)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await adminApi.get<CommandList>('/commands')
    try { boards.value = (await adminApi.get<AdminBoardList>('/boards')).boards }
    catch { boards.value = [] }
    vars.value = await adminApi.get<CommandVariables>('/commands/variables')
    await loadStats()
    actions.value = await adminApi.get<CommandActions>('/commands/actions')
    const pol = await adminApi.get<CommandPolicies>('/commands/policies')
    policies.value = Object.fromEntries(pol.policies.map((x) => [x.id, x]))
    await loadGlobalAcl()
  } catch (e) { error.value = describeError(e) } finally {
    loading.value = false
  }
}

function newCommand(kind = 'template') {
  boardEditing.value = undefined
  editing.value = {
    trigger: '', reply: '', description: '', scope: 'all', enabled: true, sortOrder: 0,
    kind, mode: 'kb', params: [], help: '', action: null, access: 'all',
  }
  preview.value = ''
  sampleArgs.value = ''
  showAllVars.value = false
  policy.value = { scope: 'GROUP', groups: '', showGroup: false, showUser: false, allowPrivate: false }
  acl.value = { whiteUsers: '', whiteGroups: '', blackUsers: '', blackGroups: '' }
}

/** 页面栏的「+ 新建命令」：先选类型，再进对应编辑器 */
function openNewMenu() {
  editing.value = null
  boardEditing.value = undefined
  boardError.value = ''
  newOpen.value = true
}

function pickNew(kind: string) {
  newOpen.value = false
  if (kind === 'board') {
    editing.value = null
    boardEditing.value = null
    boardError.value = ''
    return
  }
  newCommand(kind)
}

function edit(c: BotCommand) {
  boardEditing.value = undefined
  editing.value = { ...c }
  sampleArgs.value = sampleArgsOf(c.params ?? [])
  showAllVars.value = false
  updatePreview()
  const p = policyOf(c.id)
  policy.value = {
    scope: p.scope, groups: p.groups.join('\n'),
    showGroup: p.showGroup, showUser: p.showUser, allowPrivate: p.allowPrivate,
  }
  adminApi.get<CommandAcl>('/commands/' + c.id + '/acl')
    .then((a) => fillAcl(acl.value, a))
    .catch(() => { /* 读不到名单不挡编辑 */ })
}

/** 用参数表的示例值拼一段示例参数，编辑时预览立刻有东西可看 */
function sampleArgsOf(params: AdminParamSpec[]) {
  return params.map((p) => '#' + p.name + '-' + (p.example || p.name) + '#').join(' ')
}

/** 表格规则列：一句读完，不再堆标签 */
function rowRuleText(row: CommandRow): string {
  if (row.source === 'system') return '所有人'
  if (row.source === 'board' && row.board) {
    const parts = [ACCESS_LABEL[row.board.access] ?? row.board.access]
    if (row.board.scope !== 'GROUP') parts.push(SCOPE_LABEL[row.board.scope] ?? row.board.scope)
    if (row.board.allowPrivate) parts.push('私聊')
    return parts.join(' · ')
  }
  if (row.command) {
    const d = deviations(row.command)
    return d.length ? d.join(' · ') : '默认'
  }
  return '—'
}

/* ==================== 参数表编辑 ==================== */

function setParams(list: AdminParamSpec[]) {
  patch({ params: list })
  // 参数表的示例值会进 {arg.名字} 的预览，改完立刻重算
  updatePreview()
}

/**
 * 把选中动作声明的参数表抄进表单。
 *
 * 只覆盖名字与类型之外的空字段，不动用户已经填好的说明与示例 ——
 * "载入"是省事，不是重置。
 */
function loadActionParams() {
  const id = editing.value?.action
  const found = (actions.value?.actions ?? []).find((a) => a.id === id)
  if (!found) return
  setParams(found.params.map((p) => ({ ...p })))
}

/**
 * 表单写入口。
 * editing 的类型是 Partial（新建时只有部分字段），模板里逐字段赋值要写一堆非空断言；
 * 集中成一次浅合并，模板只剩「改哪个字段」这一件事。
 */
function patch(p: Partial<BotCommand>) {
  if (!editing.value) return
  editing.value = { ...editing.value, ...p }
}

async function updatePreview() {
  if (!editing.value) return
  try {
    const r = await adminApi.post<{ rendered: string }>('/commands/preview', {
      template: editing.value.reply ?? '',
      args: sampleArgs.value,
      params: editing.value.params ?? [],
    })
    preview.value = r.rendered
  } catch { /* 预览失败不阻塞 */ }
}

/** 示例参数改动 → 重算预览（{args} 才看得到效果） */
function onArgsInput(v: string) {
  sampleArgs.value = v
  updatePreview()
}

/** 回复内容每次改动都重算预览（原来挂在 textarea 的 @input 上） */
function onReplyInput(v: string) {
  patch({ reply: v })
  updatePreview()
}

/** 点击变量 → 插入到回复末尾（光标位置在 textarea 里比较麻烦，追加更直观） */
function insertVar(name: string) {
  if (!editing.value) return
  patch({ reply: (editing.value.reply ?? '') + '{' + name + '}' })
  updatePreview()
}

async function save() {
  if (!editing.value) return
  try {
    // 旧字段 scope 由私聊开关推导，避免两个开关说同一件事
    const body = { ...editing.value, scope: policy.value.allowPrivate ? 'all' : 'group' }
    await adminApi.post('/commands', body)

    // 命令刚建出来还没有 id，按触发词回头找一次
    const list = await adminApi.get<CommandList>('/commands')
    const saved = list.commands.find((c) => c.trigger === body.trigger)
    if (saved) {
      await adminApi.post('/commands/' + saved.id + '/policy', {
        scope: policy.value.scope,
        groups: parseIds(policy.value.groups),
        showGroup: policy.value.showGroup,
        showUser: policy.value.showUser,
        allowPrivate: policy.value.allowPrivate,
      })
      await adminApi.post('/commands/' + saved.id + '/acl', aclBody(acl.value))
    }

    editing.value = null
    await load()
  } catch (e) { error.value = describeError(e) }
}

async function remove(c: BotCommand) {
  if (!window.confirm('确定删除 /' + c.trigger + ' ？')) return
  try {
    await adminApi.post('/commands/delete', { trigger: c.trigger })
    await load()
  } catch (e) { error.value = describeError(e) }
}

async function toggle(c: BotCommand) {
  try {
    await adminApi.post('/commands/toggle', { trigger: c.trigger, enabled: !c.enabled })
    await load()
  } catch (e) { error.value = describeError(e) }
}

// ==================== 板块编辑 ====================

function editBoard(b: AdminBoard) {
  editing.value = null
  boardEditing.value = b
  boardError.value = ''
}

function cancelBoard() {
  boardEditing.value = undefined
  boardError.value = ''
}

function openRecords(b: AdminBoard) {
  recordsBoard.value = b
}

/** 近 N 天被用了几次；0 显示破折号，避免整列都是 0 */
function rowUsage(row: CommandRow) {
  const count = usageMap.value[row.path.toLowerCase()] ?? 0
  return count > 0 ? String(count) : '—'
}

function editRow(row: CommandRow) {
  if (row.source === 'system') return
  if (row.source === 'prompt' && row.command) {
    promptEditing.value = row.command
    return
  }
  if (row.source === 'board' && row.board) {
    editBoard(row.board)
  } else if (row.command) {
    edit(row.command)
  }
}

function toggleRow(row: CommandRow) {
  if (row.source === 'system') return
  if (row.source === 'board' && row.board) {
    toggleBoard(row.board)
  } else if (row.command) {
    toggle(row.command)
  }
}

function removeRow(row: CommandRow) {
  if (row.source === 'system' || row.source === 'prompt') return
  if (row.source === 'board' && row.board) {
    removeBoard(row.board)
  } else if (row.command) {
    remove(row.command)
  }
}

async function saveBoard(draft: BoardDraft) {
  boardSaving.value = true
  boardError.value = ''
  try {
    await adminApi.post('/boards', draft)
    boardEditing.value = undefined
    await load()
  } catch (e) {
    boardError.value = describeError(e)
  } finally {
    boardSaving.value = false
  }
}

async function removeBoard(b: AdminBoard) {
  if (!window.confirm('确定删除命令 /' + b.path + ' ？板块记录不会被删除。')) return
  try {
    await adminApi.post('/boards/delete', { path: b.path })
    await load()
  } catch (e) { error.value = describeError(e) }
}

async function toggleBoard(b: AdminBoard) {
  try {
    await adminApi.post('/boards/toggle', { path: b.path, enabled: !b.enabled })
    await load()
  } catch (e) { error.value = describeError(e) }
}

// ==================== 权限与名单 ====================

/** 命令 id → 数据策略。没配过就没有这个键，读的时候回默认 */
const policies = ref<Record<number, CommandPolicy>>({})
const globalAcl = ref<CommandAcl>({ white: [], black: [] })

/** 正在编辑的那条命令的策略与名单（保存时一起提交） */
const policy = ref({ scope: 'GROUP', groups: '', showGroup: false, showUser: false, allowPrivate: false })
const acl = ref({ whiteUsers: '', whiteGroups: '', blackUsers: '', blackGroups: '' })

const globalForm = ref({ whiteUsers: '', whiteGroups: '', blackUsers: '', blackGroups: '' })
const globalSaving = ref(false)
const globalSaved = ref('')

function policyOf(id: number): CommandPolicy {
  return policies.value[id] ?? { id, scope: 'GROUP', groups: [], showGroup: false, showUser: false, allowPrivate: false }
}

/**
 * 这条命令比"最保守的默认"松在哪。
 *
 * 这是这一页的核心读数：20 条命令里有 18 条是默认的，眼睛应该只落在剩下的 2 条上。
 */
function deviations(c: BotCommand): string[] {
  const p = policyOf(c.id)
  const out: string[] = []
  if (c.access !== 'all') out.push(ACCESS_LABEL[c.access] ?? c.access)
  if (p.scope !== 'GROUP') {
    out.push(p.scope === 'ALL' ? '跨群' : '指定群')
    if (p.showUser) out.push('露 QQ 号')
    else if (p.showGroup) out.push('标群号')
  }
  if (p.allowPrivate) out.push('私聊开')
  return out
}

/** 偏离里最"重"的那一档，决定标记的颜色 */
function deviationTone(c: BotCommand) {
  const d = deviations(c)
  if (!d.length) return 'neutral'
  if (d.includes('跨群') || d.includes('露 QQ 号')) return 'bad'
  return 'warn'
}

/** 勾选框的 change 事件要读 target.checked，模板里转型不好读，收成一个函数 */
function onCheck(key: 'showGroup' | 'showUser' | 'allowPrivate', e: Event) {
  policy.value[key] = (e.target as HTMLInputElement).checked
}

/** 低频区块折叠：改回复的人不该被参数表和名单挡路 */
const openBlocks = ref({ params: false, acl: false, help: false })
const paramTableRef = ref<InstanceType<typeof ParamTable> | null>(null)

/** “+ 参数”在参数标题行：展开后让参数表插入一行 */
function addParamRow() {
  openBlocks.value.params = true
  nextTick(() => paramTableRef.value?.addParam())
}

/** 只有"白名单/黑名单"两档才需要录名单，其余档位下这一块根本不该出现 */
const needsList = computed(() =>
  ['whitelist', 'blacklist'].includes(editing.value?.access ?? 'all')
)

function toggleBlock(key: 'params' | 'acl' | 'help') {
  openBlocks.value[key] = !openBlocks.value[key]
}

/** 名单里一共填了几个号，折叠时也要能看出"这里动过" */
const aclCount = computed(() => {
  const n = [acl.value.whiteUsers, acl.value.whiteGroups,
    acl.value.blackUsers, acl.value.blackGroups]
    .reduce((sum, text) => sum + parseIds(text).length, 0)
  return n ? n + ' 条' : '空'
})

/**
 * 选了一个动作之后把能自动填的都填上。
 *
 * <p>新手不需要先搞懂"路径怎么分层""参数表怎么写"才能用上定时输出 ——
 * 选个动作，路径与参数就位，他只管写要发的话。填进去的都是可改的。
 */
function onActionPicked(id: string) {
  patch({ action: id })
  const found = (actions.value?.actions ?? []).find((a) => a.id === id)
  if (!found) return
  if (!(editing.value?.trigger ?? '').trim() && found.defaultPath) {
    patch({ trigger: found.defaultPath })
  }
  if (!paramRows.value.length && found.params.length) {
    setParams(found.params.map((x) => ({ ...x })))
    openBlocks.value.params = true
  }
}

function parseIds(text: string): number[] {
  return text.split(/[\s,，、]+/).map((s) => s.trim()).filter(Boolean)
    .map(Number).filter((n) => Number.isFinite(n) && n > 0)
}

function toLines(ids: number[]): string {
  return ids.join('\n')
}

function aclBody(form: { whiteUsers: string; whiteGroups: string; blackUsers: string; blackGroups: string }) {
  return {
    white: [
      ...parseIds(form.whiteUsers).map((v) => ({ type: 'uid', value: v })),
      ...parseIds(form.whiteGroups).map((v) => ({ type: 'gid', value: v })),
    ],
    black: [
      ...parseIds(form.blackUsers).map((v) => ({ type: 'uid', value: v })),
      ...parseIds(form.blackGroups).map((v) => ({ type: 'gid', value: v })),
    ],
  }
}

function fillAcl(target: { whiteUsers: string; whiteGroups: string; blackUsers: string; blackGroups: string },
                 source: CommandAcl) {
  target.whiteUsers = toLines(source.white.filter((a) => a.type === 'uid').map((a) => a.value))
  target.whiteGroups = toLines(source.white.filter((a) => a.type === 'gid').map((a) => a.value))
  target.blackUsers = toLines(source.black.filter((a) => a.type === 'uid').map((a) => a.value))
  target.blackGroups = toLines(source.black.filter((a) => a.type === 'gid').map((a) => a.value))
}

async function loadGlobalAcl() {
  try { fillAcl(globalForm.value, await adminApi.get<CommandAcl>('/acl/global')) } catch { /* 读不到就留空 */ }
}

async function saveGlobalAcl() {
  globalSaving.value = true
  error.value = ''
  try {
    fillAcl(globalForm.value, await adminApi.post<CommandAcl>('/acl/global', aclBody(globalForm.value)))
    globalSaved.value = '已保存'
  } catch (e) { error.value = describeError(e) } finally {
    globalSaving.value = false
  }
}

const VAR_PRIORITY = ['args', 'text', 'user', 'group', 'time', 'date']
const allVars = computed(() => {
  const list = [...(vars.value?.variables ?? [])]
  return list.sort((a, b) => {
    const ia = VAR_PRIORITY.indexOf(a.name)
    const ib = VAR_PRIORITY.indexOf(b.name)
    return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib)
  })
})
const visibleVars = computed(() => showAllVars.value ? allVars.value : allVars.value.slice(0, 6))

/** 页面栏上的「刷新」「+ 新建命令」—— 见文件头注释 */
defineExpose({
  reload: load,
  openNewMenu,
  /** 读数式暴露：视图在渲染时调用，读到 loading 变化会重渲染，按钮的禁用态才跟得上 */
  isLoading: () => loading.value,
})

onMounted(load)
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <!-- 编辑态：整块替换列表。表格上面压一个巨长表单，改一个字段要滚过一整屏 -->
    <div v-if="editing" class="editor">
      <div class="editor-head">
        <div class="editor-ident">
          <div class="editor-kicker">
            <span class="type-badge">{{ isAction ? '动作' : isAgent ? '问答' : '话术' }}</span>
            <span class="editor-mode">{{ editing.id ? '编辑' : '新建' }}</span>
          </div>
          <div class="editor-path" :class="{ 'is-empty': !(editing.trigger ?? '').trim() }">/{{ (editing.trigger ?? '').split('/').filter(Boolean).join(' /') || '命令名' }}</div>
        </div>
        <div class="editor-actions">
          <Button size="sm" @click="editing = null">取消</Button>
          <Button size="sm" variant="primary" @click="save">保存</Button>
        </div>
      </div>

      <div class="editor-grid">
        <!-- 左：这条命令说什么 -->
        <section class="editor-col">
          <div class="col-title">命令</div>

          <label class="row">
            <span class="row-label">命令名</span>
            <div class="trigger-line">
              <span class="slash">/</span>
              <Input
                :model-value="editing.trigger ?? ''"
                :disabled="editing.builtin"
                placeholder="set/output"
                aria-label="命令名"
                @update:model-value="patch({ trigger: $event })"
              />
            </div>
          </label>

          <label class="row">
            <span class="row-label">说明</span>
            <Input
              :model-value="editing.description ?? ''"
              aria-label="说明"
              @update:model-value="patch({ description: $event })"
            />
          </label>

          <div class="row row--block">
            <span class="row-label">{{ isAgent || isAction ? '用法提示' : '回复' }}</span>
            <Input
              :model-value="editing.reply ?? ''"
              multiline
              :rows="7"
              mono
              :placeholder="isAgent
                ? '参数错误提示'
                : isAction
                  ? '留空自动'
                  : '回复内容'"
              aria-label="回复"
              @update:model-value="onReplyInput"
            />
            <div class="vars">
              <span class="vars-label">变量</span>
              <button
                v-for="v in visibleVars" :key="v.name" class="vchip" type="button"
                :class="{ 'vchip--adv': v.advanced }"
                :title="v.label + '，例：' + v.example" @click="insertVar(v.name)"
              >{ {{ v.name }} }</button>
              <button
                v-if="allVars.length > 6"
                type="button"
                class="vars-toggle"
                @click="showAllVars = !showAllVars"
              >{{ showAllVars ? '收起' : '更多' }}</button>
            </div>
            <div class="preview-tools">
              <Input
                :model-value="sampleArgs"
                placeholder="示例参数"
                aria-label="示例参数"
                @update:model-value="onArgsInput"
              />
            </div>
            <div v-if="preview" class="message-preview">
              <div class="preview-head">
                <span>群消息</span>
                <span class="preview-command">/{{ (editing.trigger ?? '').split('/').filter(Boolean).join(' /') || '…' }}</span>
              </div>
              <div class="preview-bubble">{{ preview || '—' }}</div>
            </div>
          </div>
        </section>

        <!-- 右：这条命令的规则 -->
        <section class="editor-col">
          <div class="col-title">设置</div>

          <label class="row">
            <span class="row-label">形态</span>
            <Select
              :model-value="editing.kind ?? 'template'"
              :options="KIND_OPTIONS"
              aria-label="类型"
              @update:model-value="patch({ kind: $event })"
            />
          </label>

          <label v-if="isAction" class="row">
            <span class="row-label">动作</span>
            <Select
              :model-value="editing.action ?? ''"
              :options="actionOptions"
              aria-label="绑定动作"
              @update:model-value="onActionPicked"
            />
          </label>

          <label v-if="isAgent" class="row">
            <span class="row-label">来源</span>
            <Select
              :model-value="editing.mode ?? 'kb'"
              :options="MODE_OPTIONS"
              aria-label="回答来源"
              @update:model-value="patch({ mode: $event })"
            />
          </label>

          <label class="row">
            <span class="row-label">权限</span>
            <Select
              :model-value="editing.access ?? 'all'"
              :options="ACCESS_OPTIONS"
              aria-label="谁能用"
              @update:model-value="patch({ access: $event })"
            />
          </label>

          <label class="row">
            <span class="row-label">范围</span>
            <Select
              :model-value="policy.scope"
              :options="DATA_SCOPES"
              aria-label="数据范围"
              @update:model-value="policy.scope = $event"
            />
          </label>

          <div v-if="policy.scope === 'SOME'" class="row row--block">
            <span class="row-label">群号</span>
            <Input
              :model-value="policy.groups"
              multiline
              :rows="2"
              placeholder="群号"
              aria-label="共享群号"
              @update:model-value="policy.groups = $event"
            />
          </div>

          <div class="checks">
            <label class="check">
              <input type="checkbox" :checked="policy.allowPrivate" @change="onCheck('allowPrivate', $event)" />
              <span>私聊</span>
            </label>
            <template v-if="policy.scope !== 'GROUP'">
              <label class="check">
                <input type="checkbox" :checked="policy.showGroup" @change="onCheck('showGroup', $event)" />
                <span>显示群</span>
              </label>
              <label class="check">
                <input type="checkbox" :checked="policy.showUser" @change="onCheck('showUser', $event)" />
                <span>显示 QQ</span>
              </label>
            </template>
          </div>

          <!-- 低频项折叠：改回复的人不该被参数表和名单挡路 -->
          <div class="block">
            <div class="block-head block-head--with-action">
              <button class="block-toggle" type="button" :aria-expanded="openBlocks.params" @click="toggleBlock('params')">
                <Icon name="chevron-right" :size="14" class="block-chev" :class="{ open: openBlocks.params }" />
                <span>参数</span>
                <span class="block-count">{{ paramRows.length }}</span>
              </button>
              <button class="head-add" type="button" @click="addParamRow">+ 参数</button>
            </div>
            <div v-if="openBlocks.params" class="block-body">
              <ParamTable
                ref="paramTableRef"
                :params="paramRows"
                empty-hint="无参数"
                @update:params="setParams"
              >
                <template #actions>
                  <Button v-if="isAction && editing.action" size="sm" @click="loadActionParams">载入动作参数</Button>
                </template>
              </ParamTable>
            </div>
          </div>

          <div v-if="needsList" class="block">
            <button class="block-head" type="button" :aria-expanded="openBlocks.acl" @click="toggleBlock('acl')">
              <Icon name="chevron-right" :size="14" class="block-chev" :class="{ open: openBlocks.acl }" />
              <span>名单</span>
              <span class="block-count">{{ aclCount }}</span>
            </button>
            <div v-if="openBlocks.acl" class="block-body">
              <div class="acl-grid">
                <div>
                  <div class="acl-k good">白名单 · QQ</div>
                  <Input :model-value="acl.whiteUsers" multiline :rows="2" placeholder="一行一个" aria-label="白名单 QQ 号" @update:model-value="acl.whiteUsers = $event" />
                </div>
                <div>
                  <div class="acl-k good">白名单 · 群</div>
                  <Input :model-value="acl.whiteGroups" multiline :rows="2" placeholder="整群" aria-label="白名单群号" @update:model-value="acl.whiteGroups = $event" />
                </div>
                <div>
                  <div class="acl-k bad">黑名单 · QQ</div>
                  <Input :model-value="acl.blackUsers" multiline :rows="2" placeholder="一行一个" aria-label="黑名单 QQ 号" @update:model-value="acl.blackUsers = $event" />
                </div>
                <div>
                  <div class="acl-k bad">黑名单 · 群</div>
                  <Input :model-value="acl.blackGroups" multiline :rows="2" placeholder="整个群" aria-label="黑名单群号" @update:model-value="acl.blackGroups = $event" />
                </div>
              </div>
            </div>
          </div>

          <div class="block">
            <button class="block-head" type="button" :aria-expanded="openBlocks.help" @click="toggleBlock('help')">
              <Icon name="chevron-right" :size="14" class="block-chev" :class="{ open: openBlocks.help }" />
              <span>帮助</span>
              <span class="block-count">{{ editing.help ? '手写' : '自动' }}</span>
            </button>
            <div v-if="openBlocks.help" class="block-body">
              <Input
                :model-value="editing.help ?? ''"
                multiline
                :rows="4"
                mono
                placeholder="留空自动"
                aria-label="单命令帮助"
                @update:model-value="patch({ help: $event })"
              />
            </div>
          </div>
        </section>
      </div>
    </div>


    <!-- 板块编辑器：与指令编辑器互斥，都是"整块替换列表" -->
    <BoardEditor
      v-if="boardEditing !== undefined"
      :board="boardEditing"
      :saving="boardSaving"
      :error="boardError"
      @save="saveBoard"
      @cancel="cancelBoard"
      @remove="removeBoard"
    />

    <section v-if="!editing && boardEditing === undefined" class="command-index">
      <header class="index-head">
        <div class="index-title">
                    <h2>命令</h2>
        </div>
        <div class="index-meta">
          <span><b>{{ rows.length }}</b> 条</span>
          <span><b>{{ boardCount }}</b> 板块</span>
          <span v-if="search || kindFilter !== 'all'"><b>{{ visibleRows.length }}</b> 匹配</span>
        </div>
        <div class="index-tools">
          <Input v-model="search" placeholder="搜索命令" aria-label="搜索命令" />
          <div class="filters" role="group" aria-label="按类型筛选">
            <button
              v-for="f in FILTERS"
              :key="f.value"
              type="button"
              class="filter"
              :class="{ active: kindFilter === f.value }"
              @click="kindFilter = f.value"
            >{{ f.label }}</button>
          </div>
        </div>
      </header>

      <div class="ledger-wrap" role="group" aria-label="命令表" tabindex="0">
        <table v-if="visibleRows.length" class="ledger">
          <!--
            列宽用 colgroup 定死，配合 CSS 的 table-layout: fixed。
            上一版是自动布局：调用列被撑到 346px，说明反而只有 324px ——
            真正要读的那一列最窄。现在调用 208、限制 132、使用 60、状态 104、操作 216，
            剩下的全部给说明，它一定是最宽的那一列。
          -->
          <colgroup>
            <col class="c-call">
            <col class="c-desc">
            <col class="c-rule">
            <col class="c-use">
            <col class="c-state">
            <col class="c-ops">
          </colgroup>
          <thead>
            <tr>
              <th>调用</th>
              <th class="th-desc">说明</th>
              <th>限制</th>
              <th class="num">使用</th>
              <th>状态</th>
              <th class="ops">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in visibleRows" :key="row.key" :class="{ 'is-off': !row.enabled }">
              <td class="cell-call">
                <span class="cmd-path"><span class="slash">/</span>{{ row.path.split('/').join(' /') }}</span>
              </td>
              <td class="cell-desc">{{ row.description }}</td>
              <td class="cell-rule">{{ rowRuleText(row) }}</td>
              <td class="cell-use num" :title="'近 ' + statsDays + ' 天'">{{ rowUsage(row) }}</td>
              <td class="cell-state">
                <span class="state-dot" :class="{ on: row.enabled }"></span>
                <span>{{ row.enabled ? '启用' : '停用' }}</span>
                <span v-if="row.builtin" class="builtin">内置</span>
              </td>
              <td class="cell-ops">
                <span v-if="row.source === 'system'" class="row-muted">只读</span>
                <template v-else>
                  <button type="button" class="row-action" @click="editRow(row)">编辑</button>
                  <button
                    v-if="row.source === 'board' && row.board"
                    type="button"
                    class="row-action"
                    @click="openRecords(row.board)"
                  >记录</button>
                  <button type="button" class="row-action" @click="toggleRow(row)">{{ row.enabled ? '停用' : '启用' }}</button>
                  <button
                    v-if="!row.builtin"
                    type="button"
                    class="row-action danger"
                    @click="removeRow(row)"
                  >删除</button>
                </template>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else class="ledger-empty">
          <p>{{ rows.length ? '没有匹配的命令' : '还没有命令' }}</p>
          <button
            v-if="rows.length"
            type="button"
            class="text-action"
            @click="search = ''; kindFilter = 'all'"
          >清除筛选</button>
          <button v-else type="button" class="text-action" @click="openNewMenu">新建命令</button>
        </div>
      </div>
    </section>

    <section v-if="!editing && boardEditing === undefined" class="usage-section">
      <header class="section-head">
        <div>
                    <h3>使用</h3>
        </div>
        <div class="range-pick">
          <Select
            :model-value="String(statsDays)"
            :options="RANGE_OPTIONS"
            aria-label="统计范围"
            @update:model-value="onRangeChange"
          />
        </div>
      </header>
      <div class="usage-grid">
        <div class="usage-col">
          <div class="usage-label">最常用</div>
          <div v-if="stats?.topUsed?.length" class="bars">
            <div v-for="t in stats.topUsed" :key="t.trigger" class="bar-row">
              <span class="bar-path">/{{ t.trigger }}</span>
              <span class="bar-track"><i :style="{ width: (t.count / maxUsed * 100) + '%' }"></i></span>
              <span class="bar-count num">{{ t.count }}</span>
            </div>
          </div>
          <p v-else class="empty-line">暂无记录</p>
        </div>
        <div class="usage-col">
          <div class="usage-label">未配置</div>
          <div v-if="stats?.unmatched?.length" class="bars">
            <div v-for="u in stats.unmatched" :key="u.trigger" class="bar-row">
              <span class="bar-path miss">/{{ u.trigger }}</span>
              <span class="bar-track miss"><i :style="{ width: (u.count / maxMiss * 100) + '%' }"></i></span>
              <span class="bar-count num">{{ u.count }}</span>
            </div>
          </div>
          <p v-else class="empty-line">暂无记录</p>
        </div>
      </div>
    </section>

    <details v-if="!editing && boardEditing === undefined" class="acl-fold">
      <summary>
        <span class="fold-title">全局名单</span>
        <span class="fold-meta">{{ globalAclCount ? globalAclCount + ' 组' : '未设置' }}</span>
      </summary>
      <div class="acl-grid">
        <div>
          <div class="acl-k good">白名单 · QQ</div>
          <Input
            :model-value="globalForm.whiteUsers" multiline :rows="2" placeholder="一行一个"
            aria-label="全局白名单 QQ 号" @update:model-value="globalForm.whiteUsers = $event"
          />
        </div>
        <div>
          <div class="acl-k good">白名单 · 群</div>
          <Input
            :model-value="globalForm.whiteGroups" multiline :rows="2" placeholder="整群"
            aria-label="全局白名单群号" @update:model-value="globalForm.whiteGroups = $event"
          />
        </div>
        <div>
          <div class="acl-k bad">黑名单 · QQ</div>
          <Input
            :model-value="globalForm.blackUsers" multiline :rows="2" placeholder="一行一个"
            aria-label="全局黑名单 QQ 号" @update:model-value="globalForm.blackUsers = $event"
          />
        </div>
        <div>
          <div class="acl-k bad">黑名单 · 群</div>
          <Input
            :model-value="globalForm.blackGroups" multiline :rows="2" placeholder="整群"
            aria-label="全局黑名单群号" @update:model-value="globalForm.blackGroups = $event"
          />
        </div>
      </div>
      <div class="acl-actions">
        <span v-if="globalSaved" class="saved">{{ globalSaved }}</span>
        <Button size="sm" variant="primary" :disabled="globalSaving" @click="saveGlobalAcl">保存</Button>
      </div>
    </details>

    <BoardRecordsModal
      :open="recordsBoard !== null"
      :board="recordsBoard"
      @close="recordsBoard = null"
    />

    <NewCommandModal
      :open="newOpen"
      @pick="pickNew"
      @close="newOpen = false"
    />

    <PromptEditor
      :open="promptEditing !== null"
      :command="promptEditing"
      @close="promptEditing = null"
      @changed="load"
    />
  </div>
</template>

<style scoped>
/* 宽度交给父页面的 AdminPage —— 面板自己定 max-width 会和页面容器打架 */
.panel-wrap {
  display: flex;
  flex-direction: column;
  gap: var(--sp-6);
}

/* ==================== 命令索引 ====================
   一页只留一个主角：命令账本。区块之间用横线和留白分，不再套卡片。 */
.command-index { display: flex; flex-direction: column; gap: var(--sp-3); }
.index-head {
  display: grid;
  grid-template-columns: auto auto 1fr;
  align-items: end;
  gap: var(--sp-5);
  padding-bottom: var(--sp-4);
  border-bottom: 1px solid var(--hairline-lit);
}
.eyebrow {
  font-size: 10.5px;
  letter-spacing: .22em;
  text-transform: uppercase;
  color: var(--ink-4);
}
.index-title h2 {
  margin: 2px 0 0;
  font-family: var(--font-title);
  font-size: 28px;
  font-weight: var(--fw-semi);
  letter-spacing: .02em;
  line-height: var(--lh-tight);
}
.index-meta {
  display: flex;
  gap: var(--sp-4);
  padding-bottom: 4px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  white-space: nowrap;
}
.index-meta b {
  margin-right: 2px;
  font-family: var(--font-mono);
  font-weight: var(--fw-medium);
  color: var(--ink);
}
.index-tools {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.index-tools :deep(.inp) { width: 210px; }
.filters { display: flex; align-items: center; gap: 1px; }
.filter {
  padding: 5px 7px;
  background: none;
  border: 0;
  border-bottom: 1px solid transparent;
  color: var(--ink-3);
  font-size: var(--fs-xs);
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease),
              border-color var(--dur-fast) var(--ease);
}
.filter:hover { color: var(--ink); }
.filter.active { color: var(--ember); border-bottom-color: var(--ember); }
.filter:focus-visible { outline: 2px solid var(--flame); outline-offset: 1px; }

.ledger-wrap { overflow-x: auto; overscroll-behavior-x: contain; }
/* table-layout: fixed 让 colgroup 的列宽真正生效；自动布局会把长内容那列撑开 */
.ledger { width: 100%; border-collapse: collapse; table-layout: fixed; }

/* 列宽只有这六个数。说明列不写宽度 = 吃掉剩余空间，因此永远是最宽的一列 */
.c-call { width: 208px; }
.c-rule { width: 132px; }
.c-use { width: 60px; }
.c-state { width: 104px; }
.c-ops { width: 216px; }
.ledger th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 7px 10px;
  border-bottom: 1px solid var(--edge);
  background: var(--stone-200);
  color: var(--ink-4);
  font-size: 10.5px;
  font-weight: var(--fw-medium);
  letter-spacing: .14em;
  text-align: left;
  text-transform: uppercase;
  white-space: nowrap;
}
.ledger th.ops { text-align: right; }
.ledger td {
  padding: 12px 10px;
  border-bottom: 1px solid var(--hairline);
  vertical-align: middle;
}
.ledger tbody tr {
  transition: background var(--dur-fast) var(--ease);
}
.ledger tbody tr:hover { background: var(--surface-hover); }
.ledger tbody tr:hover td:first-child { box-shadow: inset 2px 0 0 var(--ember); }
.ledger tbody tr.is-off { opacity: .55; }
.cell-call { overflow: hidden; }
.cmd-path {
  display: block;
  font-family: var(--font-mono);
  font-size: var(--fs-base);
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.slash { color: var(--ember); }
.cell-desc {
  color: var(--ink-2);
  font-size: var(--fs-sm);
  /* 说明列吃掉剩余宽度，长句在单元格内换行，不要撑出表格 */
  overflow-wrap: anywhere;
  line-height: 1.6;
}
.cell-rule {
  color: var(--ink-3);
  font-size: var(--fs-xs);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cell-use { width: 58px; color: var(--ink-2); }
.cell-state { color: var(--ink-3); font-size: var(--fs-xs); white-space: nowrap; }
.state-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 6px;
  border-radius: 50%;
  background: var(--ink-4);
  vertical-align: 1px;
}
.state-dot.on { background: var(--vital); box-shadow: 0 0 0 3px var(--vital-veil); }
.builtin { margin-left: 8px; color: var(--ink-4); font-size: 10.5px; letter-spacing: .08em; }
.cell-ops { text-align: right; white-space: nowrap; }
/*
 * 行内动作：上一版是「3px 内边距的灰字」，看着像表里的普通文字，
 * 用户根本不知道那三个词能点。现在给它们按钮的形：细边、浅底、26px 高。
 * 不给主色填充 —— 一行里有三四个按钮，全都点亮就等于没有重点。
 */
.row-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 44px;
  min-height: 26px;
  padding: 0 8px;
  border: 1px solid var(--edge);
  border-radius: var(--r-xs);
  background: var(--stone-400);
  color: var(--ink-2);
  font-size: var(--fs-xs);
  line-height: 1;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease),
              background var(--dur-fast) var(--ease);
}
.row-action + .row-action { margin-left: 6px; }
.row-action:hover {
  border-color: var(--ember);
  color: var(--ember-hot);
  background: var(--ember-veil);
}
/* 删除是唯一会掉数据的动作：悬停变红，和另外几个区分开 */
.row-action.danger:hover {
  border-color: var(--blight);
  color: var(--blight-lift);
  background: var(--blight-veil);
}
.row-action:active { background: var(--stone-300); }
.row-action:focus-visible { outline: 2px solid var(--ember); outline-offset: 1px; }
.row-muted { color: var(--ink-4); font-size: var(--fs-xs); }
.ledger-empty { padding: 48px 0; text-align: center; color: var(--ink-3); }
.ledger-empty p { margin: 0 0 8px; }
.text-action {
  padding: 2px 0;
  background: none;
  border: 0;
  color: var(--ember);
  font-size: var(--fs-sm);
  cursor: pointer;
}
.text-action:hover { color: var(--ember-hot); }
.text-action:focus-visible { outline: 2px solid var(--flame); outline-offset: 2px; }

.usage-section { border-top: 1px solid var(--hairline); padding-top: var(--sp-5); }
.section-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-4);
  margin-bottom: var(--sp-4);
}
.section-head h3 {
  margin: 2px 0 0;
  font-family: var(--font-title);
  font-size: 20px;
  font-weight: var(--fw-semi);
  letter-spacing: .02em;
}
.usage-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-6); }
.usage-label {
  margin-bottom: 10px;
  color: var(--ink-4);
  font-size: 10.5px;
  letter-spacing: .14em;
  text-transform: uppercase;
}
.bars { display: flex; flex-direction: column; gap: 8px; }
.bar-row {
  display: grid;
  grid-template-columns: minmax(96px, 1fr) 2fr 34px;
  gap: 10px;
  align-items: center;
}
.bar-path {
  overflow: hidden;
  color: var(--ink-2);
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bar-path.miss { color: var(--blight-lift); }
.bar-track {
  height: 4px;
  overflow: hidden;
  border-radius: 2px;
  background: var(--stone-void);
}
.bar-track i {
  display: block;
  height: 100%;
  border-radius: 2px;
  background: linear-gradient(90deg, var(--ember-deep), var(--ember));
}
.bar-track.miss i { background: linear-gradient(90deg, var(--blight-deep), var(--blight)); }
.bar-count { color: var(--ink-3); font-size: var(--fs-xs); text-align: right; }
.empty-line { margin: 0; color: var(--ink-4); font-size: var(--fs-xs); }

.acl-fold { border-top: 1px solid var(--hairline); padding-top: var(--sp-3); }
.acl-fold summary {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  color: var(--ink-2);
  cursor: pointer;
  list-style: none;
}
.acl-fold summary::-webkit-details-marker { display: none; }
.acl-fold summary::before {
  width: 12px;
  color: var(--ember);
  content: '+';
  font-family: var(--font-mono);
}
.acl-fold[open] summary::before { content: '−'; }
.fold-title { font-size: var(--fs-md); }
.fold-meta { color: var(--ink-4); font-size: var(--fs-xs); }
.acl-fold .acl-grid { margin-top: var(--sp-3); }
.acl-k { margin-bottom: 5px; font-size: var(--fs-xs); letter-spacing: .04em; }
.acl-k.good { color: var(--vital); }
.acl-k.bad { color: var(--blight-lift); }
.acl-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--sp-3);
  margin-top: var(--sp-3);
}
.saved { color: var(--vital); font-size: var(--fs-xs); }

@media (max-width: 1100px) {
  .index-head { grid-template-columns: 1fr auto; }
  .index-tools { grid-column: 1 / -1; justify-content: flex-start; }
}
@media (max-width: 900px) {
  /* 窄屏收起「限制」和「使用」，留住调用、说明、状态与操作 ——
     说明是这一页最该读的列，不能再像上一版那样被优先隐藏。

     ⚠️ 这里必须把固定布局改回自动布局：fixed + colgroup 下隐藏两列，
     列轨道还按原来的序号吃宽度，实测会错位成「状态 132px、操作 60px、说明 0」。
     换成 auto 之后，隐藏的列真正消失，说明列重新吃到剩余宽度。 */
  .ledger { table-layout: auto; }
  .c-call, .c-rule, .c-use, .c-state, .c-ops { width: auto; }
  /* 调用列给个下限：纯 auto 会被压到 64px，只剩一个「/li…」 */
  .cell-call { min-width: 96px; }
  .ledger th:nth-child(3), .ledger td:nth-child(3),
  .ledger th:nth-child(4), .ledger td:nth-child(4) { display: none; }
  .usage-grid { grid-template-columns: 1fr; gap: var(--sp-5); }
}
/* 手机上 6 列必然放不下。再收起「状态」—— 停用的行整行已经变淡，
   状态信息并没有丢；剩下调用 / 说明 / 操作三列，说明才能真正读到。 */
@media (max-width: 640px) {
  .ledger th:nth-child(5), .ledger td:nth-child(5) { display: none; }
  .row-action { min-width: 36px; padding: 0 6px; }
  .row-action + .row-action { margin-left: 4px; }
}

@media (prefers-reduced-motion: reduce) {
  .ledger tbody tr { transition: none; }
}

/* 工具栏已上移到页面栏（BotConfigView 的 AdminPage#actions），
   这里只剩内容本身的排布。 */

.trigger-line { display: flex; align-items: center; gap: var(--sp-2); }
.slash { flex: none; color: var(--copper); font-family: var(--font-mono); }

.vars { display: flex; flex-wrap: wrap; align-items: center; gap: var(--sp-1) var(--sp-2); }
.vars-label { margin-right: 2px; color: var(--ink-4); font-size: 10.5px; letter-spacing: .14em; }
.vars--adv { padding-top: 2px; }
.vars-toggle {
  padding: 2px 0;
  background: none;
  border: 0;
  color: var(--ink-3);
  font-size: 11px;
  cursor: pointer;
}
.vars-toggle:hover { color: var(--ember); }
/* 变量胶囊是可点元素，共享 Button 覆盖不到这块：
   悬浮必须【背景 + 边缘】同时变 —— 只改文字颜色在深底上看不出能不能点。
   底色沿用 Button 的规矩：基础压到 stone，悬浮抬到 raised。 */
.vchip {
  background: var(--surface-raised);
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  color: var(--mist);
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 3px 9px;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease),
              border-color var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease);
}
.vchip:hover {
  background: var(--bg-raised);
  border-color: var(--edge-hover);
  color: var(--flame-bright);
}
.vchip:active { background: var(--surface-active); }
.vchip:focus-visible { outline: 2px solid var(--flame); outline-offset: 2px; }
.vchip--adv { border-color: var(--copper-dim); color: var(--amber); }

.message-preview { margin-top: var(--sp-2); }
.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-bottom: 6px;
  color: var(--ink-4);
  font-size: 10.5px;
  letter-spacing: .14em;
  text-transform: uppercase;
}
.preview-command { color: var(--mist); font-family: var(--font-mono); letter-spacing: 0; text-transform: none; }
.preview-bubble {
  min-height: 52px;
  padding: 10px 12px;
  background: var(--stone-200);
  border-left: 2px solid var(--ember);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  color: var(--ink-2);
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.preview-tools { margin-top: var(--sp-2); }

.form-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
  margin-top: var(--sp-4);
}
.form-buttons { display: flex; gap: var(--sp-2); margin-left: auto; }
.note { font-size: var(--fs-meta); margin: 0; }
/* ---------- 编辑态 ----------
   两栏：左边是"这条命令说什么"，右边是"它按什么规则运行"。
   行内标签在左、控件在右，比一列 Field 更省纵向空间，也更容易扫读。 */
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
.editor-col { display: flex; flex-direction: column; gap: var(--sp-3); }
.col-title {
  padding-bottom: var(--sp-2);
  border-bottom: 1px solid var(--hairline);
  color: var(--ink-4);
  font-size: 10.5px;
  letter-spacing: .14em;
  text-transform: uppercase;
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
  background: var(--stone-400); border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  color: var(--ink-2); font-size: var(--fs-meta); text-align: left; cursor: pointer;
}
.block-head:hover { background: var(--stone-500); border-color: var(--edge-hover); color: var(--ink); }
.block-chev { color: var(--ember); transition: transform var(--dur-fast) var(--ease); }
.block-chev.open { transform: rotate(90deg); }
.block-count { margin-left: auto; color: var(--ink-4); font-family: var(--font-mono); }
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
  margin-top: 8px; padding: 12px 14px;
  background: var(--stone-400); border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  box-shadow: inset 0 1px 0 var(--hairline-lit);
}

.acl-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); }
.range-pick { width: 128px; }

@media (max-width: 1000px) {
  .editor-grid { grid-template-columns: 1fr; }
  .row { flex-direction: column; align-items: stretch; gap: 4px; }
  .row-label { flex: none; }
}
@media (max-width: 900px) {
  .acl-grid { grid-template-columns: 1fr; }
}
@media (prefers-reduced-motion: reduce) {
  .block-chev { transition: none; }
}
</style>
