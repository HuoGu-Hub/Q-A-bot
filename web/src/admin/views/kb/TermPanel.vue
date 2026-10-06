<script setup lang="ts">
/**
 * 「知识库 → 词条」面板 —— 「关键词」与「术语核对」合并后的管理台。
 *
 * 为什么合并：这两块本来就是同一批对象（Wiki 页面）的两个侧面 ——
 * 一个管「里面有哪些正文」，一个管「中英文名字对不对」。后端把术语表从
 * TSV 文件收成 kb_term 单表、两边接口并成 /kb/terms* 之后，前端再拆两个面板
 * 就只剩下重复：同一份数据两套状态机、两条写名字的代码路径。
 *
 * 布局是「一个列表 + 一个抽屉」：
 *   列表      全部词条，靠 view 预设切换口径（默认 / 待核对 / 无中文名 / 无正文 …）
 *   抽屉      选中那一条的「名字 + 正文」；名字区来自旧术语核对，正文区来自旧关键词
 *   核对模式  旧术语核对的逐条流水线（快捷键 / 批量勾选）原样保留，只是换了入口
 *
 * 视图预设是**唯一的筛选维度**（外加板块与搜索词）：原来「术语状态」与
 * 「是否下架」是两个各自独立的 Select，组合起来几十种状态，而用户真正要的
 * 只是「还没核的那些」这类预设。收成一个 view 之后，页面栏少一个控件，
 * 下架状态也不再需要逐行探测（新接口的 retired 字段总是返回）。
 *
 * 页面栏（视图预设 / 搜索 / 模式切换 / 新建 / 导入导出 / 刷新）由父页面 KbView
 * 渲染，读数与动作在这里 defineExpose 抛出去 —— 与合并前同一套约定。
 */
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type {
  CategoryItem,
  KbTerm,
  KbTermBatchResponse,
  KbTermBoard,
  KbTermChunk,
  KbTermChunksResponse,
  KbTermCounts,
  KbTermExportResponse,
  KbTermImportResponse,
  KbTermListResponse,
} from '@shared/api/types'
import { num } from '@shared/utils/format'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Notice from '@shared/ui/Notice.vue'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'
import Empty from '@shared/ui/Empty.vue'

type Mode = 'list' | 'review'
/** 后端约定的唯一预设参数；其它筛选都靠它 */
type ViewKey = 'main' | 'all' | 'draft' | 'verified' | 'rejected' | 'unnamed' | 'nochunk'
type TermStatus = 'draft' | 'verified' | 'rejected'
/** 核对队列里的一条：多留一份原始中文名，用来判断用户到底改没改过 */
type ReviewItem = KbTerm & { originalZh: string }

const LIST_LIMIT = 300
/** 一次拉多少条待核对。后端 limit 上限 500，取小一点让首屏快。 */
const REVIEW_PAGE = 100

const EMPTY_COUNTS: KbTermCounts = {
  all: 0, main: 0, withChunks: 0, noChunk: 0,
  draft: 0, verified: 0, rejected: 0, unnamed: 0,
}

const STATUS_LABEL: Record<string, string> = {
  verified: '已核对', rejected: '弃用', draft: '待核对', '': '未收录',
}

const DRAWER_STATUS: { value: TermStatus; label: string }[] = [
  { value: 'draft', label: '待核对' },
  { value: 'verified', label: '已核对' },
  { value: 'rejected', label: '弃用' },
]

// ==================== 状态 ====================

const mode = ref<Mode>('list')
const view = ref<ViewKey>('main')
const board = ref('')
const q = ref('')

const loading = ref(false)
const busy = ref(false)
const error = ref('')
const ok = ref('')

const items = ref<KbTerm[]>([])
const total = ref(0)
const counts = ref<KbTermCounts>({ ...EMPTY_COUNTS })
const boards = ref<KbTermBoard[]>([])
const available = ref(true)

/** 列表内联编辑：同一时刻只编辑一行，草稿跟着它走 */
const editing = ref<string | null>(null)
const zhDraft = ref('')
/** 正在弹状态小选择器的那一行 */
const statusOpen = ref<string | null>(null)

/**
 * 抽屉。
 *
 * dZh / dStatus 是抽屉自己的草稿，**不复用**列表内联编辑的 zhDraft —
 * 两处同时打开时共用一份草稿，改一边会把另一边也带着变。
 */
const selected = ref<KbTerm | null>(null)
const dZh = ref('')
const dStatus = ref<TermStatus>('draft')

/** 抽屉的正文区：块列表 + 「块 id → 文本」的草稿（只有正在改的块才有条目） */
const chunks = ref<KbTermChunk[]>([])
const chunksLoading = ref(false)
const chunkDrafts = ref<Record<string, string>>({})
const savingChunk = ref<string | null>(null)
const retireBusy = ref<string | null>(null)

/** 抽屉的板块编辑：改的是那个**原始分类**的归属，同分类的词条会一起动 */
const boardEditing = ref(false)
const categoryItems = ref<CategoryItem[]>([])
const groupOptions = ref<{ value: string; label: string }[]>([])
const groupDraft = ref('')

/** 新建词条 */
const creating = ref(false)
const savingNew = ref(false)
const nc = ref({ en: '', zh: '' })

/** 导入用的隐藏 <input type="file"> */
const fileInput = ref<HTMLInputElement | null>(null)

// ==================== 核对模式的队列 ====================

/**
 * 待核对队列。
 *
 * ⚠️ 每次都按 offset=0 拉，再按 en 去重后追加 —— 不要改成 offset 累加。
 * 核对会把条目从 draft 集合里移走：删掉第 1 条后，原来的第 101 条会顶到第 100 位，
 * 于是 offset=100 那一页正好跳过它。静默漏条目是最难被发现的一类 bug。
 */
const queue = ref<ReviewItem[]>([])
const idx = ref(0)
const picked = ref<string[]>([])

const current = computed<ReviewItem | undefined>(() => queue.value[idx.value])
const rows = computed<KbTerm[]>(() => (mode.value === 'review' ? queue.value : items.value))

const allPicked = computed(() =>
  queue.value.length > 0 && queue.value.every((r) => picked.value.includes(r.en)))

/** 进度 = 已处理 / 总数。已排除也算处理过 —— 它同样需要人看过一遍。 */
const processed = computed(() => counts.value.verified + counts.value.rejected)
const progressPct = computed(() =>
  counts.value.all ? Math.round((processed.value / counts.value.all) * 100) : 0)

const emptyText = computed(() =>
  mode.value === 'review' ? '队列里没有待核对的条目' : '这个筛选下没有词条')
const emptyHint = computed(() =>
  mode.value === 'review'
    ? '换个搜索词，或切回列表模式'
    : '换个视图预设或板块，或清掉搜索词')

/**
 * 别名提示（核对模式的卡片上）。
 *
 * 「单字别名」这条不是吹毛求疵：B 路加载术语时只收 >= 2 个字的别名，
 * 写一个单字进去会"看着保存成功、实际永远不生效"。
 */
const aliasHint = computed(() => {
  const it = current.value
  if (!it) return ''
  const parts = splitAliases(it.zh)
  if (!parts.length) return '中文名不能为空'
  if (parts.some((p) => p.length < 2)) return '有单字别名：检索时会忽略它（中文名至少 2 个字才生效）'
  if (parts.length > 1) return '将按 ' + parts.length + ' 个别名生效：' + parts.join(' / ')
  return ''
})

/**
 * 视图预设。
 *
 * 选项与计数一起给出（「待核对 · 3357」）：换算成比例让用户自己乘，
 * 不如直接把数字摆在他要选的那一项上。计数跟着 counts 走，不受搜索词影响。
 */
function viewOptions() {
  const c = counts.value
  return [
    { value: 'main', label: '默认 · ' + c.main },
    { value: 'all', label: '全部页面 · ' + c.all },
    { value: 'draft', label: '待核对 · ' + c.draft },
    { value: 'verified', label: '已核对 · ' + c.verified },
    { value: 'rejected', label: '弃用 · ' + c.rejected },
    { value: 'unnamed', label: '无中文名 · ' + c.unnamed },
    { value: 'nochunk', label: '无正文 · ' + c.noChunk },
  ]
}

/**
 * 板块骨架：一眼看到各板块多大、当前在哪个。
 *
 * 「全部」那格用 counts.all（全库口径），板块格用后端给的 terms ——
 * 板块计数不随搜索词变，这样切来切去位置不会跳。
 */
const boardTabs = computed(() => [
  { key: '', label: '全部', icon: '', count: counts.value.all },
  ...boards.value
    .filter((b) => b.terms > 0)
    .map((b) => ({ key: b.key, label: b.label, icon: b.icon, count: b.terms })),
])

