<script setup lang="ts">
/**
 * 「知识库 → 提案」Tab 面板。
 *
 * 为什么是面板而不是页面：管理端把「关键词」「分类」「术语核对」「提案」「资料」
 * 并进同一个「知识库」页，页内用二级目录切换。页面级外壳（AdminPage / Tabs /
 * 页面标题 / 页面宽度）全部交给父页面 —— 所以这里不接收 props、不 emit 事件、
 * 不渲染 AdminPage，也不写 max-width：面板自己定宽会和父页面容器打架。
 *
 * 这个 tab 管的是「提案」这条闭环的**人工处理**环节：
 *   问答记录里打「有帮助」 → 攒够阈值 → 手动点分析（调大模型） → 产出提案 → 采纳或拒绝。
 * 写入知识库只发生在「采纳」之后，所以列表按状态分档、逐条处理。
 *
 * <p><b>建议文本可先改再采纳</b>。落地方式（import { adminApi, describeError } from '@shared/api/client'）：
 * 前端不另造写入路径 —— 采纳仍走 {@code /kb/proposals/review}，但采纳前先按 kind 把
 * 用户改过的文本落到它真正会去的地方：
 * <ul>
 *   <li>{@code overwrite} → 先 {@code /kb/terms/chunk} 覆盖该词条的第一块
 *       （后端 approve 时也是覆盖同一块，所以两次写同一处，顺序上先手改后采纳）。</li>
 *   <li>{@code new} → 后端 approve 时会把词条名 + 文本投递进「资料」队列；
 *       先改没有对应的单条接口，所以这两种情况**明说不能改**，而不是假装能改。</li>
 * </ul>
 * 不这么分开的话，编辑框在一个"改了也没用"的场景里照样可输入 —— 那才是骗人。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import { num, shortTime } from '@shared/utils/format'
import Panel from '@shared/ui/Panel.vue'
import Tabs from '@shared/ui/Tabs.vue'
import Notice from '@shared/ui/Notice.vue'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'
import Stat from '@shared/ui/Stat.vue'
import Empty from '@shared/ui/Empty.vue'

/** 进度与计数 */
interface ProposalStats {
  threshold: number
  pendingGood: number
  canAnalyze: boolean
  pending: number
  approved: number
  rejected: number
  available: boolean
}

/** 一条提案 */
interface ProposalRow {
  id: number
  createdAt: string
  status: string
  kind: string
  title: string
  question: string
  currentText: string
  proposedText: string
  reason: string
  sourceStatId: number
  reviewedAt: string | null
  reviewedBy: string | null
}

interface ProposalList {
  rows: ProposalRow[]
  status: string
}

interface ActionResult {
  ok: boolean
  message: string
  stats?: ProposalStats
}

interface AnalyzeResult extends ActionResult {
  goodTotal?: number
  analyzed?: number
  proposals?: number
}

type TabKey = 'pending' | 'approved' | 'rejected'

const loading = ref(false)
const error = ref('')
const ok = ref('')

const stats = ref<ProposalStats | null>(null)
const rows = ref<ProposalRow[]>([])

const tab = ref<TabKey>('pending')

/** 分析会真的调用大模型，用独立的忙碌标记，避免和列表刷新互相禁用 */
const analyzing = ref(false)
/** 正在提交审阅的提案 id（只禁用这一条的两个按钮） */
const busyId = ref<number | null>(null)

/**
 * 建议文本的就地编辑：提案 id → 草稿。只有被改过的提案才有条目。
 * 只有 kind='overwrite' 能改 —— 见文件头对落地方式的说明。
 */
const drafts = ref<Record<number, string>>({})
const editingId = ref<number | null>(null)

/** 这条提案的建议文本能不能在采纳前改 */
const canEdit = (p: ProposalRow) => p.kind === 'overwrite'
const textOf = (p: ProposalRow) => drafts.value[p.id] ?? p.proposedText
const isDirty = (p: ProposalRow) => p.id in drafts.value && drafts.value[p.id] !== p.proposedText

/** 还差几条「有帮助」标记才能到阈值 */
const remaining = computed(() => {
  const s = stats.value
  if (!s) return 0
  return Math.max(0, s.threshold - s.pendingGood)
})

const available = computed(() => stats.value?.available === true)

