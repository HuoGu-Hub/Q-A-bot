<script setup lang="ts">
/**
 * 管理端 · 地图标注。
 *
 * <h2>它要解决的事</h2>
 * wiki 上的点位只有"官方内容"（尖塔、神龛、收集品），而群友真正会问的
 * 「铁矿在哪」「树脂在哪」一个都没有。所以这一页的核心动作只有一个：
 * <b>在地图上点一下，起个名，写句备注。</b>
 *
 * <h2>三种点，三个动作</h2>
 * <ul>
 *   <li>点<b>空白处</b> → 新建本地点位（坐标已经填好，只需要起名）；</li>
 *   <li>点<b>本地点位</b> → 编辑/删除它；</li>
 *   <li>点<b>wiki 点位</b> → 加备注：中文别名、说明、是否可作方位参照、是否隐藏。
 *       备注写在另一张表里，地图同步刷不掉它。</li>
 * </ul>
 *
 * <h2>为什么编辑表单在左侧而不是弹窗</h2>
 * 标注是"边看地图边填"的活：弹窗会挡住地图，而你要反复对照位置。
 * 固定在侧栏就能一边看图一边改字段，而且切点位时表单原地刷新，不用开关弹窗。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
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
import type { AnchorReport, AnchorReview, Catalog, MapSpec, MapStatus, Marker, PlaceInput, Relative } from '@shared/map/types'

/** 与 MapCanvas 的 mode 取值保持一致 —— 用同一个词，省得在中间做一层映射 */
type Mode = 'edit' | 'view'

const tab = ref('mark')
const mode = ref<Mode>('edit')
const loading = ref(true)
const error = ref('')

const catalog = ref<Catalog | null>(null)
const status = ref<MapStatus | null>(null)
const mapName = ref('')
const markers = ref<Marker[]>([])
const canvasIndex = ref(0)

const selected = ref<Marker | null>(null)
const draft = ref<{ x: number; y: number } | null>(null)
/** 方位线。管理端目前不主动画，但组件接口需要它 —— 保留以便后续从列表直接"看方位" */
const highlight = ref<{ toRef: string; relative: Relative } | null>(null)

/** 表单。新建与编辑共用一份，靠 id 区分 */
const form = ref<PlaceInput & { id: number }>(blankForm())
const saving = ref(false)

/** wiki 点位的备注表单 */
const noteForm = ref({
  alias: '', note: '', category: '', landmark: false, priority: 0, hidden: false,
})
const noteSaving = ref(false)

const mapCanvas = ref<InstanceType<typeof MapCanvas> | null>(null)

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
/**
 * 管理端显示的图层。
 *
 * <p>和公开站**用同一份默认**：管理员标注时看到的应该就是访客看到的那张图。
 * 这里若全开、公开站只开几个，就会出现"在访客根本看不见的图层旁边插点位"的情况。
 */
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
const localPlaces = computed(() => markers.value.filter((m) => m.source === 'admin'))

/**
 * 底图变过、需要人复核的点位。
 *
 * <p>坐标不会自己变，变的是"世界坐标落在底图的哪个像素上"。所以它不会报错、
 * 不会消失 —— 只会悄悄指错地方。这个清单就是让那件事**可见**。
 */
const anchor = ref<AnchorReport | null>(null)

function blankForm(): PlaceInput & { id: number } {
  return {
    id: 0, map: mapName.value, page: '', x: 0, y: 0, name: '', aliases: [], category: 'resource',
    note: '', icon: '', color: '', landmark: false, priority: 0, visible: true,
  }
}

/* ==================== 装载 ==================== */

