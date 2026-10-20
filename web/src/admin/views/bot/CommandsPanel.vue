<script setup lang="ts">
/**
 * 「指令」Tab 面板。
 *
 * 为什么是面板而不是页面：管理端要把「设置」和「指令」并进同一个「Bot 配置」页，
 * 页面内用二级目录切换。页面级外壳（AdminPage、Tabs、页面标题）由父页面提供，
 * 所以这里不接收 props、不 emit 事件，也不渲染 AdminPage ——
 * 只负责本 tab 的内容。页面栏上的「刷新」「+ 新建指令」由父页面 BotConfigView
 * 渲染，动作经 defineExpose 抛出（按钮调的是面板内部状态，视图拿不到）。
 *
 * 接口调用（/commands 系列）与重构前逐个字段一致，本次是纯结构与外观迁移。
 */
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type {
  CommandList, BotCommand, CommandVariables, CommandStats, CommandActions, AdminParamSpec,
  CommandPolicy, CommandPolicies, CommandAcl,
} from '@shared/api/types'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Field from '@shared/ui/Field.vue'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Notice from '@shared/ui/Notice.vue'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'

const loading = ref(false)
const error = ref('')
const data = ref<CommandList | null>(null)
const vars = ref<CommandVariables | null>(null)
const stats = ref<CommandStats | null>(null)
/** 已注册的动作：只有 action 类命令才用得上 */
const actions = ref<CommandActions | null>(null)

/** 编辑中的指令（null = 没在编辑） */
const editing = ref<Partial<BotCommand> | null>(null)
/** 实时预览结果 */
const preview = ref('')
/** 预览 {args} 用的示例参数 */
const sampleArgs = ref('')

/**
 * 指令类型 —— 这个选择决定了"会不会花钱"：
 *   template 即时回话术，不调模型、不计费，所以跑在 Guard 之前秒回；
 *   agent    交给大模型，会走 Guard 与成本预算。
 */
const KIND_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'template', label: '话术（直接回复，不调模型）' },
  { value: 'agent', label: '智能问答（调模型，计入预算）' },
  { value: 'action', label: '动作（写库 / 查库，不调模型）' },
]

/** 准入档位。五选一，值直接落库 */
const ACCESS_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'all', label: '全体' },
  { value: 'admin', label: '管理员' },
  { value: 'owner', label: '群主' },
  { value: 'whitelist', label: '白名单（只对名单里的人）' },
  { value: 'blacklist', label: '黑名单（名单里的人不能用）' },
]

/** 参数类型。与后端 ParamType 一一对应 */
const PARAM_TYPES: Array<{ value: string; label: string }> = [
  { value: 'text', label: '文本' },
  { value: 'int', label: '整数' },
  { value: 'moment', label: '时间点' },
  { value: 'date', label: '日期' },
  { value: 'enum', label: '枚举' },
]

/** agent 类的知识来源。命名上刻意说"走/不走知识库"，而不是"温度""权重"这种看不出效果的词 */
const MODE_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'kb', label: '走知识库（默认）' },
  { value: 'none', label: '不用知识库（纯模型回答）' },
]

const isAgent = computed(() => editing.value?.kind === 'agent')
const isAction = computed(() => editing.value?.kind === 'action')
/** 动作下拉：值是动作 id，直接存进库里那一列 */
const actionOptions = computed(() =>
  (actions.value?.actions ?? []).map((a) => ({ value: a.id, label: a.id }))
)
/** 编辑中的参数表。新建时是空数组，模板里靠它渲染行 */
const paramRows = computed<AdminParamSpec[]>(() => editing.value?.params ?? [])