// ==================== 小工具 ====================

/** 中文名的别名分隔符：与后端保持一致（不一致就会出现"显示 2 个、实际 3 个"） */
function splitAliases(zh: string): string[] {
  return (zh || '').split(/[、/｜|]/).map((s) => s.trim()).filter((s) => s.length > 0)
}

function statusTone(s: string): 'good' | 'bad' | 'warn' | 'neutral' {
  return s === 'verified' ? 'good' : s === 'rejected' ? 'bad' : s === 'draft' ? 'warn' : 'neutral'
}

/**
 * 成功提示 3.5 秒后自己消失；错误提示留着 ——
 * 一闪而过的报错等于没报，用户正要照着它去排查。
 */
let flashTimer = 0
function flash(text: string, fine = true) {
  if (!fine) { error.value = text; return }
  ok.value = text
  window.clearTimeout(flashTimer)
  flashTimer = window.setTimeout(() => { ok.value = '' }, 3500)
}

// ==================== 取数 ====================

/**
 * 把抽屉重新挂到列表里的新对象上。
 *
 * 后端每次返回的是新对象，抽屉若还攥着旧引用，保存完就看不到新值 ——
 * 那种「点了没反应」最难查。选中的那条已经不在当前筛选里时关掉抽屉，
 * 免得显示一条列表上已经不存在的词条。
 */