const TABS = computed(() => [
  { key: 'pending' as TabKey, label: '待确认', count: stats.value?.pending },
  { key: 'approved' as TabKey, label: '已通过', count: stats.value?.approved },
  { key: 'rejected' as TabKey, label: '已拒绝', count: stats.value?.rejected },
])

const tabLabel = computed(() => TABS.value.find(t => t.key === tab.value)?.label ?? '')

const emptyText = computed(() => {
  if (tab.value === 'approved') return '还没有通过的提案'
  if (tab.value === 'rejected') return '还没有拒绝的提案'
  return '还没有待确认的提案'
})

const emptyHint = computed(() => {
  if (tab.value !== 'pending') return ''
  if (!stats.value || !available.value) return ''
  return stats.value.canAnalyze
    ? '点右上角「让 agent 分析」产出提案'
    : '再有 ' + remaining.value + ' 条「有帮助」标记就能分析'
})

/** 禁用状态下按钮仍要能说清「为什么点不了」 */
const analyzeTitle = computed(() => {
  const s = stats.value
  if (!s) return ''
  if (!s.available) return '提案库不可用'
  if (s.canAnalyze) return '分析被标记「有帮助」的回答，产出待确认的提案'
  return '还差 ' + remaining.value + ' 条「有帮助」标记（' + s.pendingGood + ' / ' + s.threshold + '）'
})

async function fetchList() {
  if (!available.value) { rows.value = []; return }
  const r = await adminApi.get<ProposalList>('/kb/proposals?status=' + tab.value + '&limit=100')
  rows.value = r.rows ?? []
  // 换了列表就把没提交的草稿丢掉：留着会让人以为改动还在
  drafts.value = {}
  editingId.value = null
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    stats.value = await adminApi.get<ProposalStats>('/kb/proposals/stats')
    await fetchList()
  } catch (e) { error.value = describeError(e) } finally {
    loading.value = false
  }
}

/** Tabs 的模型是 string，这里收窄回本面板的三个取值 */
async function setTab(k: string) {
  if (k !== 'pending' && k !== 'approved' && k !== 'rejected') return
  if (k === tab.value) return
  tab.value = k
  loading.value = true
  error.value = ''
  try { await fetchList() } catch (e) { error.value = describeError(e) } finally {
    loading.value = false
  }
}

/** ★ 手动触发 agent 分析。会真的调用大模型（消耗额度），所以必须先确认 */
async function analyze() {
  const s = stats.value
  if (!s || !s.available || !s.canAnalyze || analyzing.value) return
  if (!window.confirm(
    '让 agent 分析被标记「有帮助」的回答，产出待确认的提案？\n\n' +
    '这会真的调用大模型、消耗额度，可能要等一会儿。'
  )) return
  analyzing.value = true
  error.value = ''
  ok.value = ''
  try {
    const r = await adminApi.post<AnalyzeResult>('/kb/proposals/analyze', { max: 5 })
    ok.value = r.message ?? '完成'
    if (r.stats) stats.value = r.stats
    await fetchList()
  } catch (e) { error.value = describeError(e) } finally {
    analyzing.value = false
  }
}

// ==================== 建议文本：就地编辑 ====================

function startEdit(p: ProposalRow) {
  if (!canEdit(p)) return
  editingId.value = p.id
  if (!(p.id in drafts.value)) drafts.value = { ...drafts.value, [p.id]: p.proposedText }
}
function cancelEdit(p: ProposalRow) {
  const d = { ...drafts.value }
  delete d[p.id]
  drafts.value = d
  editingId.value = null
}
function revertEdit(p: ProposalRow) {
  drafts.value = { ...drafts.value, [p.id]: p.proposedText }
}
function onDraft(p: ProposalRow, v: string) {
  drafts.value = { ...drafts.value, [p.id]: v }
}

/**
 * 把改过的文本落到「采纳后它会去的地方」。
 *
 * overwrite 的提案，采纳时后端覆盖的是该词条的**第一块** —— 所以这里也写那一块：
 * 先取块列表找到块号，再调 /kb/terms/chunk。这样"改完再采纳"的结果
 * 和"没改直接采纳"落在同一处，不会出现两套语义。
 */
