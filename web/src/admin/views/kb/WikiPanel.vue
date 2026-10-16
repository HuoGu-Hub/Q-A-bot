<script setup lang="ts">
/**
 * 「抓取」面板 —— 从 wiki 批量拉攻略、清洗、进知识库。
 *
 * <h2>这一页只干一件事：选范围 → 跑一次 → 看结果</h2>
 * 所以结构就是这三段，顺序即页面顺序：
 *   <ol>
 *     <li>作业面板（一整块）—— 跑的时候是进度条，跑完**原地**变成结果读数。
 *         不跳面板、不弹窗：同一个位置从"进行中"变成"已完成"。</li>
 *     <li>左「抓取范围」—— 这次要拉哪些来源，可开关、可移除、可加页名前缀，改完保存。</li>
 *     <li>右「挑分类」—— wiki 上有什么分类、各多少页、装了些什么；点「选它」进左边范围。</li>
 *   </ol>
 *
 * <p>「开始抓取」放在页面顶栏（KbView），滚动时永远看得见 —— 找不到按钮是这个页面
 * 以前最大的问题。点它先选方式（空跑 / 增量 / 强制重洗），再开跑。
 *
 * <p>导入在服务端后台线程跑，关掉页面也会继续；跑完**要重启服务**索引才重载。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import Panel from '@shared/ui/Panel.vue'
import Button from '@shared/ui/Button.vue'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Switch from '@shared/ui/Switch.vue'
import Notice from '@shared/ui/Notice.vue'
import Tag from '@shared/ui/Tag.vue'
import Bar from '@shared/ui/Bar.vue'
import Modal from '@shared/ui/Modal.vue'
import ActionOption from '@shared/ui/ActionOption.vue'
import Empty from '@shared/ui/Empty.vue'

interface SourceRow {
  name: string
  kind: string
  enabled: boolean
}

interface CatalogRow {
  name: string
  size: number
  pages: number | null
  /** 后端判定：content 内容类 / maintenance 维护清单 / update 更新日期 / index 索引类 */
  klass: string
  /** 中文建议，直接显示给运维看 */
  advice: string
  /** 前三个页名，让英文分类名一眼能看懂装的是什么 */
  samples: string[]
}

interface SourcesResp {
  items: SourceRow[]
  defaults: SourceRow[]
  saved: boolean
  maxPagesPerSource: number
  renderEnabled: boolean
  /** wiki 站点根，如 https://enshrouded.wiki.gg —— 拼"点开看"的链接 */
  wikiBase: string
}

interface CatalogResp {
  items: CatalogRow[]
  total: number
  cachedAt: string
}

