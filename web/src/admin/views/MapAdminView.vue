<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { adminApi, describeError, publicApi } from '@shared/api/client'
import AdminPage from '@shared/ui/AdminPage.vue'
import Button from '@shared/ui/Button.vue'
import Icon from '@shared/ui/Icon.vue'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Tag from '@shared/ui/Tag.vue'
import Tabs from '@shared/ui/Tabs.vue'
import { toast } from '@shared/ui/toast'
import Modal from '@shared/ui/Modal.vue'
import MapCanvas from '@shared/map/MapCanvas.vue'
import type { AnchorReport, AnchorReview, Catalog, JobState, LocateHit, LocateResult, MapCandidate, MapCandidates, MapSpec, MapStatus, Marker, PlaceInput, Relative } from '@shared/map/types'
import Bar from '@shared/ui/Bar.vue'

type Mode = 'edit' | 'view'

const tab = ref('mark')
const mode = ref<Mode>('view')
const loading = ref(true)
const error = ref('')

const catalog = ref<Catalog | null>(null)
const status = ref<MapStatus | null>(null)
const mapName = ref('')
const markers = ref<Marker[]>([])
const canvasIndex = ref(0)

const selected = ref<Marker | null>(null)
const draft = ref<{ x: number; y: number } | null>(null)
const highlight = ref<{ toRef: string; relative: Relative } | null>(null)

const form = ref<PlaceInput & { id: number }>(blankForm())
const saving = ref(false)

const noteForm = ref({
  alias: '', note: '', category: '', landmark: false, priority: 0, hidden: false,
})
const noteSaving = ref(false)

const mapCanvas = ref<InstanceType<typeof MapCanvas> | null>(null)

const findQuery = ref('')
const finding = ref(false)
const found = ref<LocateResult | null>(null)

async function findPoint() {
  const q = findQuery.value.trim()
  if (!q || finding.value) return
  finding.value = true
  try {
    found.value = await publicApi.get<LocateResult>('/map/locate?q=' + encodeURIComponent(q))
  } catch (e) {
    error.value = describeError(e)
  } finally {
    finding.value = false
  }
}

function focusHit(hit: LocateHit) {
  const m = markers.value.find((x) => x.id === hit.ref)
    ?? markers.value.find((x) => Math.abs(x.x - hit.x) < 0.5 && Math.abs(x.y - hit.y) < 0.5)
  if (m) {
    jumpTo(m)
  } else {
    mapCanvas.value?.focus(hit.x, hit.y, Math.max(mapCanvas.value?.scale() ?? 0.5, 0.6))
  }
  found.value = null
}

const currentMap = computed<MapSpec | null>(
  () => catalog.value?.maps.find((m) => m.map === mapName.value) ?? null,
)
const currentCanvas = computed(() => {
  const list = currentMap.value?.canvases ?? []
  return list[Math.min(canvasIndex.value, Math.max(0, list.length - 1))] ?? null
})
const markersOnCanvas = computed(() =>
  markers.value.filter((m) => !currentCanvas.value || !m.canvas || m.canvas === currentCanvas.value.page),
)
const visibleGroups = ref<Set<string>>(new Set())

function syncVisibleGroups() {
  const map = currentMap.value
  const configured = map?.defaultGroups
  if (configured) {
    visibleGroups.value = new Set(configured)
    return
  }
  visibleGroups.value = new Set(
    (map?.groups ?? []).filter((g) => g.category !== 'collection').map((g) => g.key),
  )
}
const allCategories = computed(() => new Set((catalog.value?.categories ?? []).map((c) => c.key)))
const groupIcons = computed(() => {
  const out = new Map<string, string>()
  for (const g of currentMap.value?.groups ?? []) if (g.icon) out.set(g.key, g.icon)
  return out
})
const categoryOptions = computed(() =>
  (catalog.value?.categories ?? []).map((c) => ({ value: c.key, label: c.label })),
)
const mapPlaces = computed(() => markers.value)

const placeKw = ref('')
const placeLimit = ref(200)
const PLACE_STEP = 200

const filteredPlaces = computed(() => {
  const kw = placeKw.value.trim().toLowerCase()
  if (!kw) return mapPlaces.value
  return mapPlaces.value.filter((p) =>
    p.name.toLowerCase().includes(kw)
    || p.category.toLowerCase().includes(kw)
    || (p.note ?? '').toLowerCase().includes(kw))
})
const shownPlaces = computed(() => filteredPlaces.value.slice(0, placeLimit.value))
watch(placeKw, () => { placeLimit.value = PLACE_STEP })

const anchor = ref<AnchorReport | null>(null)

const mapList = ref<MapCandidates | null>(null)

const primarySpec = computed(() => catalog.value?.maps?.[0] ?? null)

const displayMap = computed(() =>
  primarySpec.value?.map ?? mapList.value?.candidates?.find((c) => c.active)?.map ?? '—'
)

function canvasOf(map: string): { label: string; renderable: boolean } {
  const list = (status.value?.canvases ?? []).filter((c) => c.map === map)
  const ok = list.filter((c) => c.renderable)
  if (ok.length) {
    const tiles = ok.reduce((n, c) => n + (c.tiles || 0), 0)
    return { label: ok.length + ' 张 · ' + fmt(tiles) + ' 瓦片', renderable: true }
  }
  return { label: list.find((c) => c.background)?.background || '—', renderable: false }
}

const failedPages = computed(() => (status.value?.pages ?? []).filter((p) => p.error))

const jobFacts = computed<Record<string, string>>((): Record<string, string> => {
  const s = status.value
  if (!s) return {}
  const pages = s.pages ?? []
  const markers = pages.reduce((n, p) => n + p.markerCount, 0)
  const syncedAt = pages.reduce((t, p) => (p.syncedAt > t ? p.syncedAt : t), '')
  const cache = s.tileCache
  return {
    sync: pages.length + ' 页 · ' + fmt(markers) + ' 个点位 · 最近同步 ' + since(syncedAt),
    warm: cache.enabled
      ? fmt(cache.files) + ' 个文件 · ' + fmtBytes(cache.bytes) + ' · 最近写入 ' + since(cache.latestAt)
      : '缓存已关闭',
    corpus: fmt(s.corpus?.blocks ?? 0) + ' 条 · 最近派生 ' + since(s.corpus?.latestAt),
    reset: '',
  }
})

interface CoverCell { have: number; need: number }
interface CoverRow { key: string; label: string; title: string; cells: Record<string, CoverCell | undefined> }

const warmCover = computed(() => {
  const units = status.value?.warmCoverage ?? []
  const tiers: string[] = []
  const rows: CoverRow[] = []
  const byKey = new Map<string, CoverRow>()
  const multiMap = new Set(units.map((u) => u.map)).size > 1
  for (const u of units) {
    if (u.tier && !tiers.includes(u.tier)) tiers.push(u.tier)
    const key = u.map + '|' + u.unit
    let row = byKey.get(key)
    if (!row) {
      row = {
        key,
        label: (multiMap ? u.mapLabel + ' · ' : '') + u.unit,
        title: u.mapLabel + ' · ' + u.unit,
        cells: {},
      }
      byKey.set(key, row)
      rows.push(row)
    }
    row.cells[u.tier] = { have: u.have, need: u.need }
  }
  return { tiers, rows }
})

function coverTone(cell?: CoverCell): string {
  if (!cell) return 't-none'
  return cell.have >= cell.need ? 't-ok' : 't-short'
}

function coverText(cell?: CoverCell): string {
  return cell ? cell.have + '/' + cell.need : '—'
}