async function applyDraft(p: ProposalRow): Promise<string> {
  if (!isDirty(p)) return ''
  const text = drafts.value[p.id]
  if (!text.trim()) throw new Error('建议文本不能为空')
  const r = await adminApi.get<{ chunks: Array<{ i: number }> }>(
    '/kb/terms/chunks?title=' + encodeURIComponent(p.title))
  const list = r.chunks ?? []
  if (!list.length) throw new Error('知识库里没有词条「' + p.title + '」，改不了它的文本')
  await adminApi.post('/kb/terms/chunk', { i: list[0].i, text })
  return '已按改后的文本更新块 ' + list[0].i + '；'
}

// ==================== 采纳 / 拒绝 ====================

/** 采纳（先落改动）/ 拒绝。说清会发生什么：覆盖立刻生效，新增进投递队列 */
async function review(p: ProposalRow, approve: boolean) {
  const edited = isDirty(p)
  const consequence = approve
    ? (p.kind === 'overwrite'
        ? (edited
            ? '会先用你改后的文本覆盖知识库词条「' + p.title + '」，再标记这张提案。'
            : '采纳后会立刻覆盖知识库词条「' + p.title + '」的文本。')
        : '采纳后「' + p.title + '」作为新资料进入投递队列，需要去「资料」tab 审核并建索引。'
          + (edited ? '\n（新增提案的文本要改，请改提案本身；这里改不了它的投递内容。）' : ''))
    : '拒绝只标记这条提案，知识库不会改动。'
  if (!window.confirm(
    (approve ? '采纳提案' : '拒绝提案') + ' #' + p.id + '？\n\n' + consequence
  )) return

  busyId.value = p.id
  error.value = ''
  ok.value = ''
  try {
    const pre = approve ? await applyDraft(p) : ''
    const r = await adminApi.post<ActionResult>('/kb/proposals/review', { id: p.id, approve })
    ok.value = pre + (r.message ?? (approve ? '已采纳' : '已拒绝'))
    if (r.stats) stats.value = r.stats
    await fetchList()
  } catch (e) { error.value = describeError(e) } finally {
    busyId.value = null
  }
}

/**
 * 「删除」提案。
 *
 * 后端没有删除提案的接口（kb_proposal 表只有 status），所以这里用**拒绝**表达：
 * 卡片会从待确认移到已拒绝，等于"从待办里拿掉"，但记录仍可追溯。
 * 按钮上直接写清楚这一点，不让它看起来像真删。
 */
async function removeProposal(p: ProposalRow) {
  if (!window.confirm(
    '删除提案 #' + p.id + '？\n\n' +
    '后端没有删除接口，会把它标成「已拒绝」从待确认里拿掉；记录保留在「已拒绝」里，知识库不动。')) return
  busyId.value = p.id
  error.value = ''
  ok.value = ''
  try {
    const r = await adminApi.post<ActionResult>('/kb/proposals/review', { id: p.id, approve: false })
    ok.value = '提案 #' + p.id + ' 已移入「已拒绝」（' + (r.message ?? '已拒绝') + '）'
    if (r.stats) stats.value = r.stats
    await fetchList()
  } catch (e) { error.value = describeError(e) } finally {
    busyId.value = null
  }
}

onMounted(load)

/** 编辑态只允许一条：点开另一条时把上一条收起来（草稿留着，不丢） */
watch(editingId, (v) => { if (v !== null) ok.value = '' })

/**
 * 页面栏上的进度读数、「刷新」「让 agent 分析」由父页面 KbView 渲染。
 * 用函数暴露：视图渲染时调用，读到 stats / analyzing 的变化会计入视图的
 * 渲染副作用，进度数字与按钮禁用态才跟得上。
 */