interface ImportReport {
  sources: number
  pages: number
  /** 跨来源重复、被跳过的页数（同一个页面挂在多个来源下） */
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

const error = ref('')
const okMsg = ref('')
/** 保存范围是整表替换，双击会重复提交 —— 用一个标记把按钮按住 */
const saving = ref(false)

// ==================== 范围 ====================

const items = ref<SourceRow[]>([])
/** 已保存范围的快照 —— 用来判"有没有改动"，JSON 足够，条目就几条 */
const savedSnapshot = ref('[]')
const saved = ref(false)
const maxPagesPerSource = ref(0)
const wikiBase = ref('')
const renderEnabled = ref(true)
const prefixInput = ref('')

const dirty = computed(() => JSON.stringify(items.value) !== savedSnapshot.value)
const enabledCount = computed(() => items.value.filter(s => s.enabled).length)

// ==================== 分类菜单 ====================

const catalog = ref<CatalogRow[]>([])
const catalogTotal = ref(0)
const catalogAt = ref('')
const catalogLoading = ref(false)
const filter = ref('')
/** 默认只看内容类：wiki 上 298 个分类里大多数是维护清单，不该混在可选列表里 */
const klassFilter = ref('content')
const KLASS_OPTIONS = [
  { value: 'content', label: '只看内容类' },
  { value: 'all', label: '全部分类' },
]

// ==================== 作业 ====================

const status = ref<ImportStatus>({
  state: 'idle', phase: '', done: 0, total: 0,
  startedAt: '', finishedAt: '', report: null, errors: [],
})
const runOpen = ref(false)
/** 回滚是破坏性操作：点一次变成"确认"，再点才真清 —— 不用弹窗，避免打断键盘流 */
const purgeArmed = ref(false)

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

/** 搜索过滤后的分类菜单 */
const filteredCatalog = computed(() => {
  const kw = filter.value.trim().toLowerCase()
  return catalog.value.filter(c => {
    if (klassFilter.value !== 'all' && c.klass !== klassFilter.value) return false
    if (!kw) return true
    // 英文分类名之外，也拿示例页名和中文建议一起搜 —— 不知道分类叫什么时用中文猜
    return c.name.toLowerCase().includes(kw)
      || c.advice.includes(kw)
      || (c.samples || []).some(s => s.toLowerCase().includes(kw))
  })
})

function klassLabel(k: string) {
  if (k === 'maintenance') return '维护清单'
  if (k === 'update') return '更新日期'
  if (k === 'index') return '索引类'
  return '内容类'
}
function klassTone(k: string): 'good' | 'bad' | 'warn' | 'neutral' {
  if (k === 'maintenance' || k === 'index') return 'bad'
  if (k === 'update') return 'warn'
  return 'good'
}

/**
 * 这些英文名不是 URL，是 wiki 上的名字：
 *   分类  → wiki 的 Category 页，如 /wiki/Category:Gameplay
 *   前缀  → Special:PrefixIndex 列出以它开头的全部页，如 /wiki/Special:PrefixIndex?prefix=Quests/
 * 给链接就是为了让人能点开看一眼，不用凭英文名猜。
 */
function wikiUrl(kind: string, name: string) {
  if (!wikiBase.value) return ''
  if (kind === 'prefix') {
    return wikiBase.value + '/index.php?title=Special:PrefixIndex&prefix=' + encodeURIComponent(name)
  }
  return wikiBase.value + '/wiki/Category:' + encodeURIComponent(name.replace(/ /g, '_'))
}

function inScope(name: string) {
  return items.value.some(s => s.name === name)
}
function pagesOf(c: CatalogRow) {
  if (c.pages !== null && c.pages !== undefined) return String(c.pages)
  // 取不到精确页数时退回成员数（含子分类与文件），用 ≈ 标明这是近似值
  return '≈' + c.size
}

async function loadSources() {
  const r = await adminApi.get<SourcesResp>('/kb/wiki/sources')
  // 没保存过就回显 yml 默认值 —— 页面一打开就能看见"现在实际在拉什么"
  items.value = (r.saved ? r.items : r.defaults).map(s => ({ name: s.name, kind: s.kind, enabled: s.enabled }))
  savedSnapshot.value = JSON.stringify(items.value)
  saved.value = r.saved
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
}

function poll() {
  if (timer) return
  timer = window.setInterval(refreshStatus, 1500)
}

// ==================== 范围：增删 ====================

/** 菜单里直接选它 —— 这是唯一的"加入范围"入口，不再另开一个下拉 */
function pick(c: CatalogRow) {
  if (!inScope(c.name)) {
    items.value.push({ name: c.name, kind: 'category', enabled: true })
  }
}

function addPrefix() {
  const v = prefixInput.value.trim()
  if (!v) return
  if (!items.value.some(s => s.name === v)) {
    items.value.push({ name: v, kind: 'prefix', enabled: true })
  }
  prefixInput.value = ''
}

function removeAt(i: number) {
  items.value.splice(i, 1)
}

function discard() {
  items.value = JSON.parse(savedSnapshot.value)
}

async function save() {
  saving.value = true
  error.value = ''
  okMsg.value = ''
  try {
    const r = await adminApi.post<{ count: number; enabled: number }>('/kb/wiki/sources', { items: items.value })
    okMsg.value = '范围已保存：' + r.count + ' 条来源，启用 ' + r.enabled + ' 条'
    await loadSources()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    saving.value = false
  }
}

// ==================== 作业：开跑 ====================

function openRun() {
  runOpen.value = true
}
function closeRun() {
  runOpen.value = false
}
async function chooseRun(mode: string) {
  runOpen.value = false
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

function fmt(n: number | undefined) {
  if (n === undefined || n === null) return '—'
  return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
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

onMounted(reload)
onUnmounted(() => { if (timer) window.clearInterval(timer) })

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

      <!-- 跑的时候：进度 -->
      <template v-if="running">
        <Bar :label="status.phase || '准备中…'" :value="status.done" :max="progressMax" />
        <p class="job-note">导入在服务端后台跑，关掉这个页面也会继续。</p>
      </template>

      <!-- 跑完：原地变成结果 -->
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

        <p class="job-note">
          <template v-if="report.dryRun">
            <Tag tone="neutral">空跑</Tag> 什么都没写。数量没问题就点「开始抓取 → 增量导入」。
          </template>
          <template v-else>
            <Tag tone="warn">跑完要重启服务</Tag> 索引才重载，新资料才会被检索到。
          </template>
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

      <!-- 还没跑过 -->
      <template v-else>
        <p class="job-idle">
          还没跑过。点右上角<b>开始抓取</b>：先「空跑」确认会拉到多少页，再「增量导入」写进库。
        </p>
      </template>

      <!-- 回滚：破坏性、很少用，收在面板底部 -->
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

    <!-- ============ 双栏：左范围、右分类 ============ -->
    <div class="picker">
      <Panel title="抓取范围" :count="items.length" :hint="saved ? '页面保存的范围生效中' : '还没保存过，用的是配置文件里的默认范围'">
        <div class="meta-row">
          <Tag :tone="saved ? 'good' : 'warn'">{{ saved ? '已保存' : '默认值' }}</Tag>
          <Tag tone="neutral">每来源上限 {{ maxPagesPerSource }} 页</Tag>
          <Tag :tone="renderEnabled ? 'good' : 'neutral'">渲染兜底 {{ renderEnabled ? '开' : '关' }}</Tag>
          <Tag v-if="dirty" tone="flame">有未保存改动</Tag>
        </div>

        <ul v-if="items.length" class="scope-list">
          <li v-for="(s, i) in items" :key="s.kind + ':' + s.name" class="scope-item">
            <Switch v-model="s.enabled" :aria-label="'启用 ' + s.name" />
            <a v-if="wikiUrl(s.kind, s.name)" class="scope-name" :href="wikiUrl(s.kind, s.name)" target="_blank" rel="noopener">{{ s.name }}</a>
            <span v-else class="scope-name">{{ s.name }}</span>
            <Tag v-if="s.kind === 'prefix'" tone="neutral">前缀</Tag>
            <span class="spacer" />
            <Button size="sm" @click="removeAt(i)">移除</Button>
          </li>
        </ul>
        <Empty v-else text="范围是空的" hint="从右边「选分类」点一个「选它」，或加一个页名前缀" />

        <div class="add-row">
          <Input v-model="prefixInput" mono placeholder="页名前缀，如 Quests/" aria-label="页名前缀" />
          <Button size="sm" :disabled="!prefixInput.trim()" @click="addPrefix">加前缀</Button>
        </div>

        <div class="scope-actions">
          <Button variant="primary" size="sm" :disabled="!dirty || saving" @click="save">
            {{ saving ? '保存中…' : '保存范围' }}
          </Button>
          <Button size="sm" :disabled="!dirty || saving" @click="discard">放弃改动</Button>
          <span class="faint">启用 {{ enabledCount }} / {{ items.length }}</span>
        </div>
      </Panel>

      <Panel title="选分类" :count="filteredCatalog.length" hint="点「选它」加入左边范围">
        <div class="filters">
          <Input v-model="filter" mono placeholder="搜分类名、示例页名，或中文说明…" aria-label="搜索分类" />
          <div class="filter-sel">
            <Select v-model="klassFilter" :options="KLASS_OPTIONS" aria-label="按类别筛选" />
          </div>
        </div>

        <div v-if="catalogLoading" class="cat-loading faint">正在读取 wiki 分类…</div>
        <div v-else-if="!filteredCatalog.length" class="cat-loading">
          <Empty text="没有匹配的分类" hint="换个关键词，或把筛选切到「全部分类」" />
        </div>
        <div v-else class="cat-scroll">
          <table class="cat-tbl">
            <thead>
              <tr>
                <th>分类（wiki 上的原名）</th>
                <th>说明</th>
                <th class="r">正文页数</th>
                <th class="c">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in filteredCatalog" :key="c.name">
                <td>
                  <div class="mono">
                    <a v-if="wikiUrl('category', c.name)" :href="wikiUrl('category', c.name)" target="_blank" rel="noopener">{{ c.name }} ↗</a>
                    <span v-else>{{ c.name }}</span>
                  </div>
                  <div v-if="c.samples && c.samples.length" class="samples faint">例：{{ c.samples.join('、') }}</div>
                </td>
                <td>
                  <Tag :tone="klassTone(c.klass)">{{ klassLabel(c.klass) }}</Tag>
                  <div class="advice faint">{{ c.advice }}</div>
                </td>
                <td class="r num">{{ pagesOf(c) }}</td>
                <td class="c">
                  <Tag v-if="inScope(c.name)" tone="good">已选</Tag>
                  <Button v-else-if="c.klass === 'content'" size="sm" @click="pick(c)">选它</Button>
                  <span v-else class="faint">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <p class="faint cat-note">
          共 {{ catalogTotal }} 个分类<template v-if="catalogAt">（缓存于 {{ catalogAt }}）</template>。
          「正文页数」是分类里的文章页数，已排除子分类与文件。一个页面可能同时属于多个分类，
          导入时同一页只处理一次 —— 实际会拉多少以「空跑」为准。
          维护清单与索引类没有「选它」：选了会把几千页无关内容拉进语料。
        </p>
      </Panel>
    </div>

    <!-- 选一种抓取方式 -->
    <Modal :open="runOpen" title="开始抓取" width="540px" @close="closeRun">
      <p class="run-lede">跑在服务端后台，关掉页面也会继续。选一种方式：</p>
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
          hint="全部页重洗并重算向量。改了清洗规则、或怀疑老页是旧规则洗的，才用这个。"
          @select="chooseRun('force')"
        />
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.panel-wrap { display: grid; gap: var(--sp-4); }

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

/* ==================== 双栏 ==================== */
.picker {
  display: grid;
  grid-template-columns: minmax(0, 400px) minmax(0, 1fr);
  gap: var(--sp-4);
  align-items: start;
}

/* ---- 左：范围 ---- */
.meta-row { display: flex; gap: var(--sp-2); align-items: center; flex-wrap: wrap; margin-bottom: var(--sp-3); }
.scope-list { list-style: none; margin: 0; padding: 0; }
.scope-item {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-2) 0;
  border-bottom: 1px solid var(--hairline);
}
.scope-item:last-child { border-bottom: 0; }
.scope-name {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  color: var(--ink);
  text-decoration: none;
  overflow-wrap: anywhere;
}
a.scope-name { border-bottom: 1px solid var(--edge); }
a.scope-name:hover { color: var(--flame-bright); border-bottom-color: var(--flame-bright); }
.scope-item .spacer { flex: 1; }

.add-row { display: flex; gap: var(--sp-2); align-items: center; margin-top: var(--sp-3); }
.add-row :deep(.inp) { font-size: var(--fs-sm); }

.scope-actions {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
  flex-wrap: wrap;
  margin-top: var(--sp-3);
  padding-top: var(--sp-3);
  border-top: 1px solid var(--hairline);
}

/* ---- 右：分类菜单 ---- */
.filters { display: flex; gap: var(--sp-2); align-items: center; margin-bottom: var(--sp-3); }
.filter-sel { flex: 0 0 160px; }
.cat-loading { padding: var(--sp-4) 0; font-size: var(--fs-meta); text-align: center; }
.cat-scroll { max-height: 460px; overflow-y: auto; overscroll-behavior-y: contain; }
.cat-tbl { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
.cat-tbl th {
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
.cat-tbl td { padding: var(--sp-2) var(--sp-3); border-bottom: 1px solid var(--hairline); vertical-align: top; }
.cat-tbl tbody tr:hover { background: var(--surface-hover); }
.cat-tbl tbody tr:last-child td { border-bottom: 0; }
.cat-tbl td.c, .cat-tbl th.c { text-align: center; }
.cat-tbl td.r, .cat-tbl th.r { text-align: right; }
.cat-tbl a { color: var(--ink); text-decoration: none; border-bottom: 1px solid var(--edge); }
.cat-tbl a:hover { color: var(--flame-bright); border-bottom-color: var(--flame-bright); }
.samples { font-size: var(--fs-xs); margin-top: 2px; }
.advice { font-size: var(--fs-xs); margin-top: 3px; line-height: 1.6; max-width: 34em; }
.cat-note { font-size: var(--fs-meta); margin: var(--sp-3) 0 0; line-height: 1.7; }

.mono { font-family: var(--font-mono); }
.num { font-variant-numeric: tabular-nums; }
.faint { color: var(--ink-3); }

/* ==================== 选方式 ==================== */
.run-lede { margin: 0 0 var(--sp-3); font-size: var(--fs-body); color: var(--ink-2); }
.run-opts { display: flex; flex-direction: column; gap: var(--sp-2); }

@media (max-width: 1100px) {
  .picker { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 760px) {
  .filters { flex-wrap: wrap; }
  .filter-sel { flex: 1 1 100%; }
}
</style>