const kindHint = computed(() => {
  if (isAction.value) {
    return '动作：群友发「/路径 #名字-值# 文本」→ 交给绑定的执行器写库或查库。不调模型、不计费。'
  }
  return isAgent.value
    ? '智能问答：群友发「/路径 问题文本」→ 交给大模型回答。会走 Guard 与成本预算（会花钱），自由文本就是问题。'
    : '话术：群友发「/路径 #名字-值# 文本」→ 直接回下面的文本。不调模型、不计费，所以是秒回。'
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await adminApi.get<CommandList>('/commands')
    vars.value = await adminApi.get<CommandVariables>('/commands/variables')
    stats.value = await adminApi.get<CommandStats>('/commands/stats?days=30')
    actions.value = await adminApi.get<CommandActions>('/commands/actions')
    const pol = await adminApi.get<CommandPolicies>('/commands/policies')
    policies.value = Object.fromEntries(pol.policies.map((x) => [x.id, x]))
    await loadGlobalAcl()
  } catch (e) { error.value = describeError(e) } finally {
    loading.value = false
  }
}

function newCommand() {
  editing.value = {
    trigger: '', reply: '', description: '', scope: 'all', enabled: true, sortOrder: 0,
    kind: 'template', mode: 'kb', params: [], help: '', action: null, access: 'all',
  }
  preview.value = ''
  sampleArgs.value = ''
  policy.value = { scope: 'GROUP', groups: '', showGroup: false, showUser: false, allowPrivate: false }
  acl.value = { whiteUsers: '', whiteGroups: '', blackUsers: '', blackGroups: '' }
}