defineExpose({
  reload: load,
  analyze,
  analyzeTitle: () => analyzeTitle.value,
  progress: () => {
    const s = stats.value
    if (!s) return null
    return {
      available: s.available,
      pendingGood: s.pendingGood,
      threshold: s.threshold,
      canAnalyze: s.canAnalyze,
      remaining: remaining.value,
    }
  },
  canAnalyze: () => stats.value?.canAnalyze === true,
  analyzing: () => analyzing.value,
  isLoading: () => loading.value,
})
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-if="ok" tone="ok">{{ ok }}</Notice>

    <Notice v-if="stats && !available" tone="warn">
      提案库不可用，暂时不能分析或处理提案。
    </Notice>

    <template v-if="stats && available">
      <!-- 进度：四个计数，需要人工处理的档位转 rust，其余压暗 -->
      <div class="kpis">
        <Stat
          label="「有帮助」待分析"
          :value="num(stats.pendingGood)"
          :hint="'阈值 ' + stats.threshold + ' 条'"
          :tone="stats.canAnalyze ? 'flame' : 'mist'"
        />
        <Stat
          label="待确认"
          :value="num(stats.pending)"
          :tone="stats.pending > 0 ? 'rust' : 'mist'"
        />
        <Stat label="已通过" :value="num(stats.approved)" tone="mist" />
        <Stat label="已拒绝" :value="num(stats.rejected)" tone="mist" />
      </div>

      <Tabs :tabs="TABS" :model-value="tab" @update:model-value="setTab" />

      <Panel :title="'提案 · ' + tabLabel" :count="rows.length">
        <Empty v-if="!rows.length" :text="emptyText" :hint="emptyHint" />

        <div v-else class="list">
          <article v-for="p in rows" :key="p.id" class="item">
            <div class="item-head">
              <span class="item-title">{{ p.title || "（没有词条名）" }}</span>
              <Tag :tone="p.kind === 'new' ? 'good' : 'warn'">
                {{ p.kind === "new" ? "新增" : "覆盖" }}
              </Tag>
              <span class="faint when">
                <span class="num">#{{ p.id }}</span>
                <template v-if="p.sourceStatId"> · 问答 <span class="num">#{{ p.sourceStatId }}</span></template>
                · {{ shortTime(p.createdAt) }}
              </span>
            </div>

            <div class="line">
              <span class="field-label">触发问题</span>
              <span class="line-text">{{ p.question || "—" }}</span>
            </div>

            <div class="diff">
              <div class="col col--old">
                <span class="field-label">现有文本</span>
                <pre class="text">{{ p.currentText || "（知识库里没有这个词条）" }}</pre>
              </div>
              <div class="col col--new">
                <span class="field-label">
                  建议文本
                  <span v-if="isDirty(p)" class="dirty">已改</span>
                </span>
                <!-- 点文本就地改：overwrite 的提案采纳时写的就是这一块 -->
                <pre
                  v-if="p.status !== 'pending' || !canEdit(p) || editingId !== p.id"
                  class="text text--new" :class="{ editable: p.status === 'pending' && canEdit(p) }"
                  @click="startEdit(p)"
                >{{ textOf(p) }}</pre>
                <textarea
                  v-else
                  class="text text--new edit"
                  rows="10"
                  :value="textOf(p)"
                  aria-label="建议文本"
                  @input="onDraft(p, ($event.target as HTMLTextAreaElement).value)"
                />
                <div v-if="p.status === 'pending' && editingId === p.id" class="edit-ops">
                  <Button size="sm" :disabled="busyId === p.id" @click="revertEdit(p)">撤销改动</Button>
                  <Button size="sm" :disabled="busyId === p.id" @click="cancelEdit(p)">收起</Button>
                </div>
              </div>
            </div>

            <div class="line line--reason">
              <span class="field-label">理由</span>
              <span class="line-text faint">{{ p.reason || "—" }}</span>
            </div>

            <div v-if="p.status === 'pending'" class="item-actions">
              <Button
                size="sm"
                variant="primary"
                :disabled="busyId === p.id"
                @click="review(p, true)"
              >{{ isDirty(p) ? '按改后的文本采纳' : '采纳' }}</Button>
              <Button
                size="sm"
                :disabled="busyId === p.id"
                @click="review(p, false)"
              >拒绝</Button>
              <Button
                size="sm"
                variant="danger"
                :disabled="busyId === p.id"
                title="后端没有删除接口，会把它标成「已拒绝」从待确认拿掉"
                @click="removeProposal(p)"
              >删除</Button>
            </div>
            <div v-else class="line line--done faint">
              <span class="field-label">处理</span>
              <span class="line-text">
                {{ p.status === "approved" ? "已通过" : "已拒绝" }}
                · {{ shortTime(p.reviewedAt) }}
                <template v-if="p.reviewedBy"> · {{ p.reviewedBy }}</template>
              </span>
            </div>
          </article>
        </div>
      </Panel>
    </template>
  </div>
</template>

<style scoped>
/* 宽度交给父页面的 AdminPage —— 面板自己定 max-width 会和页面容器打架 */
.panel-wrap {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

/* 工具栏（进度 + 刷新 + 让 agent 分析）已上移到页面栏 —— KbView 的 AdminPage#actions。 */

/* 进度卡排布。层级靠背景深浅，卡片之间只留间距 */
.kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: var(--sp-3); }

