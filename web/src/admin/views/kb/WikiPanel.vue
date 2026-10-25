<script setup lang="ts">
/**
 * 「抓取」面板 —— 从 wiki 批量拉攻略、清洗、进知识库。
 *
 * <h2>一张表：全部来源，勾选即范围（2026-10-17 改）</h2>
 * 以前是"从菜单里一个个加入抓取范围 → 保存 → 再触发"，选几十个来源要点几十次。
 * 现在 wiki 上的全部分类就是那张表，**勾上就是要拉**，改动自动保存，
 * 「开始抓取」只拉勾上的那些。
 *
 * <p>每一行还显示**这个来源在知识库里生效了没有**（入库块数 / 最近同步时间）——
 * 否则"漏了哪个来源"根本看不出来：块按 {@code wiki·<来源名>} 分组存，
 * 后端数一遍就是这一列。
 *
 * <p>「开始抓取」在页面顶栏（KbView），滚动时永远看得见；导入在服务端后台跑，
 * 关掉页面也会继续。**跑完不需要重启服务**：写入会让语料版本 +1，索引、词表与
 * 检索缓存都按版本对账，下一次检索自己重载（见 KbBlockIndex / Glossary）。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import Panel from '@shared/ui/Panel.vue'
import Button from '@shared/ui/Button.vue'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Checkbox from '@shared/ui/Checkbox.vue'
import Notice from '@shared/ui/Notice.vue'
import Tag from '@shared/ui/Tag.vue'
import Bar from '@shared/ui/Bar.vue'
import Modal from '@shared/ui/Modal.vue'
import ActionOption from '@shared/ui/ActionOption.vue'
import Empty from '@shared/ui/Empty.vue'

/** 一个已保存/默认来源（后端 kb_wiki_source 的一行） */
interface SourceRow {
  name: string
  kind: string
  enabled: boolean
}

/** wiki 上的一个分类 */
interface CatalogRow {
  name: string
  size: number
  pages: number | null
  /** content 内容类 / maintenance 维护清单 / update 更新日期 / index 索引类 */
  klass: string
  advice: string
  samples: string[]
}

/** 某个来源在知识库里的生效情况 */
interface Coverage {
  blocks: number
  lastSync: string
}

interface SourcesResp {
  items: SourceRow[]
  defaults: SourceRow[]
  saved: boolean
  maxPagesPerSource: number
  renderEnabled: boolean
  wikiBase: string
  coverage: Record<string, Coverage>
}

interface CatalogResp {
  items: CatalogRow[]
  total: number
  cachedAt: string
}

interface ImportReport {
  sources: number
  pages: number
  duplicates: number
  changed: number
  imported: number
  chars: number
  rendered: number
  failed: number
  dryRun: boolean
  bySource: Record<string, number>
}

interface ImportStatus {
  state: string
  phase: string
  done: number
  total: number
  startedAt: string
  finishedAt: string
  report: ImportReport | null
  errors: string[]
}

/** 表格里的一行：wiki 分类、自定义前缀、或 wiki 上已经没有了的老来源 */
interface Row {
  key: string
  name: string
  kind: string
  checked: boolean
  url: string
  pages: number | null
  klass: string
  advice: string
  samples: string[]
  blocks: number
  lastSync: string
}

const error = ref('')
const okMsg = ref('')

// ==================== 来源表 ====================

const catalog = ref<CatalogRow[]>([])
const catalogTotal = ref(0)
const catalogAt = ref('')
const catalogLoading = ref(false)
const coverage = ref<Record<string, Coverage>>({})
/** 自定义前缀来源：勾没勾都要留在表里，否则取消勾选后就从页面上消失了 */
const prefixNames = ref<string[]>([])

const wikiBase = ref('')
const maxPagesPerSource = ref(0)
const renderEnabled = ref(true)

/** 勾选的来源 key（kind + 名字）。用数组而不是 Set，赋值即触发刷新 */
const checkedKeys = ref<string[]>([])
const checkedSet = computed(() => new Set(checkedKeys.value))
const savedSnapshot = ref('[]')
const saving = ref(false)
let saveTimer: number | undefined

const dirty = computed(() => JSON.stringify(checkedKeys.value) !== savedSnapshot.value)