function resyncSelected() {
  const cur = selected.value
  if (!cur) return
  const fresh = items.value.find((t) => t.en === cur.en)
  if (fresh) selected.value = fresh
  else closeDrawer()
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const r = await adminApi.get<KbTermListResponse>(
      '/kb/terms?q=' + encodeURIComponent(q.value)
      + '&view=' + view.value
      + '&board=' + encodeURIComponent(board.value)
      + '&limit=' + LIST_LIMIT)
    items.value = r.items ?? []
    total.value = r.total ?? 0
    counts.value = r.counts ?? { ...EMPTY_COUNTS }
    boards.value = r.boards ?? []
    available.value = r.available !== false
    resyncSelected()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

function pickView(k: string) {
  view.value = k as ViewKey
  editing.value = null
  statusOpen.value = null
  void load()
}

function pickBoard(k: string) {
  board.value = k
  editing.value = null
  statusOpen.value = null
  void load()
}

/** 搜索：两种模式各自重新取数 */
async function search() {
  if (mode.value === 'review') await startReview()
  else await load()
}

// ==================== 名字：改 / 清空 / 换状态 ====================

/**
 * 写一条词条的名字与状态。
 *
 * 新契约下这是一次普通 upsert：不再需要先把 page / category 读回来再原样写回
 * （那两列在后端已是派生数据），所以前端也没有"少带一个字段就把数据抹掉"的风险。
 */
async function postTerm(en: string, zh: string, status?: TermStatus): Promise<boolean> {
  busy.value = true
  error.value = ''
  try {
    const body: { en: string; zh: string; status?: TermStatus } = { en, zh }
    if (status) body.status = status
    await adminApi.post('/kb/terms', body)
    return true
  } catch (e) {
    error.value = describeError(e)
    return false
  } finally {
    busy.value = false
  }
}

function startEdit(t: KbTerm) {
  editing.value = t.en
  zhDraft.value = t.zh
  statusOpen.value = null
}
function cancelEdit() {
  editing.value = null
  zhDraft.value = ''
}

async function saveZh(t: KbTerm) {
  const zh = zhDraft.value.trim()
  if (zh === t.zh) { cancelEdit(); return }
  if (await postTerm(t.en, zh, (t.status || 'draft') as TermStatus)) {
    flash(zh ? '已保存「' + t.en + '」的中文名，立即生效' : '已清空「' + t.en + '」的中文名')
    cancelEdit()
    await load()
  }
}

function toggleStatusMenu(t: KbTerm) {
  statusOpen.value = statusOpen.value === t.en ? null : t.en
}

async function setStatus(t: KbTerm, next: TermStatus) {
  statusOpen.value = null
  if (next === t.status) return
  if (await postTerm(t.en, t.zh, next)) {
    flash('「' + t.en + '」已标为 ' + STATUS_LABEL[next])
    await load()
  }
}

async function clearZh(t: KbTerm) {
  if (!window.confirm('清空「' + t.en + '」的中文名？\n\n状态会回到「待核对」，词条正文不受影响。')) return
  statusOpen.value = null
  busy.value = true
  error.value = ''
  try {
    await adminApi.post('/kb/terms/clear', { en: t.en })
    flash('已清空「' + t.en + '」的中文名')
    await load()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busy.value = false
  }
}

// ==================== 整条下架 / 恢复 ====================

// ==================== 抽屉 ====================

async function openDrawer(t: KbTerm) {
  selected.value = t
  dZh.value = t.zh
  dStatus.value = (t.status || 'draft') as TermStatus
  editing.value = null
  statusOpen.value = null
  boardEditing.value = false
  await loadChunks(t.en)
}

function closeDrawer() {
  selected.value = null
  chunks.value = []
  boardEditing.value = false
}

/**
 * 读一个词条的文本块。
 *
 * title 一定是 **query 参数**，不能拼进路径 —— 页面标题里带 /（如 Sets/Clothes），
 * 拼进去会被当成多一级路径，后端匹配不上。
 */
async function loadChunks(en: string) {
  chunksLoading.value = true
  try {
    const r = await adminApi.get<KbTermChunksResponse>(
      '/kb/terms/chunks?title=' + encodeURIComponent(en))
    chunks.value = r.chunks ?? []
    } catch (e) {
    error.value = describeError(e)
  } finally {
    chunksLoading.value = false
  }
}

function onRowClick(r: KbTerm, i: number) {
  if (mode.value === 'review') { idx.value = i; return }
  if (selected.value?.en === r.en) { closeDrawer(); return }
  void openDrawer(r)
}

/**
 * 抽屉里的三个动作。
 *
 * 都从 selected 里自己取词条、不接收参数：模板里写 `@click="fn(selected)"` 时，
 * 编译器会把它包进一个回调，TS 在那个作用域里不再保留 v-if 的收窄，
 * selected 又变回可空 —— 于是每个按钮都要写一次非空断言。
 */
async function saveDrawerName() {
  const t = selected.value
  if (!t) return
  const zh = dZh.value.trim()
  if (await postTerm(t.en, zh, dStatus.value)) {
    flash(zh ? '已保存「' + t.en + '」的名字' : '已清空「' + t.en + '」的中文名')
    await load()
  }
}

function onDrawerStatus(v: string) { dStatus.value = v as TermStatus }

async function clearDrawerZh() {
  const t = selected.value
  if (t) await clearZh(t)
}

/**
 * 整条词条下架 / 恢复 = 对它名下的每个块各调一次块级接口。
 *
 * <p>为什么按块循环而不是加一个「下架整条」的接口：块表里**没有「词条」这个实体**，
 * 词条只是「同一批块共享同一个标题」的视角。要按块 id 说的话，整条下架本来就
 * 是 N 次块级下架；在后端再包一层只会多一份口径，还得处理「一半成功」的回滚。
 * 前端在这里保证「跳过已经是目标状态的块」，所以重复点不会白跑请求。
 */
async function retireSelected() {
  const t = selected.value
  if (!t) return
  if (!chunks.value.length) {
    error.value = '这条词条没有正文块，没有可下架的内容'
    return
  }
  const retire = !t.retired
  const ask = retire
    ? '下架词条「' + t.en + '」？\n\n将下架该词条的 ' + chunks.value.length + ' 个文本块，检索不再用到它们（可恢复）。'
    : '恢复词条「' + t.en + '」？\n\n它的 ' + chunks.value.length + ' 个文本块会重新参与检索。'
  if (!window.confirm(ask)) return
  busy.value = true
  error.value = ''
  try {
    for (const c of chunks.value) {
      if (c.retired === retire) continue
      await adminApi.post('/kb/blocks/retire', { id: c.id, retired: retire })
    }
    t.retired = retire
    await loadChunks(t.en)
    await load()
    flash(retire ? '已下架整条词条' : '已恢复整条词条')
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busy.value = false
  }
}

/** 抽屉里要显示的原始分类（模板里不能写 catOf(selected)，原因同上） */
const selCat = computed(() => (selected.value ? catOf(selected.value) : ''))

// ==================== 块级操作 ====================

/**
 * 块级编辑。
 *
 * <p>块的身份是 **id**（如 {@code flame-altar-0}），不再是行号 —— 所以这里全部走
 * {@code /kb/blocks/*}（按 id 操作），而不是历史上那套按行号的
 * {@code /kb/terms/chunk}（切到块表之后那套已经停用，调用会明确报错）。
 *
 * <p>改正文会**重算这一块的向量**（只这一块，不是全量重建）：块 id 不变，
 * 所以「哪条问答引用过它」这类历史引用不会失效。
 */
const isChunkEditing = (c: KbTermChunk) => c.id in chunkDrafts.value
const isChunkDirty = (c: KbTermChunk) =>
  c.id in chunkDrafts.value && chunkDrafts.value[c.id] !== c.text

function startChunkEdit(c: KbTermChunk) {
  chunkDrafts.value = { ...chunkDrafts.value, [c.id]: c.text }
  ok.value = ''
}

function cancelChunkEdit(c: KbTermChunk) {
  const d = { ...chunkDrafts.value }
  delete d[c.id]
  chunkDrafts.value = d
}

function onChunkDraft(c: KbTermChunk, v: string) {
  chunkDrafts.value = { ...chunkDrafts.value, [c.id]: v }
  ok.value = ''
}

async function saveChunk(c: KbTermChunk) {
  const t = selected.value
  const text = (chunkDrafts.value[c.id] ?? '').trim()
  if (!text) {
    error.value = '正文不能为空 —— 它会被向量化'
    return
  }
  savingChunk.value = c.id
  error.value = ''
  try {
    await adminApi.post('/kb/blocks/update', { id: c.id, text })
    cancelChunkEdit(c)
    if (t) await loadChunks(t.en)
    flash('块 ' + c.id + ' 已保存（只重算了这一块的向量）')
  } catch (e) {
    error.value = describeError(e)
  } finally {
    savingChunk.value = null
  }
}

/** 下架 / 恢复单个块。已下架的块文本仍可读、可改（改完再恢复也行） */
async function retireChunk(c: KbTermChunk, retired: boolean) {
  if (retired && !window.confirm(
    '下架块 ' + c.id + '？\n\n检索不再用这一块，词条里的其它块不受影响（可恢复）。')) return
  const t = selected.value
  retireBusy.value = c.id
  error.value = ''
  try {
    await adminApi.post('/kb/blocks/retire', { id: c.id, retired })
    if (t) await loadChunks(t.en)
    await load()
    flash('块 ' + c.id + ' 已' + (retired ? '下架' : '恢复'))
  } catch (e) {
    error.value = describeError(e)
  } finally {
    retireBusy.value = null
  }
}

// ==================== 抽屉：板块（改原始分类的归属） ====================

/** 词条的原始分类：取第一个（长度不合理或没有分类时留空，让用户自己挑） */
function catOf(t: KbTerm): string {
  return (t.cats ?? []).find((c) => typeof c === 'string' && c.length > 0 && c.length <= 60) ?? ''
}

async function openBoardEditor() {
  const t = selected.value
  if (!t) return
  if (boardEditing.value) { boardEditing.value = false; return }
  error.value = ''
  try {
    const c = await adminApi.get<{
      items: CategoryItem[]
      groups: { key: string; label: string; icon: string }[]
    }>('/kb/categories')
    categoryItems.value = c.items ?? []
    groupOptions.value = [
      ...(c.groups ?? []).map((g) => ({ value: g.key, label: g.icon + ' ' + g.label })),
      { value: '__hidden__', label: '🙈 隐藏' },
    ]
    groupDraft.value = categoryItems.value.find((i) => i.raw === catOf(t))?.groupKey ?? ''
    boardEditing.value = true
  } catch (e) {
    error.value = describeError(e)
  }
}

const affectedChunks = computed(() => {
  const t = selected.value
  if (!t) return 0
  return categoryItems.value.find((i) => i.raw === catOf(t))?.count ?? t.chunkCount
})

/**
 * 板块是由块自带的**原始分类**（cats[]）映射来的，所以这里改的是
 * 「这个原始分类归到哪个大类」—— 同分类的所有词条会一起动。
 * 界面上必须这么说，否则会被当成"只改这一条"。
 */
async function saveBoard() {
  const t = selected.value
  if (!t) return
  const raw = catOf(t)
  if (!raw) { error.value = '这个词条没有可改的原始分类'; return }
  const cur = categoryItems.value.find((i) => i.raw === raw)?.groupKey ?? ''
  if (groupDraft.value === cur) { boardEditing.value = false; return }
  const from = groupOptions.value.find((g) => g.value === cur)?.label ?? (cur || '自动规则')
  const to = groupOptions.value.find((g) => g.value === groupDraft.value)?.label ?? groupDraft.value
  if (!window.confirm(
    '把原始分类「' + raw + '」从「' + from + '」改到「' + to + '」？\n\n'
    + '这个分类下的 ' + affectedChunks.value + ' 个文本块、所有用到它的词条会一起换板块。')) return
  busy.value = true
  error.value = ''
  try {
    await adminApi.post('/kb/categories/set', { raw, groupKey: groupDraft.value })
    flash('「' + raw + '」已改到 ' + to)
    boardEditing.value = false
    await load()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busy.value = false
  }
}

/**
 * 清空**全部**词条（不可逆）。
 *
 * <p>为什么要有这个按钮、而不是让人去改数据库：词表靠进程内的版本号感知变化，
 * 外部改库**不会**让它重载 —— 机器人会继续用旧的几千条名字检索，而且不报错。
 * 走接口会 +1 版本，删完立即生效。
 */
async function clearAllTerms() {
  if (!window.confirm('确定清空**全部词条**？' + String.fromCharCode(10, 10) +
    '这会删掉所有中文名/别名（纯人工成果），不可恢复。' + String.fromCharCode(10) +
    '如果只是想重导一份术语表，不需要清空。')) return
  busy.value = true
  error.value = ''
  try {
    const r = await adminApi.post<{ ok: boolean; deleted: number }>('/kb/terms/clear-all', { confirm: true })
    flash('已清空全部词条：' + r.deleted + ' 条')
    await load()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busy.value = false
  }
}

// ==================== 新建词条 ====================

/**
 * 新建词条的前端入口。
 *
 * <p>只管**名字**：正文（块）现在来自「文档」面板导入的文档，不再从这里建。
 * 所以这里收两个字段：英文名（主键）和中文名（可含别名，用「、」分隔）。
 */
function openCreate() {
  creating.value = true
  nc.value = { en: '', zh: '' }
  ok.value = ''
  error.value = ''
}

async function submitCreate() {
  const title = nc.value.en.trim()
  if (!title) { error.value = '英文名必填 —— 它就是词条的主键'; return }
  savingNew.value = true
  error.value = ''
  try {
    const zh = nc.value.zh.trim()
    await adminApi.post('/kb/terms', { en: title, zh, status: zh ? 'verified' : 'draft' })
    flash('已新建词条「' + title + '」' + (zh ? '（中文名：' + zh + '）' : ''))
    creating.value = false
    await load()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    savingNew.value = false
  }
}

// ==================== 核对模式 ====================

/** 拉一批待核对条目，去重后追加到队尾；返回这次真正追加了几条 */
async function fillQueue(): Promise<number> {
  loading.value = true
  error.value = ''
  try {
    const r = await adminApi.get<KbTermListResponse>(
      '/kb/terms?view=draft&q=' + encodeURIComponent(q.value)
      + '&limit=' + REVIEW_PAGE + '&offset=0')
    if (r.counts) counts.value = r.counts
    const known = new Set(queue.value.map((x) => x.en))
    let added = 0
    for (const t of r.items ?? []) {
      if (known.has(t.en)) continue
      known.add(t.en)
      queue.value.push({ ...t, originalZh: t.zh })
      added++
    }
    return added
  } catch (e) {
    error.value = describeError(e)
    return 0
  } finally {
    loading.value = false
  }
}

/** 指针走到队尾就继续拉；一次拉不到就停（否则会空转成死循环） */
async function ensureCurrent() {
  while (idx.value >= queue.value.length) {
    const added = await fillQueue()
    if (added === 0) break
  }
}

/** 进入核对模式，或搜索词变了重新开始 */
async function startReview() {
  queue.value = []
  idx.value = 0
  picked.value = []
  await fillQueue()
}

async function switchMode(m: Mode) {
  if (mode.value === m) return
  mode.value = m
  picked.value = []
  ok.value = ''
  closeDrawer()
  if (m === 'review') await startReview()
  else await load()
}

/**
 * 给一条定状态。改过中文名就一起写，没改过也只写一次 ——
 * 新接口一次调用同时带 zh 与 status，不再像旧的两条路径要分情况。
 */
async function applyReviewStatus(item: ReviewItem, status: TermStatus): Promise<boolean> {
  const zh = item.zh.trim()
  if (!zh) {
    flash('中文名不能为空：想去掉这条请按 2 排除', false)
    return false
  }
  try {
    await adminApi.post('/kb/terms', { en: item.en, zh, status })
    item.originalZh = zh
    item.zh = zh
    item.status = status
    return true
  } catch (e) {
    flash(describeError(e), false)
    return false
  }
}

/** 把当前条目从队列里摘掉：指针不动，正好指到下一条 */
function dropCurrent(item: ReviewItem) {
  const at = queue.value.findIndex((x) => x.en === item.en)
  if (at >= 0) queue.value.splice(at, 1)
  const p = picked.value.indexOf(item.en)
  if (p >= 0) picked.value.splice(p, 1)
}

/** 1 / 2 键：定状态并前进。先动本地计数，让连按时进度条立刻有反应 */
async function decide(status: 'verified' | 'rejected') {
  const it = current.value
  if (!it || loading.value) return
  const fine = await applyReviewStatus(it, status)
  if (!fine) return
  if (status === 'verified') counts.value.verified++
  else counts.value.rejected++
  counts.value.draft = Math.max(0, counts.value.draft - 1)
  dropCurrent(it)
  await ensureCurrent()
}

/** 3 键：跳过 —— 不改状态，也不保存改动 */
async function skip() {
  if (!current.value) return
  idx.value++
  await ensureCurrent()
}

/** 在中文名输入框里按 Enter：存下改动、状态不变、然后前进 */
async function commitAndNext() {
  const it = current.value
  if (!it) return
  const zh = it.zh.trim()
  if (zh === it.originalZh) { await skip(); return }
  if (!zh) { flash('中文名不能为空：想去掉这条请按 2 排除', false); return }
  try {
    await adminApi.post('/kb/terms', { en: it.en, zh, status: (it.status || 'draft') as TermStatus })
    it.originalZh = zh
    flash('已保存译名：「' + it.en + '」')
    await skip()
  } catch (e) {
    flash(describeError(e), false)
  }
}

/** 共享 Input 只回传字符串，这里写回当前条目 */
function onZhInput(v: string) {
  const it = current.value
  if (it) it.zh = v
}

function togglePick(en: string, on: boolean) {
  const at = picked.value.indexOf(en)
  if (on && at < 0) picked.value.push(en)
  else if (!on && at >= 0) picked.value.splice(at, 1)
}

function toggleAll(on: boolean) {
  picked.value = on ? queue.value.map((x) => x.en) : []
}

/** 批量改状态（勾选后一起改） */
async function applyBatch(status: TermStatus) {
  if (!picked.value.length) { flash('先在表里勾选几条', false); return }
  try {
    const r = await adminApi.post<KbTermBatchResponse>('/kb/terms/batch', {
      items: picked.value, status,
    })
    if (r.counts) counts.value = r.counts
    const missed = r.missing ? r.missing.length : 0
    let text = '已改 ' + r.updated + ' 条'
    if (r.unchanged) text += '，' + r.unchanged + ' 条本来就是该状态'
    if (missed) text += '，' + missed + ' 条没找到'
    flash(text, missed === 0)
    const changed = new Set(picked.value)
    picked.value = []
    if (mode.value === 'review') {
      // 队列里只装 draft：被改成别的状态的要从队列里摘掉，否则会继续出现在核对流里
      if (status !== 'draft') {
        queue.value = queue.value.filter((x) => !changed.has(x.en))
        await ensureCurrent()
      }
    } else {
      await load()
    }
  } catch (e) {
    flash(describeError(e), false)
  }
}

// ==================== Excel 旁路：导出 / 导入 ====================

/**
 * 导出 TSV。
 *
 * client 的 request() 永远走 res.json()，所以导出接口返回的是
 * { ok, tsv, count } 而不是裸文本 —— 这里自己包成 Blob 再触发下载。
 */
async function exportTsv() {
  error.value = ''
  try {
    // ★ 把**当前筛选原样带上** —— 「看到什么就导出什么」。
    //   最关键的是 view=unnamed：自动导入的英文页有 651 条没有中文名，
    //   在网页上逐条点不现实，导到 Excel 填完再 /import 导回。
    //   带上 board 之后就能**按板块分批**（战斗装备 / 建造装饰 / 材料消耗 …）。
    const params = new URLSearchParams()
    if (view.value) params.set('view', view.value)
    if (board.value) params.set('board', board.value)
    if (q.value.trim()) params.set('q', q.value.trim())
    // ★ 导 xlsx（真 Excel 文件）。
    //   为什么不用 CSV：WPS/Excel 在中文 Windows 上另存 CSV 默认 GBK，
    //   而浏览器读文件固定按 UTF-8 解码 —— 中文变乱码**而且不报错**，直接写坏词条表。
    //   xlsx 内部是 UTF-8 的 XML，没有编码这回事。
    params.set('format', 'xlsx')
    const qs = params.toString()

    const r = await adminApi.get<KbTermExportResponse>('/kb/terms/export' + (qs ? '?' + qs : ''))
    const bytes = base64ToBytes(r.xlsxBase64 ?? '')
    // 传 .buffer 而不是 Uint8Array 本身：TS 5.7 起 Uint8Array 带上了 ArrayBufferLike 泛型，
    // 不再直接满足 BlobPart。这里 base64ToBytes 建的是独立数组，buffer 就是它自己，没有多余字节。
    const url = URL.createObjectURL(new Blob([bytes.buffer as ArrayBuffer], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    }))
    const a = document.createElement('a')
    a.href = url
    // 文件名带上筛选，否则分几批导出会互相覆盖，也对不上账
    a.download = 'kb-terms' + (view.value ? '-' + view.value : '')
      + (board.value ? '-' + board.value : '') + '.xlsx'
    document.body.appendChild(a)
    a.click()
    a.remove()
    // 立即 revoke 会让部分浏览器取消下载 —— 等一拍再释放
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    const scope = [view.value, board.value].filter(Boolean).join(' · ')
    flash('已导出 ' + r.count + ' 条词条（xlsx，WPS/Excel 双击直接打开）' + (scope ? ' —— ' + scope : ''))
  } catch (e) {
    error.value = describeError(e)
  }
}