async function boot() {
  loading.value = true
  error.value = ''
  try {
    catalog.value = await adminApi.get<Catalog>('/map/catalog')
    const first = catalog.value.maps[0]
    mapName.value = first ? first.map : ''
    if (mapName.value) await loadMarkers()
    await loadStatus()
    await loadAnchor()
    /*
     * 数据齐了**直接灌一次**图标表，不等 watcher。
     *
     * 实测这里会漏：画布是 v-if 挂载的，图标表在 catalog 到手那一刻就变了，
     * 而 watcher（post flush）跑的时候画布实例可能还没绑上 ref ——
     * 于是 setGroupIcons 一次都没被调用，画布里 groupIcons 是空 Map，
     * 结果不是"图标画不出来"，而是**连一张图标的请求都没发**，地图上只剩空心圈。
     */
    await nextTick()
    mapCanvas.value?.setGroupIcons(groupIcons.value)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function loadMarkers() {
  const r = await adminApi.get<{ markers: Marker[] }>('/map/markers?map=' + encodeURIComponent(mapName.value))
  markers.value = r.markers
  syncVisibleGroups()
  selected.value = null
}

async function loadStatus() {
  status.value = await adminApi.get<MapStatus>('/map/status')
}

async function loadAnchor() {
  try {
    anchor.value = await adminApi.get<AnchorReport>('/map/anchor')
  } catch {
    // 读不到就当没有 —— 它是一个提示，不该让整页打不开
    anchor.value = null
  }
}

/** 跳到某条待复核的点位：换地图 → 换画布 → 选中它，面板自己会展开 */
async function jumpToAnchor(a: AnchorReview) {
  if (a.map !== mapName.value) {
    mapName.value = a.map
    await loadMarkers()
  }
  const idx = (currentMap.value?.canvases ?? []).findIndex((c) => c.page === a.page)
  if (idx >= 0) canvasIndex.value = idx
  await nextTick()
  const m = markers.value.find((x) => x.id === 'place:' + a.id)
  if (m) onPick([m])
}

/** 「位置我看过了，没错」—— 只接受当前底图，不动坐标 */
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

watch(mapName, async (next, prev) => {
  if (!next || next === prev) return
  canvasIndex.value = 0
  await loadMarkers()
})

// 图标表与画布实例都可能在数据之后才就绪，两个都盯着才不会漏掉其中一次。
// 只盯图标表的话，画布晚一步挂载就永远拿不到图标 —— 表现是"地图上只有空心圈，一直不变成图标"
watch([groupIcons, mapCanvas], () => mapCanvas.value?.setGroupIcons(groupIcons.value), { flush: 'post' })

/* ==================== 地图交互 ==================== */

/**
 * 侧栏是否展开。**默认收起**。
 *
 * <p>标注是"偶尔做一次"的活，常驻侧栏等于天天占着四分之一屏幕看一个空表单。
 * 收起之后地图是主角，要标点时才把面板拉出来 —— 和公开站的侧栏同一套逻辑。
 */
const panelOpen = ref(false)

/** 从顶部按钮进来：清空表单，进入待落点状态 */
function startNewPlace() {
  selected.value = null
  draft.value = null
  form.value = { ...blankForm(), map: mapName.value, page: currentCanvas.value?.page ?? '' }
  noteForm.value = { alias: '', note: '', category: '', landmark: false, priority: 0, hidden: false }
  panelOpen.value = true
  mode.value = 'edit'
}

/**
 * 面板里该显示哪个表单。
 *
 * <p>三种互斥的情况，**不能同时摆出来**：
 * <ul>
 *   <li>{@code new} —— 新增点位模式：只给"新增点位"表单；</li>
 *   <li>{@code place} —— 选中了自定义点位：给它的编辑表单；</li>
 *   <li>{@code note} —— 选中了 wiki 点位：给备注表单。</li>
 * </ul>
 * 以前三张卡片一直摆着，想改一个已有点位时还得先在心里跳过那张"新增"表 ——
 * 那不是并列的功能，是同一个位置的不同状态。
 */
const panelKind = computed<'new' | 'place' | 'note' | null>(() => {
  if (mode.value === 'edit') return 'new'
  if (!selected.value) return null
  return selected.value.source === 'admin' ? 'place' : 'note'
})

/** 收起面板：顺手退回浏览模式，免得收起后点空白还在悄悄落点 */
function closePanel() {
  panelOpen.value = false
  mode.value = 'view'
  selected.value = null
  draft.value = null
}

/**
 * 选中点位。画布给的是**数组**（同一处可能压着好几个），管理端只关心最近的那个。
 *
 * <p>管理端不做堆叠展开：标注时你要改的是一个确定的点位，
 * 把重合的几个摊开反而要你多选一次。
 */
function onPick(list: Marker[]) {
  const m = list[0] ?? null
  if (!m) {
    selected.value = null
    return
  }
  selected.value = m
  highlight.value = null
  // 点到点位就把面板拉出来：这时候用户要看/改的就是它
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

/** 点空白处：进入"待落点"状态，坐标已定，只差起名 */
function onPickedPoint(x: number, y: number) {
  draft.value = { x, y }
  form.value = { ...blankForm(), map: mapName.value, page: currentCanvas.value?.page ?? '', x, y }
  selected.value = null
  panelOpen.value = true
}

/** 从列表跳到一个点位：先切到它所在的画布，再选中它 */
function jumpTo(m: Marker) {
  const list = currentMap.value?.canvases ?? []
  const idx = list.findIndex((c) => c.page === m.canvas)
  if (idx >= 0) canvasIndex.value = idx
  tab.value = 'mark'
  onPick([m])
  mapCanvas.value?.focus(m.x, m.y, Math.max(mapCanvas.value?.scale() ?? 0.5, 0.6))
}

/* ==================== 保存 ==================== */

async function savePlace() {
  if (saving.value) return
  if (!form.value.name.trim()) {
    toast('地点名不能为空', 'warn')
    return
  }
  saving.value = true
  try {
    const body = { ...form.value }
    // 保存前先抓一份"它原来在哪"：保存后待复核清单会刷新，那份就没了
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

/**
 * 把刚修正好的那个点当作基准，问一句"其余待复核的点要不要一起搬"。
 *
 * <p>新旧坐标之间的对应关系**只有人知道**，而人已经用一个点表达过了 ——
 * 就是他把它从哪拖到了哪。剩下的点照搬这个偏移即可，不用逐个再拖一遍。
 */
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
    // markerId 就是 id 里 # 之后那一段
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

/** 备注表的主键是 (map, page, markerId)，而点位 id 是 "page#markerId" —— 拆回来 */
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

/* ==================== 长任务 ==================== */

async function runSync() {
  try {
    await adminApi.post('/map/sync')
    toast('同步已开始，稍后看状态')
    await pollStatus()
    // 首次同步之前这一页是空的；同步完自动把地图目录拉回来，
    // 省得管理员还要自己找「重新载入」——那正是他刚做完一件长任务之后最不想找的东西
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

// 全量预热比轻量多拉原图档，第一次要十几分钟 —— 出站请求是串行节流的，
// 建议放在非高峰点，或者交给后端凌晨那个定时任务
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
  try {
    await adminApi.post('/map/corpus?purge=' + (purge ? 'true' : 'false'))
    toast(purge ? '清除已开始' : '语料派生已开始')
    await pollStatus()
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

/** 任务是在后台跑的，状态得回来问 —— 问几次就够，避免把页面变成轮询器 */
async function pollStatus() {
  for (let i = 0; i < 20; i++) {
    await new Promise((r) => setTimeout(r, 1500))
    try {
      await loadStatus()
    } catch { /* 状态查失败不该打断轮询本身 */ }
    const jobs = status.value?.jobs ?? {}
    const running = Object.values(jobs).some((j) => j.running)
    if (!running) break            // ⚠️ 不能 return：下面那步刷新就永远跑不到
  }
  // 同步可能把画布定义换掉了 —— 任务跑完顺手刷新一次待复核清单
  await loadAnchor()
}

function fmtBytes(n: number): string {
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(0) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}

function fmt(n: number): string {
  return Math.round(n).toLocaleString('zh-CN')
}

/* ==================== 默认显示的分组 ====================
 *
 * 公开地图页首屏开哪些分组，由这里决定。
 * 为什么放在后台而不是写死在前端：余烬谷有 43 个分组、一千多个点位，
 * 全开就是密密麻麻一片；该开哪几个是**审美与用途**的判断，
 * 只有管理员说得准，而写死意味着改一次默认值就要重新发一版。
 */

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

/**
 * 加载失败的图标。
 *
 * <p>图标要走 wiki 取，取不到时接口返回 404 —— 浏览器会画一个"破图"占位。
 * 四十多个分组排下来就是一列破图框，比不显示图标难看得多，所以失败的直接不渲染。
 */
const layerIconFailed = ref<Set<string>>(new Set())

function onLayerIconError(name: string) {
  const next = new Set(layerIconFailed.value)
  next.add(name)
  layerIconFailed.value = next
}

/** 当前地图的图标地址。图标挂在 /api/public 下，管理端不需要额外鉴权 */
function layerIconUrl(name: string): string {
  return '/api/public/map/tile?map=' + encodeURIComponent(layerMap.value)
    + '&name=' + encodeURIComponent(name)
}

/** 把已保存的选择读进勾选状态 */
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

/** 打开弹窗就重新拉一次：刚同步过的话，分组清单可能已经变了 */
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

/** @param keys null = 恢复"未配置"，与"全关"是两件事 */
async function saveLayers(keys: string[] | null) {
  const cur = layerCurrent.value
  if (!cur) return
  layerSaving.value = true
  try {
    await adminApi.put('/map/layers', { map: cur.map, groups: keys })
    toast(keys === null ? '已恢复为未配置' : '已保存，公开地图页下次打开就按这个显示')
    await loadLayers()
    /*
     * 重拉目录，再按新的默认图层刷新。
     *
     * 顺序很关键：syncVisibleGroups() 读的是 catalog 里的 defaultGroups，
     * 不先重拉的话它读到的还是保存前的旧值 ——
     * 表现就是"保存了但地图没变，还得手动刷新一次"。
     */
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
        { key: 'places', label: '点位列表', count: localPlaces.length },
        { key: 'status', label: '同步与状态' },
      ]" />
    </template>
    <template #actions>
      <Button size="sm" @click="openDefaults">
        <Icon name="display" :size="14" />设置默认显示
      </Button>
      <Button size="sm" @click="startNewPlace">
        <Icon name="plus" :size="14" />新增点位
      </Button>
      <Button size="sm" @click="boot"><Icon name="refresh" :size="14" />重新载入</Button>
    </template>

    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="loading" class="muted">正在载入…</p>

    <template v-else>
      <!-- ==================== 标注 ==================== -->
      <div v-show="tab === 'mark'">
        <!--
          ⚠️ 空态**只挡住标注这一页**，不能挡住整个页面。
          上一版把空态写成整页的 v-else，于是"还没有地图数据"时连「同步与状态」也一起消失 ——
          而那个页正是唯一能把地图数据弄出来的地方。管理员看到的是一个死循环：
          提示他去点同步，但同步按钮不见了。
        -->
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

          <!-- 收起状态：只剩一颗按钮，地图满宽 -->
          <button v-if="!panelOpen" type="button" class="panel-toggle" @click="startNewPlace">
            <Icon name="plus" :size="16" />
            新增点位
          </button>

          <aside v-show="panelOpen" id="mark-panel" class="panel" aria-label="点位编辑">
            <div class="panel-head">
              <span class="panel-title">
                {{ form.id > 0 ? '编辑点位' : (selected?.source === 'wiki' ? 'wiki 点位备注' : '新增点位') }}
              </span>
              <button type="button" class="panel-close" aria-label="收起面板" @click="closePanel">
                <Icon name="close" :size="15" />
              </button>
            </div>

          <!-- 新建 / 编辑本地点位 -->
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

          <!-- wiki 点位备注：只有选中 wiki 点位时才出现 -->
          <div v-else-if="panelKind === 'note'" class="card">
            <h2 class="card-title">wiki 点位备注</h2>
            <!-- v-if="selected" 不只是防御：它让 TS 在模板里把 selected 收窄成非空 -->
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

      <!-- ==================== 点位列表 ==================== -->
      <div v-show="tab === 'places'" class="table-wrap">
        <!--
          底图变了导致点位可能整体偏移。
          **坐标不会变、也不会报错**，只会悄悄指错地方 —— 所以这里必须主动喊一声。
        -->
        <div v-if="anchor && anchor.pending > 0" class="anchor-warn">
          <div class="anchor-head">
            <Icon name="alert" :size="16" />
            <strong>底图变了，{{ anchor.pending }} 个点位要看一眼</strong>
          </div>
          <p class="hint">
            它们的坐标没动，但所在底图的取景窗口或切图方式变了 —— 落点可能已经整体偏移。
            点名字跳过去，把点拖到正确位置后保存；确认位置没错就点「位置无误」。
          </p>
          <ul class="anchor-list">
            <li v-for="a in anchor.items" :key="a.id">
              <button type="button" class="anchor-name" @click="jumpToAnchor(a)">{{ a.name }}</button>
              <span class="anchor-why">{{ a.reason }}</span>
              <Button size="sm" @click="confirmAnchor(a.id)">位置无误</Button>
            </li>
          </ul>
        </div>

        <table class="tbl">
          <thead>
            <tr>
              <th>名称</th><th>分类</th><th>坐标</th><th>备注</th><th>参照</th><th>优先级</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in localPlaces" :key="p.id" class="row" @click="jumpTo(p)">
              <td>{{ p.name }}</td>
              <td>{{ p.category }}</td>
              <td class="mono">{{ fmt(p.x) }}, {{ fmt(p.y) }}</td>
              <td class="note-cell">{{ p.note }}</td>
              <td>{{ p.landmark ? '是' : '' }}</td>
              <td>{{ p.priority }}</td>
            </tr>
            <tr v-if="!localPlaces.length"><td colspan="6" class="muted">还没有本地点位。到「标注」页在地图上点一下即可新增。</td></tr>
          </tbody>
        </table>
      </div>

      <!-- ==================== 状态 ==================== -->
      <!-- ==================== 默认显示（弹窗） ====================
           从独立的标签页搬到了这里：它改的是"公开站默认显示什么"，
           和标注是同一件事的两面，分开两页只会让人来回切。 -->
      <Modal
        :open="defaultsOpen"
        title="设置默认显示"
        width="720px"
        @close="defaultsOpen = false"
      >
        <!-- 动作放标题栏：分组有 43 条，放内容底部就得滚到底才点得到保存 -->
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
        <p class="hint">
          公开地图页首屏默认打开哪些分组。分组多的时候全开会密密麻麻一片，
          所以只有在这里挑过的才会显示；新同步进来的分组默认是关着的，不会自己冒出来。
        </p>

        <p v-if="layerLoading" class="muted">正在载入…</p>

        <template v-else-if="layerMaps.length">
          <div class="chips">
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
                这张地图<strong>还没配置过</strong>，公开页当前用的是内置经验值（非收集品全开）。
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
        <div class="card">
          <h2 class="card-title">数据</h2>
          <p class="kv"><span>存储</span><code>{{ status?.directory }}</code></p>
          <p class="kv"><span>地图库</span>{{ status?.mapStoreAvailable ? '可用' : '不可用' }}</p>
          <p class="kv"><span>点位存储</span>{{ status?.placesAvailable ? '可用' : '不可用' }}</p>
          <p class="kv"><span>备注存储</span>{{ status?.notesAvailable ? '可用' : '不可用' }}</p>
          <!-- 底图变了导致点位可能偏移 —— 这个数必须是零，否则就是有活要干 -->
          <p class="kv">
            <span>待复核点位</span>
            <span v-if="status && status.anchorPending > 0" class="anchor-bad">
              {{ status.anchorPending }} / {{ status.placeTotal }} 条（底图变过）
            </span>
            <span v-else>无</span>
          </p>
          <p class="kv">
            <span>瓦片缓存</span>
            <template v-if="status">
              {{ status.tileCache.enabled ? '启用' : '关闭' }} ·
              {{ status.tileCache.files }} 个文件 · {{ fmtBytes(status.tileCache.bytes) }}
            </template>
          </p>
          <p class="kv"><span>目录</span><code>{{ status?.tileCache.root }}</code></p>
        </div>

        <div class="card">
          <h2 class="card-title">长任务</h2>
          <div v-for="j in Object.values(status?.jobs ?? {})" :key="j.name" class="job">
            <div class="job-head">
              <strong>{{ j.label }}</strong>
              <Tag :tone="j.running ? 'warn' : j.ok ? 'good' : 'bad'">
                {{ j.running ? '运行中' : j.ok ? '成功' : '失败' }}
              </Tag>
            </div>
            <p class="hint">{{ j.summary || '尚未运行过' }}</p>
            <p v-if="j.finishedAt" class="hint mono">完成于 {{ j.finishedAt }}</p>
          </div>
          <div class="actions">
            <Button variant="primary" size="sm" @click="runSync">
              <Icon name="refresh" :size="14" />同步 wiki 地图
            </Button>
            <Button size="sm" @click="runWarm">预热底图缓存</Button>
            <Button size="sm" @click="runWarmFull">全量预热</Button>
            <Button size="sm" @click="runCorpus(false)">派生地点语料</Button>
            <Button size="sm" @click="runCorpus(true)">清除地点语料</Button>
          </div>
          <p class="hint">
            同步是增量的：只拉版本变过的页，常态下几秒完成。发现内容变化后会自动补一次首屏档预热。
            底图预热要拉几百张瓦片，出站请求是节流的，第一次大约一两分钟 —— 做过一次之后
            所有访客都命中本地缓存。全量预热会多拉原图档，第一次十几分钟，建议放在非高峰；
            后端凌晨 4 点也会自动跑一次。地点语料要调向量接口，也慢。
            这些都在后台跑，进度看上面。
          </p>
        </div>

        <div class="card">
          <h2 class="card-title">画布</h2>
          <table class="tbl small">
            <thead><tr><th>地图</th><th>页面</th><th>底图</th><th>可渲染</th><th>尺寸</th><th>同步时间</th></tr></thead>
            <tbody>
              <tr v-for="c in status?.canvases ?? []" :key="c.page">
                <td>{{ c.map }}</td>
                <td class="mono">{{ c.page }}</td>
                <td>{{ c.tiles ? c.tiles + ' 瓦片' : c.background || '—' }}</td>
                <td>{{ c.renderable ? '是' : '否' }}</td>
                <td class="mono">{{ fmt(c.extent[0]) }} × {{ fmt(c.extent[1]) }}</td>
                <td class="mono">{{ c.syncedAt?.slice(0, 19) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="card">
          <h2 class="card-title">同步页</h2>
          <table class="tbl small">
            <thead><tr><th>页面</th><th>revid</th><th>点位</th><th>错误</th></tr></thead>
            <tbody>
              <tr v-for="p in status?.pages ?? []" :key="p.page">
                <td class="mono">{{ p.page }}</td>
                <td class="mono">{{ p.revid }}</td>
                <td>{{ p.markerCount }}</td>
                <td class="err">{{ p.error }}</td>
              </tr>
            </tbody>
          </table>
        </div>
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

/* ==================== 舞台与悬浮面板 ====================
   和公开站同一套：地图居中占满，侧栏浮在它上面，收起后只剩一颗按钮。 */
/*
 * 地图要贴到接近视口底部。.page 默认 76px 的页脚留白会把地图往上顶，留出一条空带。
 *
 * ⚠️ 这里**不能写 :deep(.page)**：AdminPage 的根元素本身就带本组件的 scope 标记，
 * 而 :deep() 编译成的是**后代**选择器，永远匹配不到它自己 ——
 * 表现为"改了内边距但页面纹丝不动、还多出 56px 滚动"。直接写 .page 才是对的。
 */
.page { padding-bottom: 20px; }

.stage {
  position: relative;
  /*
   * 高度 = 视口 - 顶端导航 - 其余外壳。
   * 顶端导航那 56 由变量给出；剩下的 92 = 页面栏 56 + 上下两处 gap 16 + 底部留白 20。
   *
   * 底部留白本来是 .page 的 76px 页脚内边距，会把地图往上顶、下面空出一条带子，
   * 整体看着像一根长条 —— 所以这里把 .page 的内边距压到 20px（见下面的 :deep(.page)）。
   * 这个数只能量、不能推：实测 100dvh 解析出来比 innerHeight 小几像素，宁可让地图短几像素。
   */
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
  /* 比公开站实一点：这里是表单，读字比看地图重要 */
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

/* ---------- 待复核点位：底图变了导致点位可能偏移 ---------- */

.anchor-warn {
  margin-bottom: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  border-radius: var(--r-md);
  background: var(--warn-bg, rgba(207, 163, 52, 0.12));
  border: 1px solid var(--warn, #cfa334);
}

.anchor-head {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--warn, #cfa334);
  margin-bottom: 4px;
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

.anchor-why { flex: 1; color: var(--ink-dim); font-size: 12px; }

.anchor-bad { color: var(--warn, #cfa334); }
.tbl { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
.tbl.small { font-size: var(--fs-xs); }
.tbl th, .tbl td { text-align: left; padding: var(--sp-2) var(--sp-3); border-bottom: 1px solid var(--edge-soft); }
.tbl th { color: var(--ink-3); font-weight: var(--fw-semi); }
.row { cursor: pointer; }
.row:hover { background: var(--surface-hover); }
.note-cell { max-width: 360px; color: var(--ink-2); }
.err { color: var(--blight-lift); }

.status { display: grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); gap: var(--sp-4); align-items: start; }

/* ==================== 默认显示 ==================== */
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

/*
 * 分组清单：单列、每行一个开关。
 * 用两列的话 43 个分组会横向跳着读，而"哪些开了"要能一眼扫完。
 */
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
.layer-icon { width: 18px; height: 18px; object-fit: contain; }
.layer-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.layer-n { color: var(--ink-4); font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }
.layer-actions { display: flex; gap: var(--sp-2); margin-top: var(--sp-3); }
.kv { display: flex; gap: var(--sp-3); margin: 0 0 var(--sp-2); font-size: var(--fs-sm); color: var(--ink-2); }
.kv span { min-width: 84px; color: var(--ink-3); }
.kv code { font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--ink-3); word-break: break-all; }
.job + .job { margin-top: var(--sp-3); padding-top: var(--sp-3); border-top: 1px solid var(--edge-soft); }
.job-head { display: flex; align-items: center; gap: var(--sp-2); }
</style>