const filter = ref('')
/** 默认只看内容类：wiki 上 298 个分类里大多数是维护清单，混在一起没法挑 */
const view = ref('content')
const VIEW_OPTIONS = [
  { value: 'content', label: '只看内容类' },
  { value: 'all', label: '全部分类' },
  { value: 'checked', label: '只看已勾选' },
  { value: 'missing', label: '只看没入库' },
]

function key(kind: string, name: string) {
  return kind + '\u0000' + name
}

function klassLabel(k: string) {
  if (k === 'prefix') return '页名前缀'
  if (k === 'stale') return 'wiki 上已没有'
  if (k === 'maintenance') return '维护清单'
  if (k === 'update') return '更新日期'
  if (k === 'index') return '索引类'
  return '内容类'
}
function klassTone(k: string): 'good' | 'bad' | 'warn' | 'neutral' {
  if (k === 'prefix') return 'neutral'
  if (k === 'stale') return 'warn'
  if (k === 'maintenance' || k === 'index') return 'bad'
  if (k === 'update') return 'warn'
  return 'good'
}

/**
 * 来源名 → wiki 上的地址。
 *   分类  → Category 页
 *   前缀  → Special:PrefixIndex 列出以它开头的全部页
 */
function wikiUrl(kind: string, name: string) {
  if (!wikiBase.value) return ''
  if (kind === 'prefix') {
    return wikiBase.value + '/index.php?title=Special:PrefixIndex&prefix=' + encodeURIComponent(name)
  }
  return wikiBase.value + '/wiki/Category:' + encodeURIComponent(name.replace(/ /g, '_'))
}

function coverOf(name: string): Coverage {
  return coverage.value[name] || { blocks: 0, lastSync: '' }
}

/** 全表：wiki 分类 + 自定义前缀 + 勾着但 wiki 上已经没有的老来源 */
const allRows = computed<Row[]>(() => {
  const rows: Row[] = []
  const seen = new Set<string>()
  const push = (r: Row) => {
    if (!seen.has(r.key)) {
      seen.add(r.key)
      rows.push(r)
    }
  }
  for (const c of catalog.value) {
    const k = key('category', c.name)
    const cov = coverOf(c.name)
    push({
      key: k, name: c.name, kind: 'category', checked: checkedSet.value.has(k),
      url: wikiUrl('category', c.name), pages: c.pages, klass: c.klass,
      advice: c.advice, samples: c.samples || [], blocks: cov.blocks, lastSync: cov.lastSync,
    })
  }
  for (const name of prefixNames.value) {
    const k = key('prefix', name)
    const cov = coverOf(name)
    push({
      key: k, name, kind: 'prefix', checked: checkedSet.value.has(k),
      url: wikiUrl('prefix', name), pages: null, klass: 'prefix',
      advice: '页名前缀：列出以此开头的全部页面', samples: [],
      blocks: cov.blocks, lastSync: cov.lastSync,
    })
  }
  // 勾着、但 wiki 上已经查不到了 —— 也得显示，不然取消不掉
  for (const k of checkedKeys.value) {
    const cut = k.indexOf('\u0000')
    const kind = k.slice(0, cut)
    const name = k.slice(cut + 1)
    if (kind !== 'category') continue
    const cov = coverOf(name)
    push({
      key: k, name, kind: 'category', checked: true, url: wikiUrl('category', name),
      pages: null, klass: 'stale', advice: 'wiki 的分类列表里已经没有它了', samples: [],
      blocks: cov.blocks, lastSync: cov.lastSync,
    })
  }
  return rows
})

const rowMap = computed(() => new Map(allRows.value.map(r => [r.key, r])))

const filteredRows = computed(() => {
  const kw = filter.value.trim().toLowerCase()
  return allRows.value.filter(r => {
    if (view.value === 'content' && !(r.klass === 'content' || r.klass === 'prefix' || r.klass === 'stale')) return false
    if (view.value === 'checked' && !r.checked) return false
    if (view.value === 'missing' && r.blocks > 0) return false
    if (!kw) return true
    // 英文分类名之外，也拿示例页名和中文建议一起搜 —— 不知道分类叫什么时用中文猜
    return r.name.toLowerCase().includes(kw)
      || r.advice.includes(kw)
      || r.samples.some(s => s.toLowerCase().includes(kw))
  })
})