/** 字节 → base64。分块处理：一次性展开大数组会超出 String.fromCharCode 的参数上限 */
function bytesToBase64(bytes: Uint8Array): string {
  let bin = ''
  const CHUNK = 0x8000
  for (let i = 0; i < bytes.length; i += CHUNK) {
    bin += String.fromCharCode(...bytes.subarray(i, i + CHUNK))
  }
  return btoa(bin)
}

/** base64 → 字节 */
function base64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

function pickImport() {
  fileInput.value?.click()
}

async function onImportFile(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  // 清掉 value：同一个文件连着导两次也要能触发 change
  input.value = ''
  if (!file) return
  busy.value = true
  error.value = ''
  try {
    // ★ 读**原始字节**再 base64，不读文本 ——
    //   file.text() 固定按 UTF-8 解码，读 xlsx 会得到乱码。
    const bytes = new Uint8Array(await file.arrayBuffer())
    const r = await adminApi.post<KbTermImportResponse>('/kb/terms/import', {
      xlsxBase64: bytesToBase64(bytes),
    })
    flash('导入完成：新增 ' + r.created + '，更新 ' + r.updated + '，跳过 ' + r.skipped)
    await load()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busy.value = false
  }
}

// ==================== 快捷键 ====================

/**
 * 全局快捷键，只在核对模式生效。
 *
 * 光标在输入框里时**不抢键** —— 否则中文名里连数字都打不进去。
 * 想用 1/2/3 先点一下卡片空白处；在输入框里按 Enter 是"保存并下一条"。
 *
 * ⚠️ 监听挂在 window 上（不是面板根元素），而且**必须按 activated / deactivated 挂摘**：
 * 父页面用 KeepAlive 缓存面板，切 tab 触发的是 deactivated 而**不是** unmounted ——
 * 只写 onUnmounted 的话，在核对模式切走之后快捷键依然生效：人看着分类页，
 * 按一下 1 却把词条标成了已核对。
 */
function onKey(ev: KeyboardEvent) {
  if (mode.value !== 'review') return
  const t = ev.target as HTMLElement | null
  const tag = t ? t.tagName : ''
  const typing = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'
    || (t !== null && t.isContentEditable)
  if (typing) return
  if (ev.ctrlKey || ev.metaKey || ev.altKey) return
  // 链接上按 Enter 是"打开页面"，别抢（鼠标点过链接后焦点会停在它上面）
  if (tag === 'A' && ev.key === 'Enter') return

  if (ev.key === '1') { ev.preventDefault(); void decide('verified') }
  else if (ev.key === '2') { ev.preventDefault(); void decide('rejected') }
  else if (ev.key === '3') { ev.preventDefault(); void skip() }
  else if (ev.key === 'Enter') { ev.preventDefault(); void commitAndNext() }
}