/* ==================== 提案列表 ====================
   同级条目之间：一道发丝线 + 上下留白（留白比线本身更重要）。
   条目本身不再各套一个框，避免满屏线条。 */
.list { display: flex; flex-direction: column; }
.item { padding: var(--sp-4) 0; }
.item + .item { border-top: 1px solid var(--hairline); }
.item:first-child { padding-top: 0; }
.item:last-child { padding-bottom: 0; }

.item-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-bottom: var(--sp-3);
}
/* 目标词条是这一条的入口：正文字号 + 更亮的字色，层级不靠框线 */
.item-title {
  font-size: var(--fs-body);
  font-weight: var(--fw-medium);
  color: var(--ink);
  overflow-wrap: anywhere;
}
/* 时间是数字，等宽后多行上下对得齐（同时替掉 spacer） */
.when {
  margin-left: auto;
  font-size: var(--fs-meta);
  font-variant-numeric: tabular-nums;
}

/* 字段名统一降一级：小号 + 字距，让内容自己站住 */
.field-label {
  flex: none;
  font-size: var(--fs-meta);
  color: var(--ink-faint);
  letter-spacing: var(--tracking-label);
}
.line {
  display: flex;
  gap: var(--sp-2);
  align-items: baseline;
  font-size: var(--fs-body);
  color: var(--ink-dim);
}
.line-text { overflow-wrap: anywhere; }
.line--reason { margin-top: var(--sp-3); font-size: var(--fs-meta); }
.line--done { margin-top: var(--sp-3); font-size: var(--fs-meta); }

/* 现有文本与建议文本并列对照。窄屏叠成一栏（见文件末尾） */
.diff {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-4);
  margin-top: var(--sp-3);
}
.col { display: flex; flex-direction: column; gap: var(--sp-2); min-width: 0; }
/* 文本块：凹槽底色 + 柔边，左侧一道刻痕区分「旧的 / 新的」。
   建议文本完整展示，不截断、不加滚动条。 */
.text {
  margin: 0;
  padding: var(--sp-3);
  background: var(--surface-inset);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-sm);
  font-family: var(--font-body);
  font-size: var(--fs-sm);
  line-height: 1.7;
  color: var(--ink-dim);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.text--new { color: var(--ink); box-shadow: inset 2px 0 0 var(--moss); }
.col--old .text { box-shadow: inset 2px 0 0 var(--line-strong); }

/* 可改的建议文本：看着像文本块，鼠标上是个可点的编辑面（背景 + 边缘同时变） */
.text.editable { cursor: text; transition: background var(--dur-fast) var(--ease),
                                            border-color var(--dur-fast) var(--ease); }
.text.editable:hover { background: var(--surface-raised); border-color: var(--edge-hover); }
.text.editable:active { background: var(--surface-active); }

/* 编辑态：与只读的同款外观，直接落成 textarea，不跳出一个框 */
.text.edit {
  display: block;
  width: 100%;
  font-family: var(--font-body);
  resize: vertical;
  outline: none;
}
/* ⚠️ 用 :focus-visible，并且把焦点环换成**实色双环**。
   原来是 :focus + 3px 的半透明橙晕（--flame-glow 的 alpha 只有 .28）——
   在深色底上对比度约 1.2:1，等于没有焦点指示（WCAG 2.4.11 要求 ≥3:1）。 */
.text.edit:focus-visible {
  border-color: var(--ember);
  box-shadow: inset 2px 0 0 var(--vital), 0 0 0 2px var(--stone-200), 0 0 0 4px var(--ember);
}

/* 「已改」是状态标记，不是装饰 */
.dirty {
  margin-left: var(--sp-2);
  padding: 0 6px;
  border-radius: var(--r-xs);
  font-size: var(--fs-xs);
  color: var(--flame-bright);
  background: var(--flame-veil);
}
.edit-ops { display: flex; gap: var(--sp-2); margin-top: var(--sp-2); }

.item-actions { margin-top: var(--sp-3); display: flex; gap: var(--sp-2); }

@media (max-width: 900px) {
  /* 窄屏：两栏对照叠成上下，长文本才读得下去 */
  .diff { grid-template-columns: 1fr; }
}
</style>