const checkedRows = computed(() => checkedKeys.value.map(k => rowMap.value.get(k)).filter((r): r is Row => !!r))
const checkedCount = computed(() => checkedRows.value.length)

/** 本次范围大约要拉多少页（前缀来源预先不知道页数） */
const estimatedPages = computed(() => {
  let pages = 0
  let unknown = 0
  for (const r of checkedRows.value) {
    if (r.pages === null) unknown++
    else pages += r.pages
  }
  return { pages, unknown }
})

/** 会被每来源上限截断的勾选来源（上限为「不限」时永远不截） */
const truncatedCount = computed(() => {
  if (maxPagesPerSource.value <= 0) return 0
  return checkedRows.value.filter(r => r.pages !== null && r.pages > maxPagesPerSource.value).length
})

// ==================== 作业 ====================

const status = ref<ImportStatus>({
  state: 'idle', phase: '', done: 0, total: 0,
  startedAt: '', finishedAt: '', report: null, errors: [],
})
const runOpen = ref(false)
/** 回滚是破坏性操作：点一次变成"确认"，再点才真清 —— 不弹窗，不打断键盘流 */
const purgeArmed = ref(false)
const forceArmed = ref(false)

const running = computed(() => status.value.state === 'running')
const report = computed(() => status.value.report)
const progressMax = computed(() => Math.max(status.value.total, 1))

const stateLabel = computed(() => {
  const s = status.value.state
  if (s === 'running') return '进行中'
  if (s === 'done') return '已完成'
  if (s === 'failed') return '失败'
  return '空闲'
})
const stateTone = computed<'good' | 'bad' | 'warn' | 'neutral'>(() => {
  const s = status.value.state
  if (s === 'running') return 'warn'
  if (s === 'done') return 'good'
  if (s === 'failed') return 'bad'
  return 'neutral'
})
const jobHint = computed(() => {
  if (running.value) return '进度'
  if (report.value) return '上次结果'
  return '还没跑过'
})

let timer: number | undefined

// ==================== 读取 ====================

async function loadSources() {
  const r = await adminApi.get<SourcesResp>('/kb/wiki/sources')
  coverage.value = r.coverage || {}
  // 没保存过就回显 yml 默认值 —— 页面一打开就能看见"现在实际在拉什么"
  const stored = r.saved ? r.items : r.defaults
  prefixNames.value = stored.filter(s => s.kind === 'prefix').map(s => s.name)
  checkedKeys.value = stored.filter(s => s.enabled).map(s => key(s.kind, s.name))
  savedSnapshot.value = JSON.stringify(checkedKeys.value)
  maxPagesPerSource.value = r.maxPagesPerSource
  wikiBase.value = r.wikiBase || ''
  renderEnabled.value = r.renderEnabled
}

async function loadCatalog() {
  catalogLoading.value = true
  try {
    const r = await adminApi.get<CatalogResp>('/kb/wiki/catalog')
    catalog.value = r.items
    catalogTotal.value = r.total
    catalogAt.value = r.cachedAt ? r.cachedAt.slice(11, 16) : ''
  } finally {
    catalogLoading.value = false
  }
}

async function refreshStatus() {
  const wasRunning = running.value
  try {
    status.value = await adminApi.get<ImportStatus>('/kb/wiki/import/status')
  } catch (e) {
    error.value = describeError(e)
    return
  }
  if (!running.value && timer) {
    window.clearInterval(timer)
    timer = undefined
  }
  // 刚跑完就刷一次"知识库"列 —— 否则入库情况还停在跑之前的样子
  if (wasRunning && !running.value) {
    try {
      await loadSources()
    } catch {
      // 统计刷不出来不该覆盖掉刚拿到的报告
    }
  }
}

function poll() {
  if (timer) return
  timer = window.setInterval(refreshStatus, 1500)
}

async function reload() {
  error.value = ''
  try {
    await Promise.all([loadSources(), loadCatalog()])
    await refreshStatus()
    if (running.value) poll()
  } catch (e) {
    error.value = describeError(e)
  }
}

// ==================== 勾选 ====================

function isChecked(k: string) {
  return checkedSet.value.has(k)
}