/**
 * 进入本面板只拉列表。
 * ⚠️ 核对队列**不在这里预拉** —— 切到核对模式才 startReview()，
 * 保持"没点核对模式就不多发一次 draft 查询"。
 */
onMounted(() => {
  window.addEventListener('keydown', onKey)
  void load()
})
/** 同一个函数引用重复 add / remove 是幂等的，所以挂载时被 onActivated 再挂一次没有副作用 */
onActivated(() => window.addEventListener('keydown', onKey))
onDeactivated(() => window.removeEventListener('keydown', onKey))
onUnmounted(() => {
  window.removeEventListener('keydown', onKey)
  window.clearTimeout(flashTimer)
})

/**
 * 页面栏上的筛选与动作由父页面 KbView 渲染，读数与动作在这里抛出去。
 * 一律用函数（不用 ref / computed）：视图渲染时调用，读到 view / q / loading
 * 的变化会计入视图的渲染副作用，值和禁用态都跟得上，类型也干净。
 */
defineExpose({
  reload: load,
  search,
  openCreate,
  clearAllTerms,
  pickImport,
  exportTsv,
  isLoading: () => loading.value || busy.value,
  /** 搜索框 */
  keywordValue: () => q.value,
  setKeyword: (v: string) => { q.value = v },
  /** 视图预设（选项表跟着面板走，父页面不另抄一份 —— 抄一份迟早和这里漂移） */
  viewValue: () => view.value,
  viewOptions,
  pickView,
  /** 模式 */
  switchMode,
  currentMode: () => mode.value,
  /** 核对进度读数（只在核对模式渲染，但这里始终给得出） */
  progress: () => ({
    processed: processed.value,
    total: counts.value.all,
    pct: progressPct.value,
  }),
  draftCount: () => counts.value.draft,
})
</script>

