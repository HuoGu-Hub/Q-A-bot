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
import type { CommandList, BotCommand, CommandVariables, CommandStats } from '@shared/api/types'
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

/** 编辑中的指令（null = 没在编辑） */
const editing = ref<Partial<BotCommand> | null>(null)
/** 实时预览结果 */
const preview = ref('')
/** 预览 {args} 用的示例参数 */
const sampleArgs = ref('')

/** 范围候选。配合 Select 的 options 接口，值与原 <option> 完全一致 */
const SCOPE_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'all', label: '全部（群 + 私聊）' },
  { value: 'group', label: '仅群聊' },
  { value: 'private', label: '仅私聊' },
]

/**
 * 指令类型 —— 这个选择决定了"会不会花钱"：
 *   template 即时回话术，不调模型、不计费，所以跑在 Guard 之前秒回；
 *   agent    交给大模型，会走 Guard 与成本预算。
 */
const KIND_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'template', label: '话术（直接回复，不调模型）' },
  { value: 'agent', label: '智能问答（调模型，计入预算）' },
]

/** agent 类的知识来源。命名上刻意说"走/不走知识库"，而不是"温度""权重"这种看不出效果的词 */
const MODE_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'kb', label: '走知识库（默认）' },
  { value: 'none', label: '不用知识库（纯模型回答）' },
]

const isAgent = computed(() => editing.value?.kind === 'agent')

const kindHint = computed(() => isAgent.value
  ? '智能问答：群友发「/触发词 问题文本」→ 交给大模型回答。会走 Guard 与成本预算（会花钱），参数就是问题。'
  : '话术：群友发「/触发词 参数…」→ 直接回下面的文本。不调模型、不计费，所以是秒回。')

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await adminApi.get<CommandList>('/commands')
    vars.value = await adminApi.get<CommandVariables>('/commands/variables')
    stats.value = await adminApi.get<CommandStats>('/commands/stats?days=30')
  } catch (e) { error.value = describeError(e) } finally {
    loading.value = false
  }
}

function newCommand() {
  editing.value = {
    trigger: '', reply: '', description: '', scope: 'all', enabled: true, sortOrder: 0,
    kind: 'template', mode: 'kb',
  }
  preview.value = ''
  sampleArgs.value = ''
}

function edit(c: BotCommand) {
  editing.value = { ...c }
  sampleArgs.value = ''
  updatePreview()
}

/** 列表里怎么显示这条指令：agent 类要带 <问题>，否则群友不知道要传参数 */
function displayOf(c: BotCommand) {
  return '/' + c.trigger + (c.kind === 'agent' ? ' <问题>' : '')
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
    await adminApi.post('/commands', editing.value)
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
    <Panel v-if="editing" title="编辑指令">
      <Field label="触发词" control-width="360px">
        <div class="trigger-line">
          <span class="slash">/</span>
          <Input
            :model-value="editing.trigger ?? ''"
            placeholder="help（不要写斜杠）"
            aria-label="触发词"
            @update:model-value="patch({ trigger: $event })"
          />
        </div>
      </Field>

      <Field label="说明" control-width="520px">
        <Input
          :model-value="editing.description ?? ''"
          placeholder="用途说明（{cmd.list} 会显示它）"
          aria-label="说明"
          @update:model-value="patch({ description: $event })"
        />
      </Field>

      <Field label="类型" control-width="360px">
        <Select
          :model-value="editing.kind ?? 'template'"
          :options="KIND_OPTIONS"
          aria-label="类型"
          @update:model-value="patch({ kind: $event })"
        />
      </Field>
      <p class="kind-note faint">{{ kindHint }}</p>

      <Field v-if="isAgent" label="回答模式" control-width="360px">
        <Select
          :model-value="editing.mode ?? 'kb'"
          :options="MODE_OPTIONS"
          aria-label="回答模式"
          @update:model-value="patch({ mode: $event })"
        />
      </Field>

      <Field :label="isAgent ? '用法提示（没带参数时回这句）' : '回复'" control-width="520px">
        <Input
          :model-value="editing.reply ?? ''"
          multiline
          :rows="6"
          mono
          :placeholder="isAgent
            ? '用法：/联网 问题内容（群友只发 /联网 不带参数时回这句）'
            : '回复内容，支持 {变量} 与 {args}'"
          :aria-label="isAgent ? '用法提示' : '回复'"
          @update:model-value="onReplyInput"
        />
      </Field>

      <Field label="示例参数（预览用）" control-width="360px">
        <Input
          :model-value="sampleArgs"
          placeholder="张三 18（预览 {args} / {args.1} 用）"
          aria-label="示例参数"
          @update:model-value="onArgsInput"
        />
      </Field>

      <Field label="可用变量" control-width="520px">
        <div class="vars">
          <button
            v-for="v in basicVars"
            :key="v.name"
            class="vchip"
            type="button"
            :title="v.label + '（例：' + v.example + '）'"
            @click="insertVar(v.name)"
          >{ {{ v.name }} }</button>
        </div>
      </Field>

      <Field label="高级变量" control-width="520px">
        <div v-if="advVars.length" class="vars">
          <button
            v-for="v in advVars"
            :key="v.name"
            class="vchip vchip--adv"
            type="button"
            @click="insertVar(v.name)"
          >{ {{ v.name }} }</button>
        </div>
        <p v-else class="faint note">
          高级变量（如 {user.id}）未启用 —— 回复会发到群里，默认不打 QQ 号。
          可在「Bot 设置」里开启。
        </p>
      </Field>

      <Field label="范围" control-width="360px">
        <Select
          :model-value="editing.scope ?? 'all'"
          :options="SCOPE_OPTIONS"
          aria-label="范围"
          @update:model-value="patch({ scope: $event })"
        />
      </Field>

      <Field v-if="preview" label="预览" control-width="520px">
        <pre class="preview">{{ preview }}</pre>
      </Field>

      <div class="form-actions">
        <span class="faint note">预览用示例数据渲染，实际发送时会替换为真实值</span>
        <div class="form-buttons">
          <Button size="sm" @click="editing = null">取消</Button>
          <Button size="sm" variant="primary" @click="save">保存</Button>
        </div>
      </div>
    </Panel>

    <!-- 指令列表 -->
    <Panel title="已有指令" :count="data?.commands?.length">
      <DataTable
        :rows="data?.commands?.length ?? 0"
        empty="还没有指令"
        empty-hint="点右上角「+ 新建指令」加一条"
      >
        <thead>
          <tr>
            <th>触发词</th>
            <th>说明</th>
            <th>回复预览</th>
            <th>范围</th>
            <th>状态</th>
            <th class="ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in data?.commands ?? []" :key="c.id">
            <td class="trig">{{ displayOf(c) }}</td>
            <td class="muted">{{ c.description }}</td>
            <td class="reply">{{ c.reply.slice(0, 60) }}{{ c.reply.length > 60 ? "…" : "" }}</td>
            <td class="muted">{{ c.scope }}</td>
            <td>
              <Tag :tone="c.enabled ? 'good' : 'neutral'">{{ c.enabled ? "启用" : "停用" }}</Tag>
              <Tag v-if="c.kind === 'agent'" tone="warn">问答·{{ c.mode === 'none' ? '不用知识库' : '知识库' }}</Tag>
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

    <!-- C6：统计 -->
    <Panel title="指令使用情况（近 30 天）">
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
