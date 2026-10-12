<script setup lang="ts">
/**
 * 「抓取」面板 —— 从 wiki 批量拉取攻略并清洗。
 *
 * 这个面板替代了原来的命令行脚本：范围在这里勾选、保存，触发在这里点。
 * 三件事：
 *   ① 范围 —— 从 wiki 的分类菜单里勾（带正文页数），或手动加页名前缀；
 *      保存后写进 kb_wiki_source 表，导入器**优先读表**（表空才回落到 yml 默认值）。
 *   ② 触发 —— 空跑 / 增量 / 强制重洗。导入在服务端后台线程跑，关掉页面也会继续。
 *   ③ 看 —— 轮询进度，跑完显示报告（页面 / 变过 / 写入块 / 字数 / 渲染兜底 / 失败）。
 *
 * ⚠️ 导入跑完必须重启服务，索引才重载 —— 页面里也写着这一句。
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
}

interface SourcesResp {
  items: SourceRow[]
  defaults: SourceRow[]
  saved: boolean
  maxPagesPerSource: number
  renderEnabled: boolean
}

interface CatalogResp {
  items: CatalogRow[]
  total: number
  cachedAt: string
}

interface ImportReport {
  sources: number
  pages: number
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
const busy = ref(false)

const items = ref<SourceRow[]>([])
const saved = ref(false)
const maxPagesPerSource = ref(0)
const renderEnabled = ref(true)

const catalog = ref<CatalogRow[]>([])
const catalogTotal = ref(0)
const catalogAt = ref('')

const filter = ref('')
const pickCategory = ref('')
const prefixInput = ref('')

const status = ref<ImportStatus>({ state: 'idle', phase: '', done: 0, total: 0, startedAt: '', finishedAt: '', report: null, errors: [] })
/** 回滚是破坏性操作：点一次变成"确认"，再点才真清 —— 不用弹窗，避免打断键盘流 */
const purgeArmed = ref(false)
const running = computed(function () { return status.value.state === 'running' })
const report = computed(function () { return status.value.report })
const progressMax = computed(function () { return Math.max(status.value.total, 1) })

let timer: number | undefined

/** 菜单里的分类，去掉已经在范围里的那些 */
const categoryOptions = computed(function () {
  const chosen = new Set(items.value.map(function (s) { return s.name }))
  return catalog.value
    .filter(function (c) { return !chosen.has(c.name) })
    .map(function (c) {
      const pages = c.pages === null ? '?' : String(c.pages)
      return { value: c.name, label: c.name + '（' + pages + ' 页）' }
    })
})

/** 搜索过滤后的分类菜单 */
const filteredCatalog = computed(function () {
  const kw = filter.value.trim().toLowerCase()
  if (!kw) return catalog.value
  return catalog.value.filter(function (c) { return c.name.toLowerCase().indexOf(kw) >= 0 })
})

const scopeHint = computed(function () {
  return saved.value ? '页面保存的范围生效中' : '还没保存过，当前用 application.yml 里的默认来源'
})

function nameOf(c: CatalogRow) { return c.name }
function pagesOf(c: CatalogRow) { return c.pages === null ? '—' : String(c.pages) }
function sizeOf(c: CatalogRow) { return String(c.size) }
function inScope(name: string) {
  return items.value.some(function (s) { return s.name === name })
}
function stateLabel(s: string) {
  if (s === 'running') return '进行中'
  if (s === 'done') return '已完成'
  if (s === 'failed') return '失败'
  return '空闲'
}
function stateTone(s: string) {
  if (s === 'running') return 'warn'
  if (s === 'done') return 'good'
  if (s === 'failed') return 'bad'
  return 'neutral'
}

async function loadSources() {
  const r = await adminApi.get<SourcesResp>('/kb/wiki/sources')
  // 没保存过就回显 yml 默认值 —— 页面一打开就能看见"现在实际在拉什么"
  items.value = (r.saved ? r.items : r.defaults).map(function (s) {
    return { name: s.name, kind: s.kind, enabled: s.enabled }
  })
  saved.value = r.saved
  maxPagesPerSource.value = r.maxPagesPerSource
  renderEnabled.value = r.renderEnabled
}