<template>
  <div class="panel-wrap" @keydown.esc="cancelEdit(); statusOpen = null">
    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-if="ok" tone="ok">{{ ok }}</Notice>
    <Notice v-if="!available" tone="warn">
      词条库还没就绪 —— 后端 kb_term 表为空或不可用，列表暂时没有内容。
    </Notice>

    <!-- 板块骨架：一眼看到各板块多大、当前在哪个 -->
    <div v-if="mode === 'list' && boards.length" class="boards">
      <button
        v-for="b in boardTabs" :key="b.key || 'all'"
        type="button" class="board" :class="{ on: board === b.key }"
        :aria-pressed="board === b.key" @click="pickBoard(b.key)"
      >
        <span class="b-label">{{ b.icon }} {{ b.label }}</span>
        <span class="b-count num">{{ b.count }}</span>
      </button>
    </div>

    <p class="faint note">
      共 <span class="num">{{ num(counts.all) }}</span> 个词条 ·
      有正文 <span class="num">{{ num(counts.withChunks) }}</span> ·
      有中文名 <span class="num">{{ num(counts.all - counts.unnamed) }}</span> ·
      已核对 <span class="num">{{ num(counts.verified) }}</span> /
      待核对 <span class="num">{{ num(counts.draft) }}</span>
      <template v-if="total !== counts.all"> · 当前筛选 <span class="num">{{ num(total) }}</span> 条</template>
    </p>

    <!-- ==================== 新建词条 ==================== -->
    <Panel v-if="creating" title="新建词条">
      <div class="nc">
        <label class="nc-f">
          <span class="nc-l">英文名 <b>必填</b></span>
          <Input :model-value="nc.en" placeholder="与 Wiki 页面标题一致，例如 Scrap Cup"
                 @update:model-value="(v: string) => (nc.en = v)" />
        </label>
        <label class="nc-f">
          <span class="nc-l">中文名</span>
          <Input :model-value="nc.zh" placeholder="例如 废料杯；多个别名用「、」分隔"
                 @update:model-value="(v: string) => (nc.zh = v)" />
        </label>
        <p class="faint nc-hint">
          这里只建**名字**。正文（块）请到「<b>文档</b>」面板导入文档 ——
          导入时会按块 id 建立正文与向量。
        </p>
        <div class="nc-ops">
          <Button size="sm" :disabled="savingNew" @click="creating = false">取消</Button>
          <Button size="sm" variant="primary" :disabled="savingNew" @click="submitCreate">
            {{ savingNew ? '创建中…' : '创建' }}
          </Button>
        </div>
      </div>
    </Panel>

    <!-- ==================== 核对模式 ==================== -->
    <Panel v-if="mode === 'review'" title="核对模式">
      <!--
        面板标题行上的出口。
        以前退出核对模式**只有**页面栏那个「列表」按钮 —— 它在页面最上方、
        和筛选 / 新建 / 导入挤在一排，名字里也没有"退出"两个字，进来之后
        人只会往面板里找出口（2026-10 反馈：进了核对模式找不到地方退）。
        标题行是这个面板自己的地盘，放在这里的出口不用去别处找。
      -->
      <template #actions>
        <Button size="sm" @click="switchMode('list')">退出核对模式</Button>
      </template>

      <!-- 进度条的数据源仍是 counts（后端返回的全库口径），不受搜索词影响 -->
      <div class="progress">
        <div class="track"><div class="fill" :style="{ width: progressPct + '%' }" /></div>
        <div class="pmeta faint">
          已核对 <b class="v-ok">{{ num(counts.verified) }}</b>
          · 已排除 <b class="v-no">{{ num(counts.rejected) }}</b>
          · 待核对 <b>{{ num(counts.draft) }}</b>
          · 共 {{ num(counts.all) }} 条（{{ progressPct }}%）
        </div>
      </div>

      <p class="faint kbd-note">
        快捷键：<kbd>1</kbd> 已核对 · <kbd>2</kbd> 排除 · <kbd>3</kbd> 跳过（不保存改动） ·
        <kbd>Enter</kbd> 保存译名并下一条。光标在输入框里时快捷键不生效（否则中文名里打不了数字），
        想用快捷键先点一下卡片空白处。
      </p>

      <Empty
        v-if="!current && !loading"
        text="没有待核对的条目了"
        hint="切回列表模式可以看已核对 / 已排除的，或换个搜索词"
      />

      <div v-else-if="current" class="card">
        <div class="card-head">
          <span class="card-en">{{ current.en }}</span>
          <Tag :tone="statusTone(current.status)">{{ STATUS_LABEL[current.status] ?? current.status }}</Tag>
          <span class="faint queue-pos">
            队列第 <span class="num">{{ idx + 1 }}</span> / <span class="num">{{ queue.length }}</span> 条
          </span>
        </div>

        <div class="card-row">
          <span class="lbl">中文名</span>
          <Input
            class="zh-input"
            :model-value="current.zh"
            aria-label="中文名"
            placeholder="多个别名用「、」分隔，例：灵火祭坛、火焰祭坛"
            @update:model-value="onZhInput"
            @keydown.enter.prevent="commitAndNext"
          />
        </div>
        <p v-if="aliasHint" class="faint alias-hint">{{ aliasHint }}</p>

        <div class="card-row">
          <span class="lbl">板块</span>
          <span class="val muted">{{ current.boardLabel || '—' }}</span>
          <span class="lbl">正文</span>
          <span class="val muted num">{{ current.chunkCount }} 块</span>
          <span class="lbl">页面</span>
          <a v-if="current.url" class="link" :href="current.url" target="_blank" rel="noopener noreferrer">
            {{ current.en }}
          </a>
          <span v-else class="val muted">—</span>
        </div>

        <div class="actions">
          <Button variant="primary" size="sm" @click="decide('verified')">1 · 已核对</Button>
          <Button variant="danger" size="sm" @click="decide('rejected')">2 · 排除</Button>
          <Button size="sm" @click="skip">3 · 跳过</Button>
        </div>
      </div>

      <div v-if="picked.length" class="batchbar">
        <span class="faint">已勾选 <span class="num">{{ picked.length }}</span> 条：</span>
        <Button size="sm" @click="applyBatch('verified')">标为已核对</Button>
        <Button size="sm" variant="danger" @click="applyBatch('rejected')">标为排除</Button>
        <Button size="sm" @click="toggleAll(false)">取消勾选</Button>
      </div>
    </Panel>

    <!-- ==================== 列表 ==================== -->
    <Panel
      :title="mode === 'review' ? '待核对队列' : '词条'"
      :count="mode === 'review' ? queue.length + ' 条' : total + ' 条'"
    >
      <p class="faint note legend">
        点一行{{ mode === 'review' ? '跳到那一条；' : '在右侧打开它的名字与正文；' }}
        <span class="chip">已下架</span>= 该词条的块全部退出检索，可恢复
      </p>

      <DataTable :rows="rows.length" :empty="emptyText" :empty-hint="emptyHint">
        <thead>
          <tr>
            <th v-if="mode === 'review'" class="pick">
              <input
                type="checkbox"
                aria-label="全选队列"
                :checked="allPicked"
                @change="toggleAll(($event.target as HTMLInputElement).checked)"
              />
            </th>
            <th>词条 / 块 id</th>
            <th>中文名 / 别名</th>
            <th>状态</th>
            <th>板块</th>
            <th class="num">块数</th>
            <th class="num">字数</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(r, i) in rows" :key="r.en"
            class="row"
            :class="{ open: mode === 'list' && selected?.en === r.en, current: mode === 'review' && i === idx }"
            @click="onRowClick(r, i)"
          >
            <td v-if="mode === 'review'" class="pick" @click.stop>
              <input
                type="checkbox"
                :aria-label="'勾选 ' + r.en"
                :checked="picked.includes(r.en)"
                @change="togglePick(r.en, ($event.target as HTMLInputElement).checked)"
              />
            </td>

            <td class="t">
              <span class="en">{{ r.en }}</span>
              <Tag v-if="r.retired" tone="bad">已下架</Tag>
              <Tag v-else-if="!r.chunkCount" tone="neutral">无正文</Tag>
            </td>

            <!--
              中文名 / 别名：点一下就地编辑，Enter 保存，Esc 取消（核对模式下由卡片负责）。
              ⚠️ @click.stop 只加在**真正可交互的那两个东西**上（编辑框 / 名字按钮），
              不能加在 <td> 上 —— 加在单元格上会让这一格的空白区域吃掉点击，
              整行就只剩「词条」那一列能唤出详情（2026-10-01 反馈的问题）。
            -->
            <td v-if="mode === 'list'" class="zh">
              <div v-if="editing === r.en" class="edit" @click.stop>
                <Input
                  v-model="zhDraft" class="w-zh" aria-label="中文名与别名"
                  placeholder="多个别名用「、」分隔"
                  @keyup.enter="saveZh(r)" @keyup.esc="cancelEdit()"
                />
                <div class="edit-ops">
                  <Button size="sm" variant="primary" :disabled="busy" @click="saveZh(r)">
                    {{ busy ? '保存中…' : '保存' }}
                  </Button>
                  <Button size="sm" :disabled="busy" @click="cancelEdit">取消</Button>
                </div>
              </div>
              <button v-else type="button" class="zhv" :class="{ empty: !r.zh }" @click.stop="startEdit(r)">
                <span>{{ r.zh || '未命名' }}</span>
                <span v-if="r.aliases.length > 1" class="faint al">另 {{ r.aliases.length - 1 }} 个别名</span>
              </button>
            </td>
            <td v-else class="zh">{{ r.zh || '—' }}</td>

            <!-- 状态本身可点：点开是「已核对 / 待核对 / 弃用 + 清空中文名」。
                 同样只在下拉这一小块上 stop，其余部分让点击冒泡到整行 -->
            <td v-if="mode === 'list'" class="st">
              <div class="picker" @click.stop>
                <button
                  type="button" class="trig" :aria-expanded="statusOpen === r.en"
                  :disabled="busy" @click="toggleStatusMenu(r)"
                >
                  <Tag :tone="statusTone(r.status)">{{ STATUS_LABEL[r.status] ?? r.status }}</Tag>
                </button>
                <div v-if="statusOpen === r.en" class="menu">
                  <button
                    v-for="o in DRAWER_STATUS" :key="o.value"
                    type="button" class="opt" :class="{ on: o.value === r.status }"
                    @click="setStatus(r, o.value as TermStatus)"
                  >{{ o.label }}</button>
                  <button type="button" class="opt danger" @click="clearZh(r)">清空中文名</button>
                </div>
              </div>
            </td>
            <td v-else><Tag :tone="statusTone(r.status)">{{ STATUS_LABEL[r.status] ?? r.status }}</Tag></td>

            <td class="bd">{{ r.boardLabel || '—' }}</td>
            <td class="num">{{ r.chunkCount }}</td>
            <td class="num">{{ num(r.chars) }}</td>
          </tr>
        </tbody>
      </DataTable>
    </Panel>

    <!--
      抽屉：选中词条的「名字 + 正文」。
      用 Teleport 挂到 body —— 固定定位的祖先里只要有一个带 transform / 溢出裁剪，
      position:fixed 就不再相对视口，抽屉会被裁在面板里。
    -->
    <Teleport to="body">
      <div v-if="selected" class="drawer-layer">
        <div class="drawer-mask" @click="closeDrawer" />
        <aside class="drawer" role="dialog" aria-modal="true" :aria-label="'词条 ' + selected.en">
          <header class="dr-head">
            <div class="dr-title">
              <span class="dr-en">{{ selected.en }}</span>
              <span class="dr-tags">
                <Tag :tone="statusTone(selected.status)">{{ STATUS_LABEL[selected.status] ?? selected.status }}</Tag>
                <Tag v-if="selected.retired" tone="bad">已下架</Tag>
                <a v-if="selected.url" class="link" :href="selected.url" target="_blank" rel="noopener noreferrer">
                  Wiki 原页 ↗
                </a>
              </span>
            </div>
            <button type="button" class="dr-close" aria-label="关闭" @click="closeDrawer">✕</button>
          </header>

          <div class="dr-body">
            <!-- ---------- 名字区（来自旧术语核对） ---------- -->
            <section class="dr-sec">
              <h3 class="dr-h">名字</h3>
              <label class="dr-field">
                <span class="dr-lbl">中文名 / 别名</span>
                <Input
                  v-model="dZh" placeholder="多个别名用「、」分隔，例：灵火祭坛、火焰祭坛"
                  aria-label="中文名与别名"
                />
              </label>
              <div class="dr-row">
                <span class="dr-lbl">状态</span>
                <Select
                  class="dr-status"
                  :model-value="dStatus"
                  :options="DRAWER_STATUS"
                  @update:model-value="onDrawerStatus"
                />
              </div>
              <p class="faint dr-note">
                中文名可以写多个别名 —— 任一个出现在问题里都能命中原词。
                保存后<strong>立即生效</strong>，下一次提问就会用上新词。
              </p>
              <div class="dr-ops">
                <Button size="sm" variant="primary" :disabled="busy" @click="saveDrawerName">
                  {{ busy ? '保存中…' : '保存' }}
                </Button>
                <Button size="sm" :disabled="busy" @click="clearDrawerZh">清空中文名</Button>
              </div>
            </section>

            <!-- ---------- 归属区 ---------- -->
            <section class="dr-sec">
              <h3 class="dr-h">板块</h3>
              <div class="dr-row">
                <span class="dr-val">{{ selected.boardLabel || '—' }}</span>
                <Button size="sm" @click="openBoardEditor">
                  {{ boardEditing ? '收起' : '改归属' }}
                </Button>
              </div>
              <p class="faint dr-note">
                板块由词条自带的<b>原始分类</b>映射而来，不是单独存的字段。
              </p>
              <div v-if="boardEditing" class="bpanel">
                <div class="bhead">
                  <span class="dr-lbl">原始分类</span>
                  <span class="mono bcat">{{ selCat || '（没有分类）' }}</span>
                  <span class="faint">，把这个分类归到</span>
                  <Select class="bselect" v-model="groupDraft" :options="groupOptions" />
                </div>
                <p class="faint dr-note warn-line">
                  这个分类下的 <span class="num">{{ affectedChunks }}</span> 个文本块、
                  所有用到它的词条会一起换板块，不是只改这一条。
                </p>
                <div class="dr-ops">
                  <Button size="sm" variant="primary" :disabled="busy" @click="saveBoard">
                    {{ busy ? '保存中…' : '保存' }}
                  </Button>
                  <Button size="sm" :disabled="busy" @click="boardEditing = false">取消</Button>
                </div>
              </div>
            </section>

            <!-- ---------- 标签区 ---------- -->
            <section v-if="selected.cats && selected.cats.length" class="dr-sec">
              <h3 class="dr-h">
                标签
                <span class="faint dr-cnt">{{ selected.cats.length }} 个</span>
              </h3>
              <div class="taglist">
                <span v-for="c in selected.cats" :key="c" class="tagchip">{{ c }}</span>
              </div>
              <p class="faint dr-note">
                标签就是 wiki 的<b>原始分类</b>，跟着文本块走，不是词条上单独存的字段。
                它显示成什么中文、归到哪个大类，在「<b>分类</b>」面板里统一管理 ——
                改一次，所有带这个标签的词条一起变。
              </p>
            </section>

            <!-- ---------- 正文区（来自旧关键词） ---------- -->
            <section class="dr-sec">
              <h3 class="dr-h">
                正文
                <span class="faint dr-cnt">{{ selected.chunkCount }} 块 · {{ num(selected.chars) }} 字</span>
                <Button
                  size="sm"
                  class="dr-retire"
                  :variant="selected.retired ? 'ghost' : 'danger'"
                  :disabled="busy || chunksLoading || !chunks.length"
                  @click="retireSelected"
                >{{ selected.retired ? '恢复整条' : '下架整条' }}</Button>
              </h3>
              <p class="faint dr-note">
                每块都能<b>就地改正文</b>（保存时只重算这一块的向量，块 id 不变），
                也能单独下架；「文档」面板里是同一批块的另一个入口。
              </p>

              <div v-if="chunksLoading" class="faint dr-note">读取中…</div>

              <div v-for="c in chunks" :key="c.id" class="chunk" :class="{ off: c.retired }">
                <div class="chead">
                  <span class="cnum">{{ c.id }}</span>
                  <Tag v-if="c.retired" tone="bad">已下架</Tag>
                  <span class="faint cchars num">{{ c.chars }} 字</span>
                  <span class="chead-ops">
                    <Button
                      v-if="!isChunkEditing(c)"
                      size="sm"
                      @click="startChunkEdit(c)"
                    >改正文</Button>
                    <Button
                      size="sm"
                      :variant="c.retired ? 'ghost' : 'danger'"
                      :disabled="retireBusy === c.id"
                      @click="retireChunk(c, !c.retired)"
                    >{{ c.retired ? '恢复' : '下架' }}</Button>
                  </span>
                </div>

                <template v-if="isChunkEditing(c)">
                  <Input
                    :model-value="chunkDrafts[c.id]"
                    multiline
                    :rows="6"
                    mono
                    :aria-label="'块 ' + c.id + ' 的正文'"
                    @update:model-value="onChunkDraft(c, $event)"
                  />
                  <div class="chead-ops">
                    <Button
                      size="sm" variant="primary"
                      :disabled="savingChunk === c.id"
                      @click="saveChunk(c)"
                    >{{ savingChunk === c.id ? '保存中…' : '保存（重算这一块向量）' }}</Button>
                    <Button size="sm" :disabled="savingChunk === c.id" @click="cancelChunkEdit(c)">取消</Button>
                    <span v-if="isChunkDirty(c)" class="faint">已改动，未保存</span>
                  </div>
                </template>
                <p v-else class="chunk-text">{{ c.text }}</p>
              </div>

              <p v-if="!chunksLoading && !chunks.length" class="faint dr-note">
                这个块 id 没有对应的正文块（可能是导入之前留下的名字，或手工加的名字）。
                正常情况下来源是文档导入：一个块一条词条。
              </p>
            </section>
          </div>
        </aside>
      </div>
    </Teleport>

    <!-- 导入用的文件选择器：藏在模板里，由页面栏的「导入」按钮触发 -->
    <input
      ref="fileInput" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
      class="hidden-file" aria-hidden="true" tabindex="-1" @change="onImportFile"
    />
  </div>