const jobRows = computed(() => {
  const j = (status.value?.jobs ?? {}) as Record<string, JobState | undefined>
  const facts = jobFacts.value
  return [
    { key: 'sync', label: '同步 wiki 地图', state: j.sync },
    { key: 'warm', label: '底图缓存', state: j.warm },
    { key: 'corpus', label: '地点语料', state: j.corpus },
    { key: 'reset', label: '地图数据', state: j.reset },
  ].map((r) => ({
    ...r,
    tag: jobTag(r.state),
    facts: facts[r.key] ?? '',
    fail: r.state?.finishedAt && !r.state.ok ? r.state.summary : '',
  }))
})

function jobTag(j?: JobState): { tone: 'warn' | 'bad'; text: string } | null {
  if (!j) return null
  if (j.running) return { tone: 'warn', text: '运行中' }
  if (j.finishedAt && !j.ok) return { tone: 'bad', text: '失败' }
  return null
}

async function runReset() {
  const st = status.value
  const markers = (st?.pages ?? []).reduce((n, p) => n + p.markerCount, 0)
  const cache = st?.tileCache
  const parts = [
    '清空地图数据？',
    '',
    '将删除 wiki 同步来的：页面状态、画布、分组、点位（约 ' + fmt(markers) + ' 个）',
  ]
  if (cache?.enabled) {
    parts.push('以及瓦片缓存（' + cache.files + ' 个文件 · ' + fmtBytes(cache.bytes) + '）')
  }
  parts.push(
    '',
    '不会动：你自己建的点位、备注、每张图的图层配置。',
    '之后同步一次即可恢复；瓦片要重新预热，在那之前访客取图会慢。'
  )
  if (!window.confirm(parts.join('\n'))) {
    return
  }
  try {
    await adminApi.del('/map/cache?tiles=true')
    toast('清空已开始 —— 删了多少看「地图数据」那一行')
    await pollStatus()
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

interface Todo {
  id: string
  tone: 'warn' | 'bad'
  text: string
  detail: string
  action: string
}

const todos = computed<Todo[]>(() => {
  const out: Todo[] = []
  const s = status.value
  if (!s) {
    return out
  }
  if (!s.mapStoreAvailable || !s.placesAvailable || !s.notesAvailable) {
    const down = [
      !s.mapStoreAvailable ? '地图库' : '',
      !s.placesAvailable ? '点位' : '',
      !s.notesAvailable ? '备注' : '',
    ].filter(Boolean).join('、')
    out.push({ id: 'health', tone: 'bad', text: '存储不可用：' + down,
      detail: '地图与地点问答都会缺东西', action: '看诊断' })
  }
  const failed = (s.pages ?? []).filter((p) => p.error)
  if (failed.length) {
    out.push({ id: 'pages', tone: 'bad', text: failed.length + ' 页同步失败',
      detail: failed[0].page + (failed.length > 1 ? ' 等 ' + failed.length + ' 页' : ''),
      action: '看错误' })
  }
  if (s.anchorPending > 0) {
    out.push({ id: 'anchor', tone: 'warn', text: s.anchorPending + ' 个点位需要复核',
      detail: anchor.value?.items?.[0]?.reason ?? '底图变过，落点可能偏了',
      action: '去复核' })
  }
  const top = mapList.value?.candidates?.[0]
  const active = mapList.value?.candidates?.find((c) => c.active)
  if (top && !top.active && top.markers > (active?.markers ?? 0)) {
    out.push({ id: 'maps', tone: 'warn', text: '有比当前更全的地图：' + top.map,
      detail: fmt(top.markers) + ' 点位 · wiki 最后改动 ' + (top.lastChanged?.slice(0, 10) || '—'),
      action: '去切换' })
  }
  return out
})

const mapsDetails = ref<HTMLDetailsElement | null>(null)
const pagesDetails = ref<HTMLDetailsElement | null>(null)
const healthDetails = ref<HTMLDetailsElement | null>(null)

function reveal(el: HTMLDetailsElement | null) {
  if (!el) {
    return
  }
  el.open = true
  const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  el.scrollIntoView({ block: 'start', behavior: reduce ? 'auto' : 'smooth' })
}

function todoAction(id: string) {
  if (id === 'anchor') {
    tab.value = 'places'
    toast('在点位列表里点名字，就能跳到那一个')
  } else if (id === 'pages') {
    reveal(pagesDetails.value)
  } else if (id === 'health') {
    reveal(healthDetails.value)
  } else if (id === 'maps') {
    reveal(mapsDetails.value)
  }
}

function since(iso?: string): string {
  if (!iso) {
    return '—'
  }
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) {
    return stamp(iso)
  }
  const minutes = Math.round((Date.now() - t) / 60000)
  if (minutes <= 0) return '刚刚'
  if (minutes < 60) return minutes + ' 分钟前'
  const hours = Math.round(minutes / 60)
  if (hours < 24) return hours + ' 小时前'
  const days = Math.round(hours / 24)
  return days < 7 ? days + ' 天前' : stamp(iso)
}

function stamp(iso?: string): string {
  if (!iso) {
    return '—'
  }
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) {
    return iso.slice(0, 16).replace('T', ' ')
  }
  const p = (n: number) => String(n).padStart(2, '0')
  return p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes())
}

function blankForm(): PlaceInput & { id: number } {
  return {
    id: 0, map: mapName.value, page: '', x: 0, y: 0, name: '', aliases: [], category: 'resource',
    note: '', icon: '', color: '', landmark: false, priority: 0, visible: true,
  }
}