async function loadCatalog() {
  const r = await adminApi.get<CatalogResp>('/kb/wiki/catalog')
  catalog.value = r.items
  catalogTotal.value = r.total
  catalogAt.value = r.cachedAt ? r.cachedAt.slice(11, 16) : ''
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

function addPicked() {
  if (!pickCategory.value) return
  items.value.push({ name: pickCategory.value, kind: 'category', enabled: true })
  pickCategory.value = ''
}

function addPrefix() {
  const v = prefixInput.value.trim()
  if (!v) return
  if (!items.value.some(function (s) { return s.name === v })) {
    items.value.push({ name: v, kind: 'prefix', enabled: true })
  }
  prefixInput.value = ''
}

function removeAt(i: number) {
  items.value.splice(i, 1)
}

async function save() {
  busy.value = true
  error.value = ''
  okMsg.value = ''
  try {
    const r = await adminApi.post<{ count: number; enabled: number }>('/kb/wiki/sources', { items: items.value })
    okMsg.value = '范围已保存：' + r.count + ' 条来源，启用 ' + r.enabled + ' 条'
    await loadSources()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busy.value = false
  }
}

async function start(mode: string) {
  busy.value = true
  error.value = ''
  okMsg.value = ''
  try {
    await adminApi.post('/kb/wiki/import', { mode: mode })
    okMsg.value = mode === 'dry-run' ? '空跑已开始（不写库）' : '导入已开始，可以关掉页面'
    await refreshStatus()
    poll()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busy.value = false
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

onMounted(async function () {
  try {
    await Promise.all([loadSources(), loadCatalog()])
    await refreshStatus()
    if (running.value) poll()
  } catch (e) {
    error.value = describeError(e)
  }
})

onUnmounted(function () {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-if="okMsg" tone="ok">{{ okMsg }}</Notice>

    <Panel title="拉取范围" :hint="scopeHint">
      <div class="meta-row">
        <Tag :tone="saved ? 'good' : 'warn'">{{ saved ? '页面已保存' : 'yml 默认值' }}</Tag>
        <Tag tone="neutral">每来源上限 {{ maxPagesPerSource }} 页</Tag>
        <Tag :tone="renderEnabled ? 'good' : 'neutral'">渲染兜底 {{ renderEnabled ? '开' : '关' }}</Tag>
      </div>

      <table v-if="items.length" class="tbl">
        <thead>
          <tr><th>来源</th><th>类型</th><th class="c">启用</th><th class="r">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="(s, i) in items" :key="s.kind + ':' + s.name">
            <td class="mono">{{ s.name }}</td>
            <td class="faint">{{ s.kind === 'prefix' ? '页名前缀' : '分类' }}</td>
            <td class="c"><Switch v-model="s.enabled" :aria-label="'启用 ' + s.name" /></td>
            <td class="r"><Button size="sm" variant="ghost" @click="removeAt(i)">移除</Button></td>
          </tr>
        </tbody>
      </table>
      <Empty v-else text="还没有选来源" hint="从下面的分类菜单里选，或手动加一个页名前缀" />

      <div class="add-row">
        <div class="add-sel">
          <Select v-model="pickCategory" :options="categoryOptions" placeholder="从 wiki 分类里选（带页数）…" />
        </div>
        <Button size="sm" :disabled="!pickCategory" @click="addPicked">加入范围</Button>
        <div class="add-kw">
          <Input v-model="prefixInput" mono placeholder="或输入页名前缀，如 Quests/" />
        </div>
        <Button size="sm" :disabled="!prefixInput.trim()" @click="addPrefix">加前缀</Button>
      </div>

      <div class="actions">
        <Button variant="primary" size="sm" :disabled="busy" @click="save">保存范围</Button>
        <Button size="sm" :disabled="busy" @click="loadSources">放弃改动</Button>
        <span class="faint">菜单共 {{ catalogTotal }} 个分类{{ catalogAt ? '（缓存于 ' + catalogAt + '）' : '' }}</span>
      </div>
    </Panel>

    <Panel title="触发拉取 + 清洗">
      <div class="actions">
        <Button size="sm" :disabled="running" @click="start('dry-run')">空跑（只看数量）</Button>
        <Button variant="primary" size="sm" :disabled="running" @click="start('incremental')">增量导入</Button>
        <Button variant="danger" size="sm" :disabled="running" @click="start('force')">强制重洗全部</Button>
        <Tag :tone="stateTone(status.state)">{{ stateLabel(status.state) }}</Tag>
      </div>
      <div class="actions">
        <template v-if="!purgeArmed">
          <Button size="sm" :disabled="running" @click="purgeArmed = true">回滚：清空 wiki 块</Button>
          <span class="faint">清掉全部 wiki 文章块与导入状态，可重新导入</span>
        </template>
        <template v-else>
          <span class="warn-text">确认清空？清完词条表会留下孤儿词条，要去「词条」页点一次「清理孤儿」。</span>
          <Button variant="danger" size="sm" :disabled="running" @click="doPurge">确认清空</Button>
          <Button size="sm" @click="purgeArmed = false">取消</Button>
        </template>
      </div>
      <Bar
        v-if="running || status.state === 'done'"
        :label="status.phase || '进度'"
        :value="status.done"
        :max="progressMax"
      />
      <p class="faint tip">
        增量只处理 wiki 上 revid 变过的页；<b>清洗规则改了要选「强制重洗全部」</b>，否则老页永远是旧规则的产物。
        导入在服务端后台跑，关掉页面也会继续；<b>跑完要重启服务</b>，索引才重载。
      </p>
    </Panel>

    <Panel v-if="report" title="上次结果">
      <div class="stats">
        <div class="stat"><span class="k">页面</span><span class="v num">{{ fmt(report.pages) }}</span></div>
        <div class="stat"><span class="k">版本变过</span><span class="v num">{{ fmt(report.changed) }}</span></div>
        <div class="stat"><span class="k">写入块</span><span class="v num">{{ fmt(report.imported) }}</span></div>
        <div class="stat"><span class="k">正文字数</span><span class="v num">{{ fmt(report.chars) }}</span></div>
        <div class="stat"><span class="k">渲染兜底</span><span class="v num">{{ fmt(report.rendered) }} 页</span></div>
        <div class="stat"><span class="k">失败</span><span class="v num">{{ fmt(report.failed) }}</span></div>
      </div>
      <table v-if="report.bySource" class="tbl">
        <thead><tr><th>来源</th><th class="r">页数</th></tr></thead>
        <tbody>
          <tr v-for="(n, k) in report.bySource" :key="k"><td class="mono">{{ k }}</td><td class="r num">{{ fmt(n) }}</td></tr>
        </tbody>
      </table>
      <ul v-if="status.errors && status.errors.length" class="errs">
        <li v-for="(e, i) in status.errors" :key="i">{{ e }}</li>
      </ul>
    </Panel>

    <Panel title="wiki 上的分类" :count="catalog.length">
      <Input v-model="filter" mono placeholder="搜索分类名（英文）…" aria-label="搜索分类名" />
      <table class="tbl scroll">
        <thead>
          <tr><th>分类</th><th class="r">成员数</th><th class="r">正文页数</th><th class="c">在范围</th></tr>
        </thead>
        <tbody>
          <tr v-for="c in filteredCatalog" :key="nameOf(c)">
            <td class="mono">{{ nameOf(c) }}</td>
            <td class="r num faint">{{ sizeOf(c) }}</td>
            <td class="r num">{{ pagesOf(c) }}</td>
            <td class="c"><Tag v-if="inScope(nameOf(c))" tone="good">已选</Tag><span v-else class="faint">—</span></td>
          </tr>
        </tbody>
      </table>
    </Panel>
  </div>
</template>

<style scoped>
.panel-wrap { display: grid; gap: var(--sp-4); }
.meta-row { display: flex; gap: var(--sp-2); align-items: center; flex-wrap: wrap; margin-bottom: var(--sp-3); }
.tbl { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
.tbl th, .tbl td { text-align: left; padding: 6px 10px; border-bottom: 1px solid var(--hairline); }
.tbl th { color: var(--ink-faint); font-weight: 500; font-size: var(--fs-meta); }
.tbl td.c, .tbl th.c { text-align: center; }
.tbl td.r, .tbl th.r { text-align: right; }
.tbl.scroll { display: block; max-height: 420px; overflow: auto; }
.mono { font-family: var(--font-mono); }
.num { font-variant-numeric: tabular-nums; }
.faint { color: var(--ink-faint); }
.add-row { display: flex; gap: var(--sp-2); align-items: center; margin-top: var(--sp-3); flex-wrap: wrap; }
.add-sel { flex: 1 1 260px; min-width: 200px; }
.add-kw { flex: 1 1 200px; min-width: 160px; }
.actions { display: flex; gap: var(--sp-2); align-items: center; margin-top: var(--sp-3); flex-wrap: wrap; }
.tip { color: var(--ink-dim); font-size: var(--fs-meta); line-height: 1.7; margin-top: var(--sp-3); }
.stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: var(--sp-2); margin-bottom: var(--sp-3); }
.stat { background: var(--surface-inset); border: 1px solid var(--edge); border-radius: var(--r-sm); padding: var(--sp-2) var(--sp-3); }
.stat .k { display: block; color: var(--ink-faint); font-size: var(--fs-meta); }
.stat .v { display: block; color: var(--ink); font-size: var(--fs-lg); margin-top: 2px; }
.warn-text { color: var(--flame-bright); font-size: var(--fs-meta); }
.errs { margin: var(--sp-3) 0 0; padding-left: 18px; color: var(--ink-dim); font-size: var(--fs-meta); line-height: 1.7; }
</style>