function toggle(k: string, on: boolean) {
  if (on) {
    if (!checkedSet.value.has(k)) checkedKeys.value = [...checkedKeys.value, k]
  } else {
    checkedKeys.value = checkedKeys.value.filter(x => x !== k)
  }
  scheduleSave()
}

/** 勾选即改范围：没有"保存"按钮，改完自动存，开始抓取前再兜一次 */
function scheduleSave() {
  if (saveTimer) window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(() => {
    saveTimer = undefined
    void save()
  }, 600)
}

async function flushSave() {
  if (saveTimer) {
    window.clearTimeout(saveTimer)
    saveTimer = undefined
  }
  if (dirty.value) await save()
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    // 前缀要留着（勾没勾都留，否则取消勾选后它就从表里消失了）；
    // 分类只留勾上的 —— 没勾的分类 wiki 菜单里本来就有
    const items = allRows.value
      .filter(r => r.checked || r.kind === 'prefix')
      .map(r => ({ name: r.name, kind: r.kind, enabled: r.checked }))
    await adminApi.post('/kb/wiki/sources', { items })
    savedSnapshot.value = JSON.stringify(checkedKeys.value)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    saving.value = false
  }
}

/** 当前筛选结果是不是已经全勾上了 —— 决定那个按钮是"全选"还是"取消" */
const allFilteredChecked = computed(() =>
  filteredRows.value.length > 0 && filteredRows.value.every(r => r.checked)
)
/** 当前筛选里"有页数"的来源 */
const pagedRows = computed(() => filteredRows.value.filter(r => r.pages !== null && r.pages > 0))
/** 当前筛选里有没有"有页数"的来源 —— 决定「全选有页数的」能不能点 */
const hasPagedRows = computed(() => pagedRows.value.length > 0)
/**
 * 有页数的来源是不是已经全勾上。
 *
 * <p>「全选有页数的」天生不会勾那些页数为 0 的行，所以全选之后
 * {@link allFilteredChecked} 仍然是 false，主按钮不会变成「取消当前筛选」。
 * 这里单独判断它自己那一批，按钮到底还能不能做事就一眼看得出来。
 */
const allPagedChecked = computed(() =>
  hasPagedRows.value && pagedRows.value.every(r => r.checked)
)

/** 一个按钮两用：没全勾就全勾上，全勾了就都取消 */
function toggleFiltered() {
  const drop = new Set(filteredRows.value.map(r => r.key))
  if (allFilteredChecked.value) {
    checkedKeys.value = checkedKeys.value.filter(k => !drop.has(k))
  } else {
    const keys = new Set(checkedKeys.value)
    for (const r of filteredRows.value) keys.add(r.key)
    checkedKeys.value = [...keys]
  }
  scheduleSave()
}

/**
 * 把当前筛选里**有页数**的都勾上；已经全勾上时反过来取消它们。
 * 空分类勾了也一篇都拉不到，所以这批永远不碰页数为 0 的行 ——
 * 按钮也必须能成对地撤销，否则勾完就只能逐个点掉。
 */
function togglePaged() {
  if (allPagedChecked.value) {
    const drop = new Set(pagedRows.value.map(r => r.key))
    checkedKeys.value = checkedKeys.value.filter(k => !drop.has(k))
  } else {
    const keys = new Set(checkedKeys.value)
    for (const r of pagedRows.value) keys.add(r.key)
    checkedKeys.value = [...keys]
  }
  scheduleSave()
}

/**
 * 清空全部已选（不只是当前筛选）。
 *
 * <p>必须有这个出口：批量勾选有好几个入口，「全选有页数的」不会勾空分类，
 * 主按钮因此可能一直显示「全选当前筛选」—— 用户找不到取消全选的地方，
 * 只能逐个点掉几百行。这里给一个永远可用的撤销，不依赖当前筛选与勾选比例。
 */
function clearAll() {
  if (!checkedKeys.value.length) return
  checkedKeys.value = []
  scheduleSave()
}

// ==================== 触发 ====================

function openRun() {
  if (!checkedCount.value) {
    okMsg.value = ''
    error.value = '先在下面勾选要拉取的来源'
    return
  }
  error.value = ''
  runOpen.value = true
}
function closeRun() {
  runOpen.value = false
}
async function chooseRun(mode: string) {
  runOpen.value = false
  // 勾选可能还没落到库里（自动保存有防抖）—— 开跑前先兜一次
  await flushSave()
  await start(mode)
}