</template>

<style scoped>
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

/* 筛选行（视图 / 模式 / 搜索 / 新建 / 导入导出 / 刷新）在页面栏 —— KbView 的 AdminPage#actions。 */
.note { font-size: var(--fs-meta); margin: 0; }
.legend { margin-bottom: var(--sp-4); }
.spacer { flex: 1; }
.chip {
  background: var(--surface-inset);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-xs);
  padding: 0 6px;
  font-size: var(--fs-meta);
}

/* ==================== 板块骨架 ==================== */
.boards { display: flex; flex-wrap: wrap; gap: var(--sp-2); }
.board {
  display: inline-flex; align-items: center; gap: var(--sp-2);
  background: var(--surface-panel);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-sm);
  padding: var(--sp-2) var(--sp-3);
  font: inherit; font-size: var(--fs-sm); color: var(--ink-dim);
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease);
}
.board:hover { background: var(--bg-raised); border-color: var(--edge-hover); color: var(--ink); }
.board:active { background: var(--surface-active); }
.board.on { background: var(--flame-veil); border-color: var(--flame); color: var(--flame-bright); }
.board.on:hover { background: var(--flame-glow); }
.b-count { font-size: var(--fs-meta); color: var(--ink-faint); }
.board.on .b-count { color: var(--flame); }

/*
  ⚠️ 这里**不能**给 td 设 display:flex。
  td 一旦不是 table-cell 就脱离了表格布局：它不再随行高拉伸，
  于是它那一列的底部边框（行分隔线）会比同排其它列高出一截 ——
  在列边界处看就是「隔断线断了/错位」。实测高度差约 10px（46.25 vs 56.2）。
  垂直居中要用 vertical-align（表格自带的机制），名称与标签的间距用相邻兄弟外边距。
*/
:deep(td.t) { font-family: var(--font-mono); vertical-align: middle; }
:deep(td.t) > * + * { margin-left: var(--sp-2); }
:deep(tr.row) { cursor: pointer; }
:deep(tr.row.open) { background: var(--surface-hover); }
:deep(td.zh) { vertical-align: middle; }
:deep(td.st) { white-space: nowrap; vertical-align: middle; }
:deep(td.bd) { font-size: var(--fs-meta); color: var(--ink-dim); }

/* 中文名的就地编辑入口：看起来像文字，点下去是编辑 */
.zhv {
  display: flex; flex-direction: column; align-items: flex-start; gap: 2px;
  background: none; border: 1px solid transparent; border-radius: var(--r-xs);
  padding: 2px 6px; margin: -2px -6px;
  font: inherit; color: var(--ink); text-align: left; cursor: text;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}
.zhv:hover { background: var(--surface-hover); border-color: var(--edge-soft); }
.zhv.empty { color: var(--ink-faint); font-style: italic; }
.al { font-size: var(--fs-meta); }

.edit { display: flex; flex-direction: column; gap: var(--sp-2); min-width: 240px; }
.w-zh { width: 220px; }
.edit-ops { display: flex; gap: var(--sp-2); }

/* 状态：属性本身可点，点开是几个选项 */
.picker { position: relative; display: inline-block; }
.trig {
  background: none; border: 1px solid transparent; border-radius: var(--r-xs);
  padding: 1px; cursor: pointer;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}