async function boot() {
  loading.value = true
  error.value = ''
  try {
    catalog.value = await adminApi.get<Catalog>('/map/catalog')
    const first = catalog.value.maps[0]
    await selectMap(first ? first.map : '', true)
    await nextTick()
    mapCanvas.value?.setGroupIcons(groupIcons.value)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
  if (tab.value !== 'mark') void loadExtras()
}

async function loadExtras() {
  await Promise.allSettled([loadStatus(), loadAnchor(), loadMaps()])
}

let extrasLoaded = false

function ensureExtras(): void {
  if (extrasLoaded) return
  extrasLoaded = true
  void loadExtras()
}

watch(tab, (next) => {
  if (next !== 'mark') ensureExtras()
})

async function loadMarkers() {
  const r = await adminApi.get<{ markers: Marker[] }>('/map/markers?map=' + encodeURIComponent(mapName.value))
  markers.value = r.markers
  syncVisibleGroups()
  selected.value = null
}

let mapTask: Promise<void> | null = null

async function selectMap(name: string, force = false): Promise<void> {
  if (!name) return
  if (name === mapName.value) {
    if (force) await loadMarkers()
    return
  }
  mapName.value = name
  await nextTick()
  await mapTask
}

async function loadStatus() {
  try {
    status.value = await adminApi.get<MapStatus>('/map/status')
  } catch (e) {
    error.value = describeError(e)
  }
}

async function loadMaps() {
  try {
    mapList.value = await adminApi.get<MapCandidates>('/map/maps')
  } catch {
    mapList.value = null
  }
}

async function toggleMap(m: MapCandidate) {
  try {
    await adminApi.put('/map/maps/active', { map: m.map, active: !m.active })
    toast((m.active ? '已停用 ' : '已启用 ') + m.map)
    await loadMaps()
    await boot()
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

async function loadAnchor() {
  try {
    anchor.value = await adminApi.get<AnchorReport>('/map/anchor')
  } catch {
    anchor.value = null
  }
}

async function jumpToAnchor(a: AnchorReview) {
  if (a.map !== mapName.value) {
    await selectMap(a.map)
  }
  const idx = (currentMap.value?.canvases ?? []).findIndex((c) => c.page === a.page)
  if (idx >= 0) canvasIndex.value = idx
  await nextTick()
  const m = markers.value.find((x) => x.id === 'place:' + a.id)
  if (m) onPick([m])
}

async function confirmAnchor(id: number) {
  try {
    await adminApi.post('/map/places/' + id + '/confirm-anchor')
    toast('已确认位置无误')
    await loadAnchor()
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

onMounted(boot)

watch(mapName, (next, prev) => {
  if (!next || next === prev) return
  canvasIndex.value = 0
  mapTask = loadMarkers()
})

watch([groupIcons, mapCanvas], () => mapCanvas.value?.setGroupIcons(groupIcons.value), { flush: 'post' })

const panelOpen = ref(false)

function startNewPlace() {
  selected.value = null
  draft.value = null
  form.value = { ...blankForm(), map: mapName.value, page: currentCanvas.value?.page ?? '' }
  noteForm.value = { alias: '', note: '', category: '', landmark: false, priority: 0, hidden: false }
  panelOpen.value = true
  mode.value = 'edit'
}

const panelKind = computed<'new' | 'place' | 'note' | null>(() => {
  if (mode.value === 'edit') return 'new'
  if (!selected.value) return null
  return selected.value.source === 'admin' ? 'place' : 'note'
})

function closePanel() {
  panelOpen.value = false
  mode.value = 'view'
  selected.value = null
  draft.value = null
}

function onPick(list: Marker[]) {
  const m = list[0] ?? null
  if (!m) {
    selected.value = null
    panelOpen.value = false
    mode.value = 'view'
    return
  }
  selected.value = m
  highlight.value = null
  panelOpen.value = true
  if (m.source === 'admin') {
    form.value = {
      id: Number(m.id.replace('place:', '')),
      map: m.map, page: m.page, x: m.x, y: m.y, name: m.name,
      aliases: [], category: m.category, note: m.note, icon: '', color: '',
      landmark: m.landmark, priority: m.priority, visible: true,
    }
    noteForm.value = { alias: '', note: '', category: '', landmark: false, priority: 0, hidden: false }
  } else {
    noteForm.value = {
      alias: m.name === m.original ? '' : m.name,
      note: m.note, category: '', landmark: m.landmark,
      priority: m.priority, hidden: false,
    }
    form.value = blankForm()
  }
}

function onPickedPoint(x: number, y: number) {
  draft.value = { x, y }
  form.value = { ...blankForm(), map: mapName.value, page: currentCanvas.value?.page ?? '', x, y }
  selected.value = null
  panelOpen.value = true
}

function jumpTo(m: Marker) {
  const list = currentMap.value?.canvases ?? []
  const idx = list.findIndex((c) => c.page === m.canvas)
  if (idx >= 0) canvasIndex.value = idx
  tab.value = 'mark'
  onPick([m])
  mapCanvas.value?.focus(m.x, m.y, Math.max(mapCanvas.value?.scale() ?? 0.5, 0.6))
}

async function savePlace() {
  if (saving.value) return
  if (!form.value.name.trim()) {
    toast('地点名不能为空', 'warn')
    return
  }
  saving.value = true
  try {
    const body = { ...form.value }
    const before = anchor.value?.items.find((a) => a.id === body.id) ?? null
    if (body.id > 0) {
      await adminApi.put('/map/places/' + body.id, body)
      toast('已更新点位')
    } else {
      await adminApi.post('/map/places', body)
      toast('已新增点位')
    }
    draft.value = null
    await loadMarkers()
    await loadStatus()
    await loadAnchor()
    if (before) await offerBatchShift(before, body.x, body.y)
  } catch (e) {
    toast(describeError(e), 'error')
  } finally {
    saving.value = false
  }
}

async function offerBatchShift(before: AnchorReview, x: number, y: number) {
  const dx = x - before.x
  const dy = y - before.y
  if (dx === 0 && dy === 0) return
  const siblings = (anchor.value?.items ?? []).filter(
    (a) => a.id !== before.id && a.map === before.map && a.page === before.page,
  ).length
  if (siblings === 0) return
  const d = (n: number) => (n > 0 ? '+' : '') + Math.round(n)
  const yes = window.confirm(
    '「' + before.name + '」挪了 (' + d(dx) + ', ' + d(dy) + ')。\n' +
    '同一张底图上还有 ' + siblings + ' 个待复核的点，要按同样的偏移一起搬吗？',
  )
  if (!yes) return
  try {
    const r = await adminApi.post<{ moved: number; skipped: number }>(
      '/map/anchor/apply-offset', { referenceId: before.id, dx, dy })
    toast('已按偏移搬移 ' + r.moved + ' 个点位' + (r.skipped ? '，跳过 ' + r.skipped + ' 个' : ''))
    await loadMarkers()
    await loadAnchor()
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

async function deletePlace() {
  if (form.value.id <= 0) return
  if (!window.confirm('删除「' + form.value.name + '」？这一步不可撤销。')) return
  try {
    await adminApi.del('/map/places/' + form.value.id)
    toast('已删除')
    form.value = blankForm()
    selected.value = null
    await loadMarkers()
    await loadAnchor()
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

async function saveNote() {
  if (!selected.value || selected.value.source !== 'wiki' || noteSaving.value) return
  noteSaving.value = true
  try {
    const markerId = markerIdOf(selected.value)
    await adminApi.post('/map/notes', {
      map: selected.value.map,
      page: selected.value.page,
      markerId,
      alias: noteForm.value.alias,
      note: noteForm.value.note,
      category: noteForm.value.category,
      landmark: noteForm.value.landmark,
      priority: noteForm.value.priority,
      hidden: noteForm.value.hidden,
    })
    toast('备注已保存')
    await loadMarkers()
  } catch (e) {
    toast(describeError(e), 'error')
  } finally {
    noteSaving.value = false
  }
}

function markerIdOf(m: Marker): string {
  const hash = m.id.indexOf('#')
  return hash >= 0 ? m.id.slice(hash + 1) : m.id
}

async function clearNote() {
  if (!selected.value) return
  const markerId = markerIdOf(selected.value)
  try {
    await adminApi.del(
      '/map/notes?map=' + encodeURIComponent(selected.value.map) +
      '&page=' + encodeURIComponent(selected.value.page) +
      '&markerId=' + encodeURIComponent(markerId))
    toast('备注已清除')
    await loadMarkers()
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

async function runSync() {
  try {
    await adminApi.post('/map/sync')
    toast('同步已开始，稍后看状态')
    await pollStatus()
    if (!catalog.value || !catalog.value.maps.length) {
      await boot()
    }
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

async function runWarm() {
  try {
    await adminApi.post('/map/warm')
    toast('缓存预热已开始：首屏瓦片会一次性拉齐')
    await pollStatus()
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

async function runWarmFull() {
  try {
    await adminApi.post('/map/warm-full')
    toast('全量预热已开始：含原图档，首次约十几分钟')
    await pollStatus()
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

async function runCorpus(purge: boolean) {
  if (purge && !window.confirm('清除全部派生地点语料？这一步不可撤销，之后要重新派生一次（要调向量接口，较慢）。')) {
    return
  }
  try {
    await adminApi.post('/map/corpus?purge=' + (purge ? 'true' : 'false'))
    toast(purge ? '清除已开始' : '语料派生已开始')
    await pollStatus()
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

async function pollStatus() {
  for (let i = 0; i < 20; i++) {
    await new Promise((r) => setTimeout(r, 1500))
    try {
      await loadStatus()
    } catch {   }
    const jobs = status.value?.jobs ?? {}
    const running = Object.values(jobs).some((j) => j.running)
    if (!running) break
  }
  await loadAnchor()
  await loadMaps()
}

function fmtBytes(n: number): string {
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(0) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}

function fmt(n: number): string {
  return Math.round(n).toLocaleString('zh-CN')
}

interface LayerGroup {
  key: string
  name: string
  icon: string
  category: string
  count: number
  on: boolean
}

interface LayerMap {
  map: string
  label: string
  markerCount: number
  configured: boolean
  groups: LayerGroup[]
}

const layerMaps = ref<LayerMap[]>([])
const layerMap = ref('')
const layerOn = ref<Set<string>>(new Set())
const layerLoading = ref(false)
const layerSaving = ref(false)

const layerCurrent = computed(() => layerMaps.value.find((m) => m.map === layerMap.value) ?? null)

const layerIconFailed = ref<Set<string>>(new Set())

function onLayerIconError(name: string) {
  const next = new Set(layerIconFailed.value)
  next.add(name)
  layerIconFailed.value = next
}

function layerIconUrl(name: string): string {
  return '/api/public/v1/map/tile?map=' + encodeURIComponent(layerMap.value)
    + '&name=' + encodeURIComponent(name)
}

function syncLayerSelection() {
  const cur = layerCurrent.value
  layerOn.value = new Set(cur ? cur.groups.filter((g) => g.on).map((g) => g.key) : [])
}

async function loadLayers() {
  layerLoading.value = true
  try {
    const r = await adminApi.get<{ maps: LayerMap[] }>('/map/layers')
    layerMaps.value = r.maps
    if (!r.maps.some((m) => m.map === layerMap.value)) {
      layerMap.value = r.maps[0]?.map ?? ''
    }
    syncLayerSelection()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    layerLoading.value = false
  }
}

function openDefaults() {
  defaultsOpen.value = true
  void loadLayers()
}

const defaultsOpen = ref(false)

watch(layerMap, syncLayerSelection)

function toggleLayer(key: string) {
  const next = new Set(layerOn.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  layerOn.value = next
}

async function saveLayers(keys: string[] | null) {
  const cur = layerCurrent.value
  if (!cur) return
  layerSaving.value = true
  try {
    await adminApi.put('/map/layers', { map: cur.map, groups: keys })
    toast(keys === null ? '已恢复为未配置' : '已保存，公开地图页下次打开就按这个显示')
    await loadLayers()
    catalog.value = await adminApi.get<Catalog>('/map/catalog')
    syncVisibleGroups()
    await nextTick()
    mapCanvas.value?.setGroupIcons(groupIcons.value)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    layerSaving.value = false
  }
}
</script>

<template>
  <AdminPage width="wide">
    <template #tabs>
      <Tabs v-model="tab" :tabs="[
        { key: 'mark', label: '标注' },
        { key: 'places', label: '点位列表', count: mapPlaces.length },
        { key: 'status', label: '同步与状态' },
      ]" />
    </template>
    <template #actions>
      <form class="find" @submit.prevent="findPoint">
        <Input v-model="findQuery" placeholder="找点位：铁矿 / 尖塔 / 木匠" autocapitalize="off" />
        <Button type="submit" size="sm" :disabled="finding">
          <Icon name="search" :size="14" />{{ finding ? '查找中' : '查找' }}
        </Button>
      </form>
      <Button size="sm" @click="openDefaults">
        <Icon name="display" :size="14" />设置默认显示
      </Button>
      <Button size="sm" @click="startNewPlace">
        <Icon name="plus" :size="14" />新增点位
      </Button>
      <Button size="sm" @click="boot"><Icon name="refresh" :size="14" />重新载入</Button>
    </template>

    <div v-if="found" class="find-hits">
      <div class="find-hits-head">
        <span class="hint">「{{ found.q }}」找到 {{ found.hits.length }} 个</span>
        <button type="button" class="mini" @click="found = null">关闭</button>
      </div>
      <ol v-if="found.hits.length" class="hits">
        <li v-for="h in found.hits" :key="h.ref + h.x + h.y">
          <button type="button" class="hit" @click="focusHit(h)">
            <span class="hit-name">
              {{ h.name }}
              <Tag v-if="h.source === 'admin'" tone="flame">本地</Tag>
            </span>
            <span class="hit-rel">
              <template v-if="h.relative">{{ h.relative.phrase }}</template>
              <template v-else>坐标 ({{ fmt(h.x) }}, {{ fmt(h.y) }})</template>
            </span>
          </button>
        </li>
      </ol>
      <p v-else class="hint">没找到。</p>
    </div>

    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="loading" class="muted">正在载入…</p>

    <template v-else>
      <div v-show="tab === 'mark'">
        <div v-if="!catalog || !catalog.maps.length" class="empty-state">
          <h2>还没有地图数据</h2>
          <p>切到上面的「同步与状态」，点一次「同步 wiki 地图」。同步完成后这一页会自己刷新。</p>
          <Button variant="primary" size="sm" @click="tab = 'status'">去同步</Button>
        </div>

        <div v-else class="stage">
          <MapCanvas
            ref="mapCanvas"
            v-if="currentCanvas"
            :canvas="currentCanvas"
            :markers="markersOnCanvas"
            :visible-groups="visibleGroups"
            :visible-categories="allCategories"
            :selected-id="selected?.id ?? ''"
            :highlight="highlight"
            :draft="draft"
            :map-name="mapName"
            :max-zoom="catalog?.maxZoom ?? 2"
            :mode="mode"
            @pick="onPick"
            @picked-point="onPickedPoint"
          />

          <button v-if="!panelOpen" type="button" class="panel-toggle" @click="startNewPlace">
            <Icon name="plus" :size="16" />
            新增点位
          </button>

          <aside v-show="panelOpen" id="mark-panel" class="panel" aria-label="点位编辑">
            <div class="panel-head">
              <span class="panel-title">
                {{ panelKind === 'note' ? 'wiki 点位备注'
                  : panelKind === 'place' ? '编辑点位' : '新增点位' }}
              </span>
              <button type="button" class="panel-close" aria-label="收起面板" @click="closePanel">
                <Icon name="close" :size="15" />
              </button>
            </div>

          <div v-if="panelKind === 'new' || panelKind === 'place'" class="card">
            <h2 class="card-title">
              {{ form.id > 0 ? '编辑点位' : '新增点位' }}
              <Tag v-if="form.id > 0" tone="flame">id {{ form.id }}</Tag>
              <Tag v-else-if="draft" tone="warn">待落点</Tag>
            </h2>
            <label class="field">
              <span class="lab">名称</span>
              <Input v-model="form.name" placeholder="铁矿 / 树脂 / 老矿坑" />
            </label>
            <label class="field">
              <span class="lab">分类</span>
              <Select v-model="form.category" :options="categoryOptions" />
            </label>
            <label class="field">
              <span class="lab">备注</span>
              <Input v-model="form.note" multiline :rows="3" placeholder="出产、数量、附近有什么" />
            </label>
            <label class="field">
              <span class="lab">别名</span>
              <Input
                :model-value="form.aliases.join('，')"
                placeholder="多个用逗号分隔，检索时一并匹配"
                @update:model-value="form.aliases = $event.split(/[,，]/).map((s) => s.trim()).filter(Boolean)"
              />
            </label>
            <div class="row2">
              <label class="field">
                <span class="lab">坐标 X</span>
                <Input :model-value="String(form.x)" mono @update:model-value="form.x = Number($event) || 0" />
              </label>
              <label class="field">
                <span class="lab">坐标 Y</span>
                <Input :model-value="String(form.y)" mono @update:model-value="form.y = Number($event) || 0" />
              </label>
            </div>
            <label class="field">
              <span class="lab">优先级</span>
              <Input :model-value="String(form.priority)" @update:model-value="form.priority = Number($event) || 0" />
            </label>
            <div class="toggles">
              <label class="check">
                <input type="checkbox" v-model="form.landmark" />
                <span>可作方位参照</span>
              </label>
              <label class="check">
                <input type="checkbox" v-model="form.visible" />
                <span>在公开站显示</span>
              </label>
            </div>
            <div class="actions">
              <Button variant="primary" size="sm" :disabled="saving" @click="savePlace">
                {{ saving ? '保存中' : '保存' }}
              </Button>
              <Button v-if="form.id > 0" variant="danger" size="sm" @click="deletePlace">
                <Icon name="trash" :size="14" />删除
              </Button>
              <Button v-else size="sm" @click="draft = null; form = blankForm()">清空</Button>
            </div>
          </div>

          <div v-else-if="panelKind === 'note'" class="card">
            <h2 class="card-title">wiki 点位备注</h2>
            <template v-if="selected">
              <p class="picked">
                <Icon name="map-pin" :size="14" />
                {{ selected.original || selected.name }}
                <span class="mono">{{ fmt(selected.x) }}, {{ fmt(selected.y) }}</span>
              </p>
              <label class="field">
                <span class="lab">中文别名</span>
                <Input v-model="noteForm.alias" placeholder="留空则用 wiki 原名" />
              </label>
              <label class="field">
                <span class="lab">说明</span>
                <Input v-model="noteForm.note" multiline :rows="3" placeholder="离出生点最近的尖塔之类" />
              </label>
              <label class="field">
                <span class="lab">分类覆盖</span>
                <Select
                  :model-value="noteForm.category"
                  :options="[{ value: '', label: '不覆盖' }, ...categoryOptions]"
                  @update:model-value="noteForm.category = $event"
                />
              </label>
              <label class="field">
                <span class="lab">优先级</span>
                <Input :model-value="String(noteForm.priority)" @update:model-value="noteForm.priority = Number($event) || 0" />
              </label>
              <div class="toggles">
                <label class="check">
                  <input type="checkbox" v-model="noteForm.landmark" />
                  <span>可作方位参照</span>
                </label>
                <label class="check">
                  <input type="checkbox" v-model="noteForm.hidden" />
                  <span>隐藏（公开站与问答都不用）</span>
                </label>
              </div>
              <div class="actions">
                <Button variant="primary" size="sm" :disabled="noteSaving" @click="saveNote">
                  {{ noteSaving ? '保存中' : '保存备注' }}
                </Button>
                <Button size="sm" @click="clearNote">清除备注</Button>
              </div>
            </template>
          </div>
          </aside>
        </div>
      </div>

      <div v-if="tab === 'places'" class="table-wrap">
        <div v-if="anchor && anchor.pending > 0" class="anchor-warn">
          <div class="anchor-head">
            <Icon name="alert" :size="16" />
            <strong>底图变了，{{ anchor.pending }} 个点位要看一眼</strong>
          </div>
          <p class="hint">它们的坐标没动，但落点可能已经整体偏移。</p>
          <ul class="anchor-list">
            <li v-for="a in anchor.items" :key="a.id">
              <button type="button" class="anchor-name" @click="jumpToAnchor(a)">{{ a.name }}</button>
              <span class="anchor-why">{{ a.reason }}</span>
              <Button size="sm" @click="confirmAnchor(a.id)">位置无误</Button>
            </li>
          </ul>
        </div>

        <div class="place-tools">
          <Input
            v-model="placeKw"
            placeholder="筛选点位：名字 / 分类 / 备注"
            aria-label="筛选点位"
          />
          <span class="hint">{{ filteredPlaces.length }} 个</span>
        </div>

        <table class="tbl places-tbl">
          <colgroup>
            <col class="c-name">
            <col class="c-cat">
            <col class="c-xy">
            <col class="c-note">
            <col class="c-ref">
            <col class="c-pri">
          </colgroup>
          <thead>
            <tr>
              <th>名称</th><th>分类</th><th>坐标</th><th>备注</th><th>参照</th><th>优先级</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in shownPlaces" :key="p.id" class="place-row" @click="jumpTo(p)">
              <td>
                <span class="name-cell">
                  {{ p.name }}
                  <Tag :tone="p.source === 'admin' ? 'flame' : 'neutral'">{{ p.source === 'admin' ? '本地' : 'wiki' }}</Tag>
                </span>
              </td>
              <td>{{ p.category }}</td>
              <td class="mono">{{ fmt(p.x) }}, {{ fmt(p.y) }}</td>
              <td class="note-cell">{{ p.note }}</td>
              <td>{{ p.landmark ? '是' : '' }}</td>
              <td>{{ p.priority }}</td>
            </tr>
            <tr v-if="!filteredPlaces.length">
              <td colspan="6" class="muted">
                {{ placeKw ? '没有匹配的点位，换个词试试。' : '这张地图还没有点位。到「标注」页在地图上点一下即可新增。' }}
              </td>
            </tr>
          </tbody>
        </table>

        <div v-if="shownPlaces.length < filteredPlaces.length" class="place-more">
          <Button size="sm" @click="placeLimit += PLACE_STEP">继续显示</Button>
          <span class="hint">已显示 {{ shownPlaces.length }} / {{ filteredPlaces.length }}</span>
        </div>
      </div>

      <Modal
        :open="defaultsOpen"
        title="设置默认显示"
        width="720px"
        @close="defaultsOpen = false"
      >
        <template #actions>
          <Button
            variant="primary"
            size="sm"
            :disabled="layerSaving || !layerCurrent"
            @click="saveLayers([...layerOn])"
          >{{ layerSaving ? '保存中…' : '保存' }}</Button>
          <Button
            size="sm"
            :disabled="layerSaving || !layerCurrent"
            @click="saveLayers(null)"
          >恢复未配置</Button>
        </template>
      <div class="defaults">
        <p v-if="layerLoading" class="muted">正在载入…</p>

        <template v-else-if="layerMaps.length">
          <div v-if="layerMaps.length > 1" class="chips">
            <button
              v-for="m in layerMaps"
              :key="m.map"
              type="button"
              class="chip"
              :class="{ on: m.map === layerMap }"
              :aria-pressed="m.map === layerMap"
              @click="layerMap = m.map"
            >
              {{ m.label }}
              <span class="chip-n">{{ fmt(m.markerCount) }}</span>
              <span v-if="!m.configured" class="chip-tag">未配置</span>
            </button>
          </div>

          <div v-if="layerCurrent" class="card">
            <div class="card-head">
              <h2 class="card-title">默认显示的分组</h2>
              <div class="mini-actions">
                <button
                  type="button"
                  class="mini"
                  @click="layerOn = new Set(layerCurrent.groups.map((g) => g.key))"
                >全开</button>
                <button type="button" class="mini" @click="layerOn = new Set()">全关</button>
              </div>
            </div>

            <p class="hint">
              选中 <b>{{ layerOn.size }}</b> / {{ layerCurrent.groups.length }} 个分组。
              <template v-if="!layerCurrent.configured">
                这张地图<strong>还没配置过</strong>。
              </template>
            </p>

            <ul class="layer-list">
              <li v-for="g in layerCurrent.groups" :key="g.key">
                <label class="layer-row">
                  <input
                    type="checkbox"
                    :checked="layerOn.has(g.key)"
                    @change="toggleLayer(g.key)"
                  />
                  <img
                    v-if="g.icon && !layerIconFailed.has(g.icon)"
                    class="layer-icon"
                    :src="layerIconUrl(g.icon)"
                    alt=""
                    @error="onLayerIconError(g.icon)"
                  />
                  <span class="layer-name">{{ g.name }}</span>
                  <span class="layer-n">{{ fmt(g.count) }}</span>
                </label>
              </li>
            </ul>

          </div>
        </template>

        <div v-else class="empty-state">
          <h2>还没有地图数据</h2>
          <p>先到「同步与状态」同步一次 wiki 地图。</p>
        </div>
      </div>
      </Modal>

      <div v-show="tab === 'status'" class="status">

        <section v-if="todos.length" class="todo">
          <h3 class="sect">待办</h3>
          <ul class="todo-list">
            <li v-for="t in todos" :key="t.id" :class="'t-' + t.tone">
              <Icon :name="t.tone === 'bad' ? 'alert' : 'map-pin'" :size="15" />
              <span class="todo-text">{{ t.text }}</span>
              <span class="todo-detail">{{ t.detail }}</span>
              <Button size="sm" @click="todoAction(t.id)">{{ t.action }}</Button>
            </li>
          </ul>
        </section>
        <p v-else-if="status" class="todo-quiet">
          <Icon name="check" :size="14" />
          没有待办。
        </p>

        <details ref="mapsDetails" class="diag">
          <summary>
            <Icon name="chevron-right" :size="14" class="caret" />
            <span class="diag-name">地图</span>
            <span class="diag-count">{{ mapList?.candidates?.length ?? 0 }}</span>
            <span class="diag-here">当前显示 <b>{{ displayMap }}</b></span>
          </summary>
          <table v-if="mapList && mapList.candidates.length" class="tbl small">
            <thead>
              <tr>
                <th>地图</th><th>点位</th><th>底图</th><th>可渲染</th>
                <th>wiki 最后改动</th><th>状态</th><th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="m in mapList.candidates" :key="m.map">
                <td class="mono">{{ m.map }}</td>
                <td>{{ fmt(m.markers) }}</td>
                <td class="mono">{{ canvasOf(m.map).label }}</td>
                <td :class="{ no: !canvasOf(m.map).renderable }">
                  {{ canvasOf(m.map).renderable ? '是' : '否' }}
                </td>
                <td class="mono">{{ m.lastChanged ? m.lastChanged.slice(0, 10) : '—' }}</td>
                <td>
                  <Tag :tone="m.active ? 'good' : 'neutral'">
                    {{ m.active ? (m.auto ? '自动启用' : '已启用') : '未启用' }}
                  </Tag>
                </td>
                <td>
                  <Button size="sm" @click="toggleMap(m)">{{ m.active ? '停用' : '启用' }}</Button>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else class="hint">还没有地图数据。</p>
        </details>

        <section class="ops">
          <h3 class="sect">任务</h3>
          <ul class="op-list">
            <li v-for="row in jobRows" :key="row.key" class="op">
              <div class="op-main">
                <div class="op-head">
                  <span class="op-name">{{ row.label }}</span>
                  <Tag v-if="row.tag" :tone="row.tag.tone">{{ row.tag.text }}</Tag>
                </div>

                <Bar
                  v-if="row.state?.running && row.state.total > 0"
                  class="op-bar"
                  :label="'已处理 ' + row.state.done + ' / ' + row.state.total"
                  :value="row.state.done"
                  :max="row.state.total"
                  :show-value="false"
                />

                <p v-if="row.facts" class="op-result">{{ row.facts }}</p>

                <div
                  v-if="row.key === 'warm' && status?.tileCache?.enabled && warmCover.rows.length"
                  class="cover"
                >
                  <div class="cover-line cover-head">
                    <span class="cover-name" />
                    <span v-for="t in warmCover.tiers" :key="t" class="cover-cell">{{ t }}</span>
                  </div>
                  <div v-for="r in warmCover.rows" :key="r.key" class="cover-line">
                    <span class="cover-name" :title="r.title">{{ r.label }}</span>
                    <span
                      v-for="t in warmCover.tiers"
                      :key="t"
                      class="cover-cell"
                      :class="coverTone(r.cells[t])"
                    >{{ coverText(r.cells[t]) }}</span>
                  </div>
                </div>
                <p v-if="row.fail" class="op-fail">{{ row.fail }}</p>
              </div>
              <div class="op-actions">
                <template v-if="row.key === 'sync'">
                  <Button variant="primary" size="sm" title="只拉版本变过的页" @click="runSync">
                    <Icon name="refresh" :size="14" />同步
                  </Button>
                </template>
                <template v-else-if="row.key === 'warm'">
                  <Button size="sm" title="160 / 320 / 640 三档，一两分钟" @click="runWarm">预热首屏档</Button>
                  <Button size="sm" title="再加 1280 与原图档，首次十几分钟" @click="runWarmFull">全量预热</Button>
                </template>
                <template v-else-if="row.key === 'corpus'">
                  <Button size="sm" title="每个地点块调一次向量接口" @click="runCorpus(false)">派生语料</Button>
                  <Button variant="danger" size="sm" title="删掉全部派生地点块" @click="runCorpus(true)">清除语料</Button>
                </template>
                <template v-else>
                  <Button variant="danger" size="sm" title="删掉 wiki 同步来的页、画布、分组与点位" @click="runReset">清空地图数据</Button>
                </template>
              </div>
            </li>
          </ul>
        </section>

        <section class="diag-zone">
          <h3 class="sect">诊断</h3>

          <details ref="healthDetails" class="diag">
            <summary>
              <Icon name="chevron-right" :size="14" class="caret" />
              <span class="diag-name">存储与路径</span>
            </summary>
            <dl class="facts">
              <div><dt>问答库</dt><dd class="mono">{{ status?.directory }}</dd></div>
              <div>
                <dt>待复核点位</dt>
                <dd :class="{ bad: (status?.anchorPending ?? 0) > 0 }">
                  <template v-if="status && status.anchorPending > 0">
                    {{ status.anchorPending }} / {{ status.placeTotal }} 条（底图变过）
                  </template>
                  <template v-else>无</template>
                </dd>
              </div>
              <div>
                <dt>瓦片缓存</dt>
                <dd>
                  <template v-if="status">
                    {{ status.tileCache.enabled ? '启用' : '关闭' }} ·
                    {{ status.tileCache.files }} 个文件 · {{ fmtBytes(status.tileCache.bytes) }}
                  </template>
                </dd>
              </div>
              <div><dt>缓存目录</dt><dd class="mono">{{ status?.tileCache.root }}</dd></div>
            </dl>
          </details>

          <div v-if="status && !failedPages.length" class="diag diag-flat">
            <Icon name="check" :size="14" class="ok" />
            <span class="diag-name">同步页</span>
            <span class="diag-ok">{{ status.pages.length }} 页全部同步正常</span>
          </div>
          <details v-else ref="pagesDetails" class="diag">
            <summary>
              <Icon name="chevron-right" :size="14" class="caret" />
              <span class="diag-name">同步页</span>
              <span class="diag-count bad">{{ failedPages.length }}</span>
            </summary>
            <table class="tbl small">
              <thead><tr><th>页面</th><th>revid</th><th>点位</th><th>错误</th></tr></thead>
              <tbody>
                <tr v-for="p in failedPages" :key="p.page">
                  <td class="mono">{{ p.page }}</td>
                  <td class="mono">{{ p.revid }}</td>
                  <td>{{ p.markerCount }}</td>
                  <td class="err">{{ p.error }}</td>
                </tr>
              </tbody>
            </table>
          </details>

        </section>
      </div>

    </template>
  </AdminPage>
</template>

<style scoped>
.muted { color: var(--ink-3); }
.alert {
  margin: 0;
  padding: var(--sp-3) var(--sp-4);
  border-left: 3px solid var(--blight);
  background: var(--blight-veil);
  color: var(--blight-lift);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
}
.empty-state {
  padding: var(--sp-7) var(--sp-5);
  text-align: center;
  background: var(--stone-300);
  border-radius: var(--r-md);
  box-shadow: var(--bevel-raised);
}
.empty-state h2 { margin: 0 0 var(--sp-2); font-size: var(--fs-lg); }

.page { padding-bottom: 20px; }

.stage {
  position: relative;
  height: calc(100dvh - var(--admin-bar-h, 56px) - 92px);
  min-height: 420px;
  border-radius: var(--r-md);
  overflow: hidden;
  box-shadow: var(--shadow-lift);
}
.panel {
  position: absolute;
  left: var(--sp-3);
  top: var(--sp-3);
  bottom: var(--sp-3);
  width: 320px;
  z-index: 3;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  overflow-y: auto;
  padding: var(--sp-3) var(--sp-4) var(--sp-4);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--stone-300) 90%, transparent);
  box-shadow: var(--shadow-pop), var(--bevel-raised);
  backdrop-filter: blur(10px);
  overscroll-behavior: contain;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  position: sticky;
  top: calc(var(--sp-3) * -1);
  margin: calc(var(--sp-3) * -1) calc(var(--sp-4) * -1) 0;
  padding: var(--sp-2) var(--sp-3) var(--sp-2) var(--sp-4);
  background: color-mix(in srgb, var(--stone-300) 94%, transparent);
  border-bottom: 1px solid var(--edge-soft);
}
.panel-title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semi);
  letter-spacing: .04em;
  color: var(--ink-2);
}
.panel-close,
.panel-toggle {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  border: 1px solid var(--edge);
  background: color-mix(in srgb, var(--stone-400) 90%, transparent);
  color: var(--ink-2);
  cursor: pointer;
  backdrop-filter: blur(8px);
}
.panel-close { padding: 4px 6px; border-radius: var(--r-sm); }
.panel-toggle {
  position: absolute;
  left: var(--sp-3);
  top: var(--sp-3);
  z-index: 3;
  padding: 7px 12px;
  border-radius: var(--r-pill);
  font-size: var(--fs-sm);
}
.panel-toggle:hover,
.panel-close:hover { color: var(--ember-hot); border-color: var(--ember); }
@media (max-width: 760px) {
  .stage { height: auto; min-height: 62vh; }
  .panel {
    left: var(--sp-2);
    right: var(--sp-2);
    top: var(--sp-2);
    bottom: auto;
    width: auto;
    max-height: 70%;
  }
  .panel-toggle { left: var(--sp-2); top: var(--sp-2); }
}

.card {
  padding: var(--sp-4);
  background: var(--stone-300);
  border-radius: var(--r-md);
  box-shadow: var(--bevel-raised);
}
.card.dim { opacity: .72; }
.card-title {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin: 0 0 var(--sp-3);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semi);
  color: var(--ink-3);
  letter-spacing: .04em;
}
.field { display: block; margin-bottom: var(--sp-3); }
.lab { display: block; margin-bottom: 4px; font-size: var(--fs-xs); color: var(--ink-3); }
.row2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); }
.toggles { display: flex; flex-direction: column; gap: var(--sp-2); margin-bottom: var(--sp-3); }
.check { display: flex; align-items: center; gap: var(--sp-2); font-size: var(--fs-sm); color: var(--ink-2); cursor: pointer; }
.check input { accent-color: var(--ember); }
.actions { display: flex; flex-wrap: wrap; gap: var(--sp-2); }
.hint { margin: var(--sp-2) 0 0; font-size: var(--fs-xs); color: var(--ink-3); line-height: 1.7; }
.picked { display: flex; align-items: center; gap: var(--sp-2); margin: 0 0 var(--sp-3); font-size: var(--fs-sm); color: var(--ink); }
.mono { font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--ink-3); }
.chips { display: flex; flex-wrap: wrap; gap: var(--sp-2); margin-top: var(--sp-3); }
.chip {
  padding: 3px 10px;
  border-radius: var(--r-pill);
  border: 1px solid var(--edge);
  background: var(--stone-400);
  color: var(--ink-2);
  font-size: var(--fs-xs);
  cursor: pointer;
}
.chip.on { border-color: var(--ember); color: var(--ember-hot); background: var(--ember-veil); }

.table-wrap { overflow-x: auto; background: var(--stone-300); border-radius: var(--r-md); box-shadow: var(--bevel-raised); }

.anchor-warn {
  margin-bottom: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  border-radius: var(--r-md);
  background: var(--warn-veil);
  border: 1px solid var(--warn);
}

.anchor-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  color: var(--warn);
  margin-bottom: var(--sp-1);
}

.anchor-list {
  list-style: none;
  margin: var(--sp-2) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.anchor-list li {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}

.anchor-name {
  background: none;
  border: none;
  padding: 0;
  cursor: pointer;
  color: var(--ink);
  font: inherit;
  text-decoration: underline dotted;
}

.anchor-why { flex: 1; color: var(--ink-3); font-size: var(--fs-xs); }

.place-tools {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4) 0;
}
.place-tools :deep(.inp) { max-width: 320px; }
.place-tools .hint { flex: none; white-space: nowrap; }

.name-cell { display: inline-flex; align-items: center; gap: var(--sp-2); }

.tbl tr { display: table-row; }

.places-tbl { table-layout: fixed; }
.places-tbl .c-name { width: 38%; }
.places-tbl .c-cat { width: 120px; }
.places-tbl .c-xy { width: 150px; }
.places-tbl .c-ref { width: 70px; }
.places-tbl .c-pri { width: 80px; }
.places-tbl td { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.places-tbl .note-cell { white-space: normal; }

@media (max-width: 760px) {
  .places-tbl { table-layout: auto; }
  .places-tbl colgroup { display: none; }
  .places-tbl td { white-space: normal; overflow-wrap: anywhere; }
  .places-tbl td:nth-child(3) { white-space: nowrap; }
  .places-tbl th:nth-child(1), .places-tbl td:nth-child(1) { width: 44%; }
  .places-tbl th:nth-child(4), .places-tbl td:nth-child(4),
  .places-tbl th:nth-child(5), .places-tbl td:nth-child(5),
  .places-tbl th:nth-child(6), .places-tbl td:nth-child(6) { display: none; }
  .place-tools { flex-wrap: wrap; gap: var(--sp-2); }
  .place-tools :deep(.inp) { max-width: none; flex: 1 1 100%; }
}
.place-row { cursor: pointer; transition: background var(--dur-fast) var(--ease); }
.place-row:hover { background: var(--surface-hover); }
.place-row:hover td:first-child { box-shadow: inset 2px 0 0 var(--ember); }

.place-more {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--sp-3);
  padding: var(--sp-3) 0 var(--sp-4);
}

.anchor-bad { color: var(--warn, #cfa334); }
.tbl { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
.tbl.small { font-size: var(--fs-xs); }
.tbl th, .tbl td { text-align: left; padding: var(--sp-2) var(--sp-3); border-bottom: 1px solid var(--edge-soft); }
.tbl th { color: var(--ink-3); font-weight: var(--fw-semi); }
.row { cursor: pointer; }
.row:hover { background: var(--surface-hover); }
.note-cell { max-width: 360px; color: var(--ink-2); }
.err { color: var(--blight-lift); }

.status {
  display: flex;
  flex-direction: column;
  gap: var(--sp-6);
  width: 100%;
  max-width: 1120px;
  margin-inline: auto;
}

.sect {
  margin: 0 0 var(--sp-3);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semi);
  letter-spacing: var(--tracking-ink);
  color: var(--ink-3);
}

.todo-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.todo-list li {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  border-left: 3px solid var(--warn);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  background: var(--warn-veil);
  color: var(--warn);
}
.todo-list li.t-bad {
  border-left-color: var(--blight);
  background: var(--blight-veil);
  color: var(--blight-lift);
}
.todo-text { font-size: var(--fs-sm); font-weight: var(--fw-semi); color: var(--ink); }
.todo-detail {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.todo-quiet { display: flex; align-items: center; gap: var(--sp-2); margin: 0; font-size: var(--fs-sm); color: var(--ink-3); }

.op-list { list-style: none; margin: 0; padding: 0; }
.op { display: flex; align-items: center; gap: var(--sp-4); padding: var(--sp-3) 0; border-top: 1px solid var(--hairline); }
.op:first-child { border-top: none; padding-top: 0; }
.op-main { flex: 1; min-width: 0; }
.op-head { display: flex; align-items: center; gap: var(--sp-2); }
.op-name { font-size: var(--fs-md); font-weight: var(--fw-medium); color: var(--ink); }
.op-result {
  margin: var(--sp-1) 0 0;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  word-break: break-word;
}
.op-bar { margin-top: var(--sp-2); max-width: 420px; }
.cover { margin-top: var(--sp-2); max-width: 520px; display: flex; flex-direction: column; gap: 2px; }
.cover-line { display: flex; align-items: center; gap: var(--sp-2); }
.cover-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-xs);
  color: var(--ink-2);
}
.cover-cell { flex: none; width: 58px; text-align: right; font-family: var(--font-mono); font-size: var(--fs-xs); }
.cover-head .cover-cell { color: var(--ink-4); }
.t-ok { color: var(--ink-3); }
.t-short { color: var(--ember); }
.t-none { color: var(--ink-4); }
.op-fail {
  margin: var(--sp-1) 0 0;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--blight-lift);
  word-break: break-word;
}
.op-actions { flex: none; display: flex; gap: var(--sp-2); }

.diag-zone { display: flex; flex-direction: column; gap: var(--sp-2); }
.diag { border-radius: var(--r-sm); background: var(--stone-300); box-shadow: var(--bevel-raised); }
.diag > summary {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-3) var(--sp-4);
  cursor: pointer;
  list-style: none;
  font-size: var(--fs-sm);
  color: var(--ink-2);
}
.diag > summary::-webkit-details-marker { display: none; }
.diag > summary:hover { background: var(--surface-hover); }
.diag > summary:focus-visible { outline: 2px solid var(--ember); outline-offset: -2px; }
.diag[open] > summary { border-bottom: 1px solid var(--hairline); }
.diag-name { font-weight: var(--fw-medium); color: var(--ink); }
.diag-count {
  padding: 0 6px;
  border-radius: var(--r-xs);
  background: var(--stone-void);
  box-shadow: var(--bevel-inset);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-3);
}
.diag-flat { display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-3) var(--sp-4); cursor: default; }
.ok { color: var(--vital); }
.diag-ok { font-size: var(--fs-xs); color: var(--ink-3); }
.diag-here { font-size: var(--fs-xs); color: var(--ink-3); }
.diag-here b { font-family: var(--font-mono); font-weight: var(--fw-medium); color: var(--ink-2); }
.diag-count.bad { background: var(--blight-veil); box-shadow: none; color: var(--blight-lift); }
.tbl td.no { color: var(--ink-4); }
.caret { color: var(--ink-4); transition: transform .15s var(--ease); }
.diag[open] > summary > .caret { transform: rotate(90deg); }
.diag > .tbl, .diag > .hint, .diag > .facts { margin: var(--sp-3) var(--sp-4) var(--sp-4); }
.diag > .tbl { width: calc(100% - var(--sp-4) * 2); }

.facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: var(--sp-2) var(--sp-4); margin-top: var(--sp-3); }
.facts > div { display: flex; gap: var(--sp-3); align-items: baseline; min-width: 0; }
.facts dt { flex: none; min-width: 78px; font-size: var(--fs-xs); color: var(--ink-3); }
.facts dd { margin: 0; min-width: 0; font-size: var(--fs-xs); color: var(--ink-2); word-break: break-all; }
.facts dd.bad { color: var(--warn); }

@media (max-width: 760px) {
  .op { flex-direction: column; align-items: stretch; gap: var(--sp-2); }
  .op-actions { flex-wrap: wrap; }
  .todo-list li { flex-wrap: wrap; }
  .todo-detail { white-space: normal; }
}

.defaults { max-width: 720px; }
.card-head { display: flex; align-items: baseline; justify-content: space-between; gap: var(--sp-3); }
.chip-n { color: var(--ink-4); font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }
.chip-tag {
  padding: 1px 6px;
  border-radius: var(--r-pill);
  background: var(--stone-500);
  color: var(--ink-3);
  font-size: var(--fs-micro);
}
.mini-actions { display: flex; gap: var(--sp-3); }
.mini {
  background: none;
  border: none;
  padding: 0;
  color: var(--ink-3);
  font-size: var(--fs-xs);
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.mini:hover { color: var(--ember); }

.layer-list {
  list-style: none;
  margin: var(--sp-3) 0 0;
  padding: 0;
  max-height: 46vh;
  overflow-y: auto;
  border-top: 1px solid var(--edge-soft);
}
.layer-row {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 5px 4px;
  border-bottom: 1px solid var(--edge-soft);
  cursor: pointer;
  font-size: var(--fs-sm);
}
.layer-row:hover { background: var(--stone-400); }
.find { display: flex; align-items: center; gap: var(--sp-2); min-width: 260px; }
.find :deep(input) { min-width: 180px; }

.find-hits {
  position: fixed;
  top: calc(var(--admin-bar-h, 56px) + 62px);
  right: var(--sp-5);
  z-index: var(--z-drawer);
  width: min(380px, calc(100vw - var(--sp-5) * 2));
  max-height: 60vh;
  overflow-y: auto;
  padding: var(--sp-3) var(--sp-4);
  border-radius: var(--r-md);
  background: var(--stone-300);
  box-shadow: var(--shadow-pop), var(--bevel-raised);
}
.find-hits-head { display: flex; align-items: baseline; justify-content: space-between; gap: var(--sp-3); }
.hits { list-style: none; margin: var(--sp-2) 0 0; padding: 0; }
.hit {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: 7px 8px;
  border: none;
  border-radius: var(--r-sm);
  background: none;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.hit:hover { background: var(--stone-400); }
.hit-name { display: flex; align-items: center; gap: var(--sp-2); font-size: var(--fs-sm); color: var(--ink); }
.hit-rel { font-size: var(--fs-xs); color: var(--ink-3); }

.layer-icon { width: 18px; height: 18px; object-fit: contain; }
.layer-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.layer-n { color: var(--ink-4); font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }
.layer-actions { display: flex; gap: var(--sp-2); margin-top: var(--sp-3); }

</style>