async function start(mode: string) {
  error.value = ''
  okMsg.value = ''
  try {
    await adminApi.post('/kb/wiki/import', { mode })
    okMsg.value = mode === 'dry-run' ? '空跑已开始（不写库）' : '已开始，可以关掉页面'
    await refreshStatus()
    poll()
  } catch (e) {
    error.value = describeError(e)
  }
}

async function doPurge() {
  purgeArmed.value = false
  await start('purge')
}

/**
 * 强制重洗不适合埋在「开始抓取」弹窗的第三个选项里 ——
 * 改完清洗规则的人会直接来这个面板找它，找不到就会以为没有这个功能。
 * 单独放一个按钮，并在执行前要求二次确认：它会重算全部 wiki 块的向量。
 */
function armForce() {
  if (!checkedCount.value) {
    okMsg.value = ''
    error.value = '先在下面勾选要重洗的来源'
    return
  }
  error.value = ''
  forceArmed.value = true
}
async function doForce() {
  forceArmed.value = false
  await flushSave()
  await start('force')
}

function fmt(n: number | undefined | null) {
  if (n === undefined || n === null) return '—'
  return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/** ISO 时间 → "10-17 02:00"，表格里够用 */
function shortTime(iso: string) {
  if (!iso || iso.length < 16) return iso
  return iso.slice(5, 16).replace('T', ' ')
}

onMounted(reload)
onUnmounted(() => {
  if (timer) window.clearInterval(timer)
  if (saveTimer) window.clearTimeout(saveTimer)
})

defineExpose({
  reload,
  isRunning: () => running.value,
  openRun,
  runState: () => ({ state: status.value.state, label: stateLabel.value, tone: stateTone.value }),
})
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-if="okMsg" tone="ok">{{ okMsg }}</Notice>

    <!-- ============ 作业面板：进度与结果共用同一块地方 ============ -->
    <Panel title="抓取作业" :hint="jobHint">
      <template #actions>
        <Tag :tone="stateTone">{{ stateLabel }}</Tag>
      </template>

      <template v-if="running">
        <Bar :label="status.phase || '准备中…'" :value="status.done" :max="progressMax" />
        <p class="job-note">导入在服务端后台跑，关掉这个页面也会继续。</p>
      </template>

      <template v-else-if="report">
        <div class="stats">
          <div class="stat"><span class="k">页面</span><span class="v num">{{ fmt(report.pages) }}</span></div>
          <div v-if="report.duplicates" class="stat"><span class="k">去重跳过</span><span class="v num">{{ fmt(report.duplicates) }}</span></div>
          <div class="stat"><span class="k">版本变过</span><span class="v num">{{ fmt(report.changed) }}</span></div>
          <div class="stat"><span class="k">写入块</span><span class="v num">{{ fmt(report.imported) }}</span></div>
          <div class="stat"><span class="k">正文字数</span><span class="v num">{{ fmt(report.chars) }}</span></div>
          <div class="stat"><span class="k">渲染兜底</span><span class="v num">{{ fmt(report.rendered) }}</span></div>
          <div class="stat"><span class="k">失败</span><span class="v num">{{ fmt(report.failed) }}</span></div>
        </div>

        <p v-if="report.dryRun" class="job-note">
          <Tag tone="neutral">空跑</Tag> 什么都没写。数量没问题就点「开始抓取 → 增量导入」；改了清洗规则用下面的「强制重洗全部」。
        </p>

        <table v-if="report.bySource && Object.keys(report.bySource).length" class="mini">
          <thead><tr><th>来源</th><th class="r">页数</th></tr></thead>
          <tbody>
            <tr v-for="(n, k) in report.bySource" :key="k">
              <td class="mono">{{ k }}</td><td class="r num">{{ fmt(n) }}</td>
            </tr>
          </tbody>
        </table>

        <ul v-if="status.errors && status.errors.length" class="errs">
          <li v-for="(e, i) in status.errors" :key="i">{{ e }}</li>
        </ul>
      </template>

      <template v-else>
        <p class="job-idle">
          还没跑过。点右上角<b>开始抓取</b>：先「空跑」确认会拉到多少页，再「增量导入」写进库。
          改了清洗规则、或要让老页补抓配图，用下面的<b>强制重洗全部</b>。
        </p>
      </template>

      <div class="job-actions">
        <template v-if="!forceArmed">
          <Button size="sm" :disabled="running" @click="armForce">强制重洗全部</Button>
          <span class="faint">全部页重洗、重算向量，并为有主图的页面补抓配图；改了清洗规则、或要让老页补图，才用这个</span>
        </template>
        <template v-else>
          <span class="warn-text">确认重洗全部？会重新清洗、重算全部 wiki 块的向量，并为有主图的页面下载配图。可能持续数小时，且按量计费。</span>
          <Button variant="danger" size="sm" :disabled="running" @click="doForce">确认重洗</Button>
          <Button size="sm" @click="forceArmed = false">取消</Button>
        </template>
      </div>

      <div class="purge">
        <template v-if="!purgeArmed">
          <Button size="sm" :disabled="running" @click="purgeArmed = true">回滚：清空 wiki 块</Button>
          <span class="faint">删掉全部 wiki 文章块与导入状态，之后可以重新导入</span>
        </template>
        <template v-else>
          <span class="warn-text">确认清空？会删掉全部 wiki 文章块与导入状态，不可撤销。</span>
          <Button variant="danger" size="sm" :disabled="running" @click="doPurge">确认清空</Button>
          <Button size="sm" @click="purgeArmed = false">取消</Button>
        </template>
      </div>
    </Panel>

    <!-- ============ 来源表：勾选即范围 ============ -->
    <Panel class="src-panel" title="wiki 来源" :count="checkedCount" hint="勾选即范围，改动自动保存；开始抓取只拉勾上的">
      <template #actions>
        <div class="bulk-actions">
          <Button
            size="sm"
            :disabled="!filteredRows.length"
            :title="allFilteredChecked ? '取消当前筛选里已勾选的来源' : '勾选当前筛选里的全部来源'"
            @click="toggleFiltered"
          >
            {{ allFilteredChecked ? '取消当前筛选' : '全选当前筛选' }}
          </Button>
          <Button
            size="sm"
            :disabled="!hasPagedRows"
            :title="allPagedChecked
              ? '取消当前筛选里页数大于 0 的来源'
              : '只勾选当前筛选里页数大于 0 的来源（空分类勾了也拉不到内容）'"
            @click="togglePaged"
          >
            {{ allPagedChecked ? '取消有页数的' : '全选有页数的' }}
          </Button>
          <!-- 全局撤销：不依赖当前筛选，也不依赖「是不是所有行都勾上了」 -->
          <Button
            v-if="checkedCount"
            size="sm"
            title="取消全部已勾选的来源"
            @click="clearAll"
          >
            清空已选
          </Button>
        </div>
      </template>

      <div class="filters">
        <Input v-model="filter" mono placeholder="搜分类名、示例页名，或中文说明…" aria-label="搜索来源" />
        <div class="filter-sel">
          <Select v-model="view" :options="VIEW_OPTIONS" aria-label="筛选来源" />
        </div>
      </div>

      <div v-if="catalogLoading" class="cat-loading faint">正在读取 wiki 分类…</div>
      <Empty
        v-else-if="!filteredRows.length"
        text="没有匹配的来源"
        hint="换个关键词，或把筛选切到「全部分类」"
      />
      <div v-else class="src-scroll">
        <table class="src-tbl">
          <thead>
            <tr>
              <th class="c">选</th>
              <th>来源（wiki 上的原名）</th>
              <th>说明</th>
              <th class="r">页数</th>
              <th>知识库</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in filteredRows" :key="r.key" :class="{ on: r.checked }">
              <td class="c">
                <Checkbox
                  :model-value="isChecked(r.key)"
                  :aria-label="'选择 ' + r.name"
                  @update:model-value="(v: boolean) => toggle(r.key, v)"
                />
              </td>
              <td>
                <div class="mono">
                  <a v-if="r.url" :href="r.url" target="_blank" rel="noopener">{{ r.name }} ↗</a>
                  <span v-else>{{ r.name }}</span>
                </div>
                <div v-if="r.samples.length" class="samples faint">例：{{ r.samples.join('、') }}</div>
              </td>
              <td>
                <Tag :tone="klassTone(r.klass)">{{ klassLabel(r.klass) }}</Tag>
                <div class="advice faint">{{ r.advice }}</div>
              </td>
              <td class="r num">{{ r.pages === null ? '—' : fmt(r.pages) }}</td>
              <td>
                <Tag v-if="r.blocks > 0" tone="good">已入库 {{ fmt(r.blocks) }} 块</Tag>
                <Tag v-else-if="r.lastSync" tone="warn">拉过 · 无正文</Tag>
                <span v-else class="faint">未入库</span>
                <div v-if="r.lastSync" class="sync faint">{{ shortTime(r.lastSync) }}</div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="src-foot">
        <span class="faint">
          已勾选 <b class="num">{{ checkedCount }}</b> 个来源 · 本次约 <b class="num">{{ fmt(estimatedPages.pages) }}</b> 页<template v-if="estimatedPages.unknown">（{{ estimatedPages.unknown }} 个前缀来源页数未知）</template>
        </span>
        <Tag v-if="truncatedCount" tone="warn">有 {{ truncatedCount }} 个来源会被上限截断</Tag>
        <span class="spacer" />
        <span class="faint">
          {{ saving ? '保存中…' : dirty ? '未保存' : '已保存' }} · 每来源上限 {{ maxPagesPerSource > 0 ? fmt(maxPagesPerSource) + ' 页' : '不限' }} · 渲染兜底 {{ renderEnabled ? '开' : '关' }}
        </span>
      </div>

      <p class="faint cat-note">
        共 {{ catalogTotal }} 个分类<template v-if="catalogAt">（缓存于 {{ catalogAt }}）</template>。
        「页数」是分类里的文章页数，已排除子分类与文件；「知识库」按来源数生效的块 ——
        <b>未入库</b>说明这个来源还没拉过，「拉过 · 无正文」说明拉过但清洗后没有可入库的正文。
      </p>
    </Panel>

    <!-- 选一种抓取方式 -->
    <Modal :open="runOpen" title="开始抓取" width="540px" @close="closeRun">
      <p class="run-lede">
        跑在服务端后台，关掉页面也会继续。本次要拉 <b class="num">{{ checkedCount }}</b> 个来源、约
        <b class="num">{{ fmt(estimatedPages.pages) }}</b> 页<template v-if="estimatedPages.unknown">（另有 {{ estimatedPages.unknown }} 个前缀来源页数未知）</template>。选一种方式：
      </p>
      <div class="run-opts">
        <ActionOption
          title="空跑"
          hint="只统计会拉到多少页、清洗出多少字。不写库、不算向量、不花钱。"
          @select="chooseRun('dry-run')"
        />
        <ActionOption
          title="增量导入"
          hint="只处理 wiki 上版本变过的页。日常更新用这个。"
          @select="chooseRun('incremental')"
        />
        <ActionOption
          title="强制重洗全部"
          hint="全部页重洗并重算向量，同时补抓配图。改了清洗规则、或要让老页补图，才用这个。"
          @select="chooseRun('force')"
        />
      </div>
    </Modal>
  </div>
</template>

<style scoped>
/* 用 flex 而不是 grid：grid 的隐式列默认 min-content，宽表会把整页顶出横向滚动条
   （390px 下实测文档宽 624）。flex 列的方向上没有这个问题，宽度交给面板。 */
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

/* ==================== 作业面板 ==================== */
.job-idle { margin: 0; font-size: var(--fs-base); color: var(--ink-2); line-height: 1.8; }
.job-note { margin: var(--sp-3) 0 0; font-size: var(--fs-meta); color: var(--ink-3); line-height: 1.8; }

.stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(112px, 1fr));
  gap: var(--sp-2);
}
.stat {
  background: var(--surface-inset);
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  padding: var(--sp-2) var(--sp-3);
}
.stat .k { display: block; color: var(--ink-3); font-size: var(--fs-meta); }
.stat .v { display: block; color: var(--ink); font-size: var(--fs-lg); margin-top: 2px; }