function edit(c: BotCommand) {
  editing.value = { ...c }
  sampleArgs.value = ''
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

/**
 * 列表里怎么显示这条指令。
 *
 * 路径每一层都补上斜杠，与群里写法一致；有参数表的把参数摆出来 ——
 * 否则群友看到 /set /output 直接发出去，只会收到一句用法提示。
 */
function displayOf(c: BotCommand) {
  const path = '/' + c.trigger.split('/').join(' /')
  const ps = c.params ?? []
  if (ps.length) {
    return path + ' ' + ps.map((p) => '#' + p.name + '-' + (p.example || p.name) + '#').join(' ')
  }
  return path + (c.kind === 'agent' ? ' <问题>' : '')
}

/* ==================== 参数表编辑 ==================== */

function setParams(list: AdminParamSpec[]) {
  patch({ params: list })
}

function patchParam(i: number, p: Partial<AdminParamSpec>) {
  const list = paramRows.value.slice()
  list[i] = { ...list[i], ...p }
  setParams(list)
}

function addParam() {
  setParams([...paramRows.value, { name: '', type: 'text', required: false, default: '', example: '', description: '', multiple: false }])
}

function removeParam(i: number) {
  setParams(paramRows.value.filter((_, idx) => idx !== i))
}

/** checkbox 的 change 事件要读 target.checked，模板里直接转型不好读，收成一个函数 */
function onRequiredChange(i: number, e: Event) {
  patchParam(i, { required: (e.target as HTMLInputElement).checked })
}

/** enum 的候选值在表单里是逗号分隔的文本 */
function optionsText(p: AdminParamSpec) {
  return (p.options ?? []).join(', ')
}

function onOptionsChange(i: number, v: string) {
  patchParam(i, { options: v.split(/[,，]/).map((s) => s.trim()).filter(Boolean) })
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

// ==================== 权限与名单 ====================

const ACCESS_LABEL: Record<string, string> = {
  all: '全体', admin: '管理员', owner: '群主', whitelist: '白名单', blacklist: '黑名单',
}

const DATA_SCOPES: Array<{ value: string; label: string }> = [
  { value: 'GROUP', label: '只看本群' },
  { value: 'SOME', label: '只在这几个群之间' },
  { value: 'ALL', label: '所有群' },
]

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

function onMultipleChange(i: number, e: Event) {
  patchParam(i, { multiple: (e.target as HTMLInputElement).checked })
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

const basicVars = computed(() => (vars.value?.variables ?? []).filter(v => !v.advanced))
const advVars = computed(() => (vars.value?.variables ?? []).filter(v => v.advanced))

/** 页面栏上的「刷新」「+ 新建指令」—— 见文件头注释 */
defineExpose({
  reload: load,
  newCommand,
  /** 读数式暴露：视图在渲染时调用，读到 loading 变化会重渲染，按钮的禁用态才跟得上 */
  isLoading: () => loading.value,
})

onMounted(load)
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <!-- 编辑区：原来就是插在列表上方的一块，位置与交互保留，只换成共享 Panel -->
    <!-- 编辑态：整块替换列表。表格上面压一个巨长表单，改一个字段要滚过一整屏 -->
    <div v-if="editing" class="editor">
      <div class="editor-head">
        <div>
          <div class="editor-eyebrow">编辑指令</div>
          <div class="editor-path">/{{ (editing.trigger ?? '').split('/').filter(Boolean).join(' /') || '新指令' }}</div>
        </div>
        <div class="editor-actions">
          <Button size="sm" @click="editing = null">取消</Button>
          <Button size="sm" variant="primary" @click="save">保存</Button>
        </div>
      </div>

      <div class="editor-grid">
        <!-- 左：这条命令说什么 -->
        <section class="editor-col">
          <div class="col-title">内容</div>

          <label class="row">
            <span class="row-label">路径</span>
            <div class="trigger-line">
              <span class="slash">/</span>
              <Input
                :model-value="editing.trigger ?? ''"
                placeholder="set/output（用 / 分层）"
                aria-label="路径"
                @update:model-value="patch({ trigger: $event })"
              />
            </div>
          </label>

          <label class="row">
            <span class="row-label">说明</span>
            <Input
              :model-value="editing.description ?? ''"
              placeholder="一句话说清它干什么"
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
                ? '参数不合格时回这句'
                : isAction
                  ? '留空则按参数表自动生成'
                  : '支持 {变量} 与 {args}'"
              aria-label="回复"
              @update:model-value="onReplyInput"
            />
            <div class="vars">
              <button
                v-for="v in basicVars"
                :key="v.name"
                class="vchip"
                type="button"
                :title="v.label + '（例：' + v.example + '）'"
                @click="insertVar(v.name)"
              >{ {{ v.name }} }</button>
              <button
                v-for="v in advVars"
                :key="v.name"
                class="vchip vchip--adv"
                type="button"
                @click="insertVar(v.name)"
              >{ {{ v.name }} }</button>
            </div>
            <p v-if="preview" class="preview-line">{{ preview }}</p>
          </div>
        </section>

        <!-- 右：这条命令的规则 -->
        <section class="editor-col">
          <div class="col-title">规则</div>

          <label class="row">
            <span class="row-label">类型</span>
            <Select
              :model-value="editing.kind ?? 'template'"
              :options="KIND_OPTIONS"
              aria-label="类型"
              @update:model-value="patch({ kind: $event })"
            />
          </label>

          <label v-if="isAction" class="row">
            <span class="row-label">绑定动作</span>
            <Select
              :model-value="editing.action ?? ''"
              :options="actionOptions"
              aria-label="绑定动作"
              @update:model-value="patch({ action: $event })"
            />
          </label>

          <label v-if="isAgent" class="row">
            <span class="row-label">回答来源</span>
            <Select
              :model-value="editing.mode ?? 'kb'"
              :options="MODE_OPTIONS"
              aria-label="回答来源"
              @update:model-value="patch({ mode: $event })"
            />
          </label>

          <label class="row">
            <span class="row-label">谁能用</span>
            <Select
              :model-value="editing.access ?? 'all'"
              :options="ACCESS_OPTIONS"
              aria-label="谁能用"
              @update:model-value="patch({ access: $event })"
            />
          </label>

          <label class="row">
            <span class="row-label">数据范围</span>
            <Select
              :model-value="policy.scope"
              :options="DATA_SCOPES"
              aria-label="数据范围"
              @update:model-value="policy.scope = $event"
            />
          </label>

          <div v-if="policy.scope === 'SOME'" class="row row--block">
            <span class="row-label">共享群</span>
            <Input
              :model-value="policy.groups"
              multiline
              :rows="2"
              placeholder="一行一个群号"
              aria-label="共享群号"
              @update:model-value="policy.groups = $event"
            />
          </div>

          <div class="checks">
            <label class="check">
              <input type="checkbox" :checked="policy.allowPrivate" @change="onCheck('allowPrivate', $event)" />
              <span>允许私聊</span>
            </label>
            <template v-if="policy.scope !== 'GROUP'">
              <label class="check">
                <input type="checkbox" :checked="policy.showGroup" @change="onCheck('showGroup', $event)" />
                <span>标出来源群</span>
              </label>
              <label class="check">
                <input type="checkbox" :checked="policy.showUser" @change="onCheck('showUser', $event)" />
                <span>标出来源 QQ</span>
              </label>
            </template>
          </div>

          <!-- 低频项折叠：改回复的人不该被参数表和名单挡路 -->
          <div class="block">
            <button class="block-head" type="button" @click="toggleBlock('params')">
              <span class="caret">{{ openBlocks.params ? '−' : '+' }}</span>
              参数表
              <span class="faint">{{ paramRows.length ? paramRows.length + ' 个' : '没有' }}</span>
            </button>
            <div v-if="openBlocks.params" class="block-body">
              <p v-if="!paramRows.length" class="faint note">群友发过来的整段都当文本，模板里用 {args} 取。</p>
              <div v-for="(p, i) in paramRows" :key="i" class="param-row">
                <Input :model-value="p.name" placeholder="名字" aria-label="参数名" @update:model-value="patchParam(i, { name: $event })" />
                <Select :model-value="p.type" :options="PARAM_TYPES" aria-label="参数类型" @update:model-value="patchParam(i, { type: $event })" />
                <label class="req">
                  <input type="checkbox" :checked="p.required" @change="onRequiredChange(i, $event)" />
                  必填
                </label>
                <Input :model-value="p.example ?? ''" placeholder="示例" aria-label="示例" @update:model-value="patchParam(i, { example: $event })" />
                <Input :model-value="p.description ?? ''" placeholder="说明" aria-label="说明" @update:model-value="patchParam(i, { description: $event })" />
                <label class="req">
                  <input type="checkbox" :checked="p.multiple" @change="onMultipleChange(i, $event)" />
                  可多值
                </label>
                <Button size="sm" variant="danger" @click="removeParam(i)">删</Button>
                <Input
                  v-if="p.type === 'enum'"
                  class="enum-opts"
                  :model-value="optionsText(p)"
                  placeholder="候选值，逗号分开"
                  aria-label="候选值"
                  @update:model-value="onOptionsChange(i, $event)"
                />
              </div>
              <div class="block-actions">
                <Button size="sm" @click="addParam">+ 加参数</Button>
                <Button v-if="isAction && editing.action" size="sm" @click="loadActionParams">载入该动作的参数表</Button>
              </div>
            </div>
          </div>

          <div class="block">
            <button class="block-head" type="button" @click="toggleBlock('acl')">
              <span class="caret">{{ openBlocks.acl ? '−' : '+' }}</span>
              名单
              <span class="faint">{{ aclCount }}</span>
            </button>
            <div v-if="openBlocks.acl" class="block-body">
              <div class="acl-grid">
                <div>
                  <div class="acl-k"><Tag tone="good">白名单 · QQ 号</Tag></div>
                  <Input :model-value="acl.whiteUsers" multiline :rows="2" placeholder="一行一个" aria-label="白名单 QQ 号" @update:model-value="acl.whiteUsers = $event" />
                </div>
                <div>
                  <div class="acl-k"><Tag tone="good">白名单 · 群号</Tag></div>
                  <Input :model-value="acl.whiteGroups" multiline :rows="2" placeholder="整个群" aria-label="白名单群号" @update:model-value="acl.whiteGroups = $event" />
                </div>
                <div>
                  <div class="acl-k"><Tag tone="bad">黑名单 · QQ 号</Tag></div>
                  <Input :model-value="acl.blackUsers" multiline :rows="2" placeholder="一行一个" aria-label="黑名单 QQ 号" @update:model-value="acl.blackUsers = $event" />
                </div>
                <div>
                  <div class="acl-k"><Tag tone="bad">黑名单 · 群号</Tag></div>
                  <Input :model-value="acl.blackGroups" multiline :rows="2" placeholder="整个群" aria-label="黑名单群号" @update:model-value="acl.blackGroups = $event" />
                </div>
              </div>
            </div>
          </div>

          <div class="block">
            <button class="block-head" type="button" @click="toggleBlock('help')">
              <span class="caret">{{ openBlocks.help ? '−' : '+' }}</span>
              单命令帮助
              <span class="faint">{{ editing.help ? '手写' : '自动生成' }}</span>
            </button>
            <div v-if="openBlocks.help" class="block-body">
              <Input
                :model-value="editing.help ?? ''"
                multiline
                :rows="4"
                mono
                placeholder="留空则按参数表自动生成；群里发「/路径 /help」看它"
                aria-label="单命令帮助"
                @update:model-value="patch({ help: $event })"
              />
            </div>
          </div>
        </section>
      </div>
    </div>


    <!-- 指令列表 -->
    <Panel v-if="!editing" title="已有指令" :count="data?.commands?.length">
      <DataTable
        :rows="data?.commands?.length ?? 0"
        empty="还没有指令"
        empty-hint="点右上角「+ 新建指令」加一条"
      >
        <thead>
          <tr>
            <th>命令</th>
            <th>说明</th>
            <th>偏离默认</th>
            <th>状态</th>
            <th class="ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in data?.commands ?? []" :key="c.id">
            <td class="trig">{{ displayOf(c) }}</td>
            <td class="muted">{{ c.description }}</td>
            <td>
              <template v-if="deviations(c).length">
                <Tag v-for="d in deviations(c)" :key="d" :tone="deviationTone(c)">{{ d }}</Tag>
              </template>
              <span v-else class="faint">默认</span>
            </td>
            <td>
              <Tag :tone="c.enabled ? 'good' : 'neutral'">{{ c.enabled ? "启用" : "停用" }}</Tag>
              <Tag v-if="c.kind === 'agent'" tone="warn">问答·{{ c.mode === 'none' ? '不用知识库' : '知识库' }}</Tag>
              <Tag v-if="c.kind === 'action'" tone="warn">动作·{{ c.action }}</Tag>
              <Tag v-if="c.builtin" tone="flame">内置</Tag>
            </td>
            <td class="ops">
              <div class="ops-btns">
                <Button size="sm" @click="edit(c)">编辑</Button>
                <Button size="sm" @click="toggle(c)">{{ c.enabled ? "停用" : "启用" }}</Button>
                <Button v-if="!c.builtin" size="sm" variant="danger" @click="remove(c)">删除</Button>
              </div>
            </td>
          </tr>
        </tbody>
      </DataTable>
    </Panel>

    <!-- 全局名单：对每条命令都生效，压过单命令的判定 -->
    <Panel v-if="!editing" title="全局名单">
      <div class="acl-grid">
        <div>
          <div class="acl-k"><Tag tone="good">白名单 · QQ 号</Tag></div>
          <Input
            :model-value="globalForm.whiteUsers" multiline :rows="3" placeholder="一行一个"
            aria-label="全局白名单 QQ 号" @update:model-value="globalForm.whiteUsers = $event"
          />
        </div>
        <div>
          <div class="acl-k"><Tag tone="good">白名单 · 群号</Tag></div>
          <Input
            :model-value="globalForm.whiteGroups" multiline :rows="3" placeholder="整个群"
            aria-label="全局白名单群号" @update:model-value="globalForm.whiteGroups = $event"
          />
        </div>
        <div>
          <div class="acl-k"><Tag tone="bad">黑名单 · QQ 号</Tag></div>
          <Input
            :model-value="globalForm.blackUsers" multiline :rows="3" placeholder="一行一个"
            aria-label="全局黑名单 QQ 号" @update:model-value="globalForm.blackUsers = $event"
          />
        </div>
        <div>
          <div class="acl-k"><Tag tone="bad">黑名单 · 群号</Tag></div>
          <Input
            :model-value="globalForm.blackGroups" multiline :rows="3" placeholder="整个群"
            aria-label="全局黑名单群号" @update:model-value="globalForm.blackGroups = $event"
          />
        </div>
      </div>
      <div class="global-actions">
        <span class="faint note">名单里的 QQ 号优先于群号；单命令的名单优先于这一份。</span>
        <span class="faint note">{{ globalSaved }}</span>
        <Button size="sm" variant="primary" :disabled="globalSaving" @click="saveGlobalAcl">保存全局名单</Button>
      </div>
    </Panel>

    <!-- C6：统计 -->
    <Panel v-if="!editing" title="指令使用情况（近 30 天）">
      <div class="two">
        <div class="col">
          <div class="faint sub">最常用</div>
          <DataTable :rows="stats?.topUsed?.length ?? 0" empty="还没有使用记录" empty-hint="群友用过之后这里才有数据">
            <tbody>
              <tr v-for="t in stats?.topUsed ?? []" :key="t.trigger">
                <td class="trig">/{{ t.trigger }}</td>
                <td class="num">{{ t.count }}</td>
              </tr>
            </tbody>
          </DataTable>
        </div>
        <div class="col">
          <div class="faint sub">群友发过但没配的命令（该加什么）</div>
          <DataTable :rows="stats?.unmatched?.length ?? 0" empty="没有未配置的命令" empty-hint="说明群友发的都能对上指令">
            <tbody>
              <tr v-for="u in stats?.unmatched ?? []" :key="u.trigger">
                <td class="trig trig--miss">/{{ u.trigger }}</td>
                <td class="num">{{ u.count }}</td>
              </tr>
            </tbody>
          </DataTable>
        </div>
      </div>
    </Panel>
  </div>
</template>

<style scoped>
/* 宽度交给父页面的 AdminPage —— 面板自己定 max-width 会和页面容器打架 */
.panel-wrap {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

/* 工具栏已上移到页面栏（BotConfigView 的 AdminPage#actions），
   这里只剩内容本身的排布。 */

.trigger-line { display: flex; align-items: center; gap: var(--sp-2); }
.slash { flex: none; color: var(--copper); font-family: var(--font-mono); }

.vars { display: flex; flex-wrap: wrap; gap: var(--sp-1) var(--sp-2); }
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

.preview {
  margin: 0;
  background: var(--surface-inset);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-sm);
  padding: var(--sp-3);
  font-family: var(--font-mono);
  font-size: var(--fs-meta);
  line-height: 1.7;
  color: var(--moss);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

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
/* 类型说明紧跟在下拉后面：它决定"这条指令会不会花钱"，不适合塞进 placeholder */
.kind-note { margin: calc(var(--sp-3) * -1) 0 var(--sp-4); max-width: 620px; line-height: 1.7; }

/* ---------- 编辑态 ----------
   两栏：左边是"这条命令说什么"，右边是"它按什么规则运行"。
   行内标签在左、控件在右，比一列 Field 更省纵向空间，也更容易扫读。 */
.editor { background: var(--stone-300); border-radius: 10px; padding: 20px 22px; }
.editor-head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--sp-4); margin-bottom: var(--sp-5); }
.editor-eyebrow { font-size: 11.5px; letter-spacing: .14em; text-transform: uppercase; color: var(--ink-4); }
.editor-path { font-family: var(--font-mono); font-size: 17px; color: var(--flame); margin-top: 2px; }
.editor-actions { display: flex; gap: var(--sp-2); }
.editor-grid { display: grid; grid-template-columns: 1.15fr 1fr; gap: var(--sp-6); align-items: start; }
.editor-col { display: flex; flex-direction: column; gap: var(--sp-3); }
.col-title { font-size: 11.5px; letter-spacing: .14em; text-transform: uppercase; color: var(--ink-4); padding-bottom: var(--sp-2); border-bottom: 1px solid var(--hairline); }

.row { display: flex; align-items: center; gap: var(--sp-3); }
.row-label { flex: 0 0 76px; font-size: var(--fs-meta); color: var(--ink-3); }
.row > :not(.row-label) { flex: 1; min-width: 0; }
.row--block { flex-direction: column; align-items: stretch; gap: var(--sp-2); }
.row--block .row-label { flex: none; }

.checks { display: flex; flex-wrap: wrap; gap: var(--sp-4); }
.check { display: flex; align-items: center; gap: 6px; font-size: var(--fs-meta); color: var(--ink-2); }
.check input { accent-color: var(--flame); }

.block { border-top: 1px solid var(--hairline); padding-top: var(--sp-2); }
.block-head { display: flex; align-items: center; gap: 8px; width: 100%; background: none; border: 0; padding: 6px 0; color: var(--ink-2); font-size: var(--fs-meta); cursor: pointer; text-align: left; }
.block-head:hover { color: var(--ink); }
.caret { width: 12px; color: var(--flame); font-family: var(--font-mono); }
.block-body { padding: var(--sp-2) 0 var(--sp-1); display: flex; flex-direction: column; gap: var(--sp-2); }
.block-actions { display: flex; gap: var(--sp-2); }
.preview-line { margin: var(--sp-2) 0 0; font-size: var(--fs-meta); color: var(--moss); font-family: var(--font-mono); white-space: pre-wrap; }

@media (max-width: 1000px) {
  .editor-grid { grid-template-columns: 1fr; }
  .row { flex-direction: column; align-items: stretch; gap: 4px; }
  .row-label { flex: none; }
}

/* 私聊与来源显示用勾选：它们是"开/关"而不是"选一个" */
.switch { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; color: var(--ink-2); }
.switch input[type='checkbox'] { accent-color: var(--flame); }
.field-extra { margin: calc(var(--sp-3) * -1) 0 var(--sp-4); max-width: 520px; }

.acl-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); }
.acl-k { margin-bottom: var(--sp-1); }
.global-actions { display: flex; align-items: center; justify-content: flex-end; gap: var(--sp-3); margin-top: var(--sp-3); }
@media (max-width: 900px) { .acl-grid { grid-template-columns: 1fr; } }