.trig:hover { background: var(--surface-hover); border-color: var(--edge-hover); }
.trig:disabled { opacity: .5; cursor: not-allowed; }
.menu {
  position: absolute; left: 0; top: calc(100% + 4px); z-index: var(--z-drawer);
  display: flex; flex-direction: column; min-width: 118px;
  background: var(--surface-raised);
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  box-shadow: var(--shadow-lift);
  padding: var(--sp-1);
}
.opt {
  background: none; border: 0; border-radius: var(--r-xs);
  padding: var(--sp-2) var(--sp-3); text-align: left;
  font: inherit; font-size: var(--fs-sm); color: var(--ink-dim); cursor: pointer;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.opt:hover { background: var(--surface-hover); color: var(--ink); box-shadow: inset 0 0 0 1px var(--edge-soft); }
.opt.on { color: var(--flame-bright); background: var(--flame-veil); }
.opt.danger { color: var(--rust); }
.opt.danger:hover { background: var(--rust-veil); color: var(--rust); }

/* ==================== 核对模式 ==================== */
.progress { margin-bottom: var(--sp-3); }
.track {
  height: 6px;
  border-radius: var(--r-pill);
  background: var(--surface-inset);
  border: 1px solid var(--edge-soft);
  overflow: hidden;
}
.fill {
  height: 100%;
  background: linear-gradient(90deg, var(--flame-deep), var(--flame-bright));
  transition: width var(--dur) var(--ease);
}
.pmeta { font-size: var(--fs-meta); margin-top: var(--sp-2); }
/* 进度数字一律等宽 + tabular-nums：连按 1/2 时数字位数变化不会让整行左右抖 */
.pmeta b { font-weight: var(--fw-semi); font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.v-ok { color: var(--moss); }
.v-no { color: var(--rust); }

/* 快捷键提示：kbd 是这套界面里唯一的"按键"视觉语言 */
.kbd-note { font-size: var(--fs-meta); line-height: 1.9; margin: 0 0 var(--sp-4); }
kbd {
  font-family: var(--font-mono);
  font-size: var(--fs-meta);
  border: 1px solid var(--edge);
  border-bottom-width: 2px;
  border-radius: var(--r-xs);
  padding: 0 5px;
  background: var(--bg-raised);
  color: var(--ink);
}

/* 逐条核对卡：层级靠背景抬升一档（surface-raised）表达，不靠多描一层粗框 */
.card {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  background: var(--surface-raised);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-md);
  padding: var(--sp-4);
  box-shadow: var(--shadow-inset);
}
.card-head { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.card-en {
  font-family: var(--font-mono);
  font-size: var(--fs-section);
  color: var(--flame-bright);
  overflow-wrap: anywhere;
}
.queue-pos {
  margin-left: auto;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: var(--fs-meta);
}
/*
  ⚠️ 这个 class 叫 .card-row，**不能**叫 .row ——
  下面表格里的 <tr> 已经占了 .row（见 :deep(tr.row)）。两者同名时，
  这条 display: flex 会落到 <tr> 上：tr 一旦不是 table-row 就不再参与表格列布局，
  它的每个 <td> 退化成各自排布的 flex item —— 于是每一行的列位置都不一样，
  整张表跟表头完全对不上（2026-10-01 修的就是这个）。
  惯例：.row 是表格行的钩子（只管 cursor/背景），卡片内部的横排用 .card-row。
*/
.card-row { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.lbl { font-size: var(--fs-meta); color: var(--ink-faint); min-width: 44px; }
.val { font-size: var(--fs-body); }
.muted { color: var(--ink-dim); }
.zh-input { flex: 1 1 280px; }
.alias-hint { font-size: var(--fs-meta); margin: 0; }

/* 「页面」是链接 = 可点元素：悬浮必须【背景 + 边缘】同时变，只改文字色不够 */
.link {
  color: var(--flame);
  border-radius: var(--r-xs);
  padding: 1px 4px;
  margin: 0 -4px;
  text-decoration: none;
  transition: background var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease),
              box-shadow var(--dur-fast) var(--ease);
}
.link:hover {
  color: var(--flame-bright);
  background: var(--surface-hover);
  box-shadow: inset 0 0 0 1px var(--edge-soft);
}
.link:active { background: var(--surface-active); }

.actions { display: flex; align-items: center; gap: var(--sp-2); margin-top: var(--sp-1); flex-wrap: wrap; }

/* 批量条：顶上一道发丝线 + --sp-4 留白。留白比线本身更重要 —— 只留线会"看不出两两分界" */
.batchbar {
  display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap;
  margin-top: var(--sp-4);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--hairline);
}

/* 勾选框：可交互元素，悬浮反馈靠 DataTable 的行背景 + 火焰选中色 */
.pick { width: 34px; }
.pick input { width: 15px; height: 15px; accent-color: var(--flame); cursor: pointer; }
.pick input:focus-visible { outline: 2px solid var(--flame); outline-offset: 2px; }

/* 当前正在核对的那一行：背景染色 + 左缘火焰线（和共享 Field 的 changed 同一套语言） */
.panel-wrap :deep(table.tbl tbody tr.current) { background: var(--flame-veil); }
.panel-wrap :deep(table.tbl tbody tr.current:hover) {
  background: var(--flame-veil);
  box-shadow: inset 0 0 0 1px var(--flame-glow);
}
.panel-wrap :deep(table.tbl tbody tr.current td:first-child) {
  box-shadow: inset 2px 0 0 var(--flame);
}

/* ==================== 新建表单 ==================== */
.nc { display: flex; flex-direction: column; gap: var(--sp-3); }
.nc-f { display: flex; flex-direction: column; gap: var(--sp-1); }
/* 新建词条里的说明行 */
.nc-hint { margin: 0; font-size: var(--fs-meta); line-height: 1.75; }
.nc-l { font-size: var(--fs-meta); color: var(--ink-dim); }
.nc-l b { color: var(--flame); font-weight: var(--fw-medium); }
.nc-ops { display: flex; justify-content: flex-end; gap: var(--sp-2); }

/* ==================== 抽屉 ==================== */
.hidden-file { display: none; }

.drawer-layer { position: fixed; inset: 0; z-index: var(--z-drawer); }
.drawer-mask {
  position: absolute; inset: 0;
  background: rgba(0, 0, 0, .5);
  backdrop-filter: blur(1px);
}
/*
  抽屉本体。
  ⚠️ 这里不能用 --surface-panel：它和页面底几乎同色，抽屉贴边时会"糊"在一起，
  看不出浮在上面。抬到 --surface-raised，再用 shadow-lift 说明它比页面高一层。
*/
.drawer {
  position: absolute; top: 0; right: 0; bottom: 0;
  width: min(560px, 100vw);
  display: flex; flex-direction: column;
  background: var(--surface-raised);
  border-left: 1px solid var(--edge);
  box-shadow: var(--shadow-lift);
}
.dr-head {
  display: flex; align-items: flex-start; gap: var(--sp-3);
  padding: var(--sp-4);
  border-bottom: 1px solid var(--hairline);
}
.dr-title { display: flex; flex-direction: column; gap: var(--sp-1); min-width: 0; }
.dr-en {
  font-family: var(--font-mono);
  font-size: var(--fs-section);
  color: var(--flame-bright);
  overflow-wrap: anywhere;
}
.dr-tags { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.dr-close {
  flex: none; margin-left: auto;
  background: none; border: 1px solid transparent; border-radius: var(--r-sm);
  padding: 2px 8px; font: inherit; font-size: var(--fs-sm); color: var(--ink-dim); cursor: pointer;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease);
}
.dr-close:hover { background: var(--surface-hover); border-color: var(--edge-hover); color: var(--ink); }

.dr-body { flex: 1; overflow-y: auto; padding: var(--sp-4); display: flex; flex-direction: column; gap: var(--sp-4); }
.dr-sec {
  background: var(--surface-panel);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-md);
  padding: var(--sp-4);
  display: flex; flex-direction: column; gap: var(--sp-3);
}
.dr-h {
  margin: 0;
  font-size: var(--fs-body);
  font-weight: var(--fw-semi);
  display: flex; align-items: baseline; gap: var(--sp-3);
}
.dr-cnt { font-size: var(--fs-meta); font-weight: var(--fw-normal); }
/* 整条下架放在「正文」标题行最右：它是这一节的操作，不该跟名字区的保存挤在一起 */
.dr-h .dr-retire { margin-left: auto; }
.dr-field { display: flex; flex-direction: column; gap: var(--sp-1); }
/* 标签：只读展示，用细边框药丸，和公开站详情页的观感一致 */
.taglist { display: flex; flex-wrap: wrap; gap: 6px; }
.tagchip {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-dim);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-pill);
  padding: 2px 10px;
  background: var(--surface-inset);
  overflow-wrap: anywhere;
}
.dr-lbl { font-size: var(--fs-meta); color: var(--ink-faint); letter-spacing: var(--tracking-label); }
.dr-row { display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap; }
.dr-val { font-size: var(--fs-body); }
.dr-status { width: 140px; }
.dr-note { font-size: var(--fs-meta); line-height: 1.7; margin: 0; }
.dr-note strong, .dr-note b { color: var(--ink); font-weight: var(--fw-medium); }
.dr-ops { display: flex; gap: var(--sp-2); flex-wrap: wrap; }
.dr-foot {
  display: flex; align-items: center; gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  border-top: 1px solid var(--hairline);
  flex-wrap: wrap;
}

.bpanel {
  background: var(--surface-inset);
  border-radius: var(--r-sm);
  padding: var(--sp-3);
  display: flex; flex-direction: column; gap: var(--sp-3);
}
.bhead { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; font-size: var(--fs-sm); }
.bcat { font-family: var(--font-mono); font-size: var(--fs-sm); color: var(--ink); }
.bselect { width: 200px; }
.warn-line { color: var(--amber); }

.chunk { display: flex; flex-direction: column; gap: var(--sp-2); }
.chunk + .chunk { border-top: 1px solid var(--hairline); padding-top: var(--sp-3); }
/* 已下架的块：整体压暗，但文本仍可读可改（改完再恢复也合理） */
.chunk.off { opacity: .62; }
.chead { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.chead .btn + .btn { margin-left: var(--sp-1); }
/* 块头右侧的操作区：改正文 / 下架。推到最右，和块 id 拉开距离，防止误点 */
.chead-ops { display: flex; align-items: center; gap: var(--sp-1); margin-left: auto; flex-wrap: wrap; }
.cnum { font-size: var(--fs-meta); color: var(--copper); }
/* 只读的块正文：保留换行，别让它挤成一坨 */
.chunk-text { margin: 0; font-size: var(--fs-meta); color: var(--ink-dim); line-height: 1.75; white-space: pre-wrap; }
.cchars { font-size: var(--fs-meta); }
.add-chunk { display: flex; flex-direction: column; gap: var(--sp-3); }

@media (max-width: 760px) {
  .w-zh, .zh-input, .bselect, .dr-status { width: 100%; }
}
</style>