.mini { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); margin-top: var(--sp-3); }
.mini th, .mini td { text-align: left; padding: 5px var(--sp-2); border-bottom: 1px solid var(--hairline); }
.mini th { color: var(--ink-3); font-weight: var(--fw-medium); font-size: var(--fs-meta); }
.mini td.r, .mini th.r { text-align: right; }
.mini tbody tr:last-child td { border-bottom: 0; }

.errs { margin: var(--sp-3) 0 0; padding-left: 18px; color: var(--ink-dim); font-size: var(--fs-meta); line-height: 1.7; }

.job-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-3);
  padding-top: var(--sp-3);
  border-top: 1px solid var(--hairline);
  font-size: var(--fs-meta);
}
.purge {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-top: var(--sp-4);
  padding-top: var(--sp-3);
  border-top: 1px solid var(--hairline);
}
.warn-text { color: var(--flame-bright); font-size: var(--fs-meta); }

/* ==================== 来源表 ==================== */
/* 三个批量按钮排成一组：窄屏时整组换到标题下一行，不把标题挤没 */
.bulk-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--sp-2);
}
.filters { display: flex; gap: var(--sp-2); align-items: center; margin-bottom: var(--sp-3); }
.filter-sel { flex: 0 0 160px; }
.cat-loading { padding: var(--sp-4) 0; font-size: var(--fs-meta); text-align: center; }