/* 参数表：一行一个参数。列宽固定，枚举的候选值另起一行 ——
   它只在 type=enum 时出现，混在行里会把其余控件挤变形。 */
.params { display: flex; flex-direction: column; gap: var(--sp-2); }
.param-row {
  display: grid;
  grid-template-columns: 130px 108px 62px 118px 118px minmax(120px, 1fr) auto;
  gap: var(--sp-2);
  align-items: center;
}
.param-row .enum-opts { grid-column: 1 / -1; }
.req {
  display: flex; align-items: center; gap: 6px;
  font-size: var(--fs-meta); color: var(--mist); white-space: nowrap;
}
.req input[type='checkbox'] { accent-color: var(--flame); }
.param-actions { display: flex; gap: var(--sp-2); }
@media (max-width: 1100px) {
  .param-row { grid-template-columns: 1fr 1fr; }
  .param-row .enum-opts { grid-column: 1 / -1; }
}

/* 触发词列：等宽 + 灵火色，一眼能和说明文字分开 */
.trig { font-family: var(--font-mono); color: var(--flame); }
.trig--miss { color: var(--rust); }
.reply { font-size: var(--fs-meta); }

/* 操作列右对齐。DataTable 的表头样式带组件自己的 scope，特异性比裸 .ops 高，
   所以这里用面板祖先 + :deep 提升特异性，而不是加 !important。 */
.panel-wrap :deep(th.ops),
.panel-wrap :deep(td.ops) { text-align: right; white-space: nowrap; }
.ops-btns { display: flex; gap: var(--sp-1); justify-content: flex-end; }

.two { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-5); }
.sub { font-size: var(--fs-meta); margin-bottom: var(--sp-2); }
/* 这两张表是「排行清单」不是数据表：行距收紧一点，
   否则两列排行会把面板撑得很长（默认单元格内边距是给多列宽表用的）。 */
.col :deep(td) { padding: var(--sp-2) var(--sp-3); }

@media (max-width: 900px) {
  .two { grid-template-columns: 1fr; }
}
</style>