/* 宽表在窄屏必须自己横滑：只设 overflow-y 时表格的 min-content 会撑破面板。 */
.src-scroll { max-height: 560px; overflow: auto; overscroll-behavior: contain; }
.src-tbl { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
.src-tbl th {
  position: sticky;
  top: 0;
  z-index: 1;
  text-align: left;
  padding: var(--sp-2) var(--sp-3);
  background: var(--stone-400);
  border-bottom: 1px solid var(--edge);
  color: var(--ink-3);
  font-weight: var(--fw-medium);
  font-size: var(--fs-xs);
  letter-spacing: var(--tracking-label);
  white-space: nowrap;
}
.src-tbl td { padding: var(--sp-2) var(--sp-3); border-bottom: 1px solid var(--hairline); vertical-align: top; }
.src-tbl tbody tr:hover { background: var(--surface-hover); }
.src-tbl tbody tr:last-child td { border-bottom: 0; }
/* 勾上的整行给一点底色 —— 一眼扫出这次要拉哪些 */
.src-tbl tbody tr.on { background: var(--surface-raised); }
.src-tbl tbody tr.on:hover { background: var(--surface-active); }
.src-tbl td.c, .src-tbl th.c { text-align: center; width: 44px; }
.src-tbl td.r, .src-tbl th.r { text-align: right; white-space: nowrap; }
.src-tbl a { color: var(--ink); text-decoration: none; border-bottom: 1px solid var(--edge); }
.src-tbl a:hover { color: var(--flame-bright); border-bottom-color: var(--flame-bright); }
.samples { font-size: var(--fs-xs); margin-top: 2px; }
.advice { font-size: var(--fs-xs); margin-top: 3px; line-height: 1.6; max-width: 34em; }
.sync { font-size: var(--fs-xs); margin-top: 2px; font-variant-numeric: tabular-nums; }

.src-foot {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-top: var(--sp-3);
  padding-top: var(--sp-3);
  border-top: 1px solid var(--hairline);
  font-size: var(--fs-meta);
}
.src-foot .spacer { flex: 1; }
.cat-note { font-size: var(--fs-meta); margin: var(--sp-3) 0 0; line-height: 1.7; }

.mono { font-family: var(--font-mono); }
.num { font-variant-numeric: tabular-nums; }
.faint { color: var(--ink-3); }

/* ==================== 选方式 ==================== */
.run-lede { margin: 0 0 var(--sp-3); font-size: var(--fs-body); color: var(--ink-2); line-height: 1.8; }
.run-opts { display: flex; flex-direction: column; gap: var(--sp-2); }

@media (max-width: 760px) {
  .filters { flex-wrap: wrap; }
  .filter-sel { flex: 1 1 100%; }
  /* 标题行允许换行，批量按钮整组落到下一行（右对齐），不撑破面板 */
  .src-panel :deep(.panel-title) { flex-wrap: wrap; row-gap: var(--sp-2); }
  .bulk-actions { flex: 1 1 100%; }
}
</style>