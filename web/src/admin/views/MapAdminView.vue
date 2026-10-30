<script setup lang="ts">
/**
 * 管理端 · 地图。
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

/** 与 MapCanvas 的 mode 取值保持一致 —— 用同一个词，省得在中间做一层映射 */
type Mode = 'edit' | 'view'

const tab = ref('mark')
/*
 * 初始必须是 view。
 *
 * 写成 edit 的话，一进页面就已经是"新增点位模式"了 —— 而那个模式会关掉点位的点击，
 * 于是刷新之后点任何图标都没反应，看起来像"编辑功能坏了"。
 * 进这个模式只有一个入口：点右上角的「新增点位」。
 */
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

/* ==================== 找点位 ====================
 * 复用公开站那一套（同一个 /map/locate 接口、同一份命中结构）：
 * 标注时"我要改某个点"和浏览时"我要找某个点"是同一件事，
 * 各写一套搜索只会两处结果不一致。
 */
const findQuery = ref('')
const finding = ref(false)
const found = ref<LocateResult | null>(null)

async function findPoint() {
  const q = findQuery.value.trim()
  if (!q || finding.value) return
  finding.value = true
  try {
    // 这个接口在公开侧，管理端直接调即可 —— 它本来就不需要登录
    found.value = await publicApi.get<LocateResult>('/map/locate?q=' + encodeURIComponent(q))
  } catch (e) {
    error.value = describeError(e)
  } finally {
    finding.value = false
  }
}

/**
 * 跳到一条命中。
 *
 * <p>先在本地点位表里把它认出来 —— 认得出来就走 {@link jumpTo}，
 * 那样才会切画布、选中并**带出编辑面板**；认不出来就只把视野挪过去。
 */
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
/**
 * 点位列表的数据源 = **当前地图的全部点位**，不只是本地补充点。
 *
 * <p>上一版只列 source === 'admin' 的点，而这个库里一条本地补充都没有：
 * 地图上画着 1064 个 wiki 点位，列表却是空的 —— 名字叫「点位列表」，
 * 读起来就是"地图上这些点的清单"，空着只会让人以为数据丢了。
 */
const mapPlaces = computed(() => markers.value)

/** 列表筛选：名字、分类、备注都能命中 */
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
/** 一次最多渲染 200 行：1064 行全铺进 DOM 会拖慢整页，而这一页刚做过加载优化 */
const shownPlaces = computed(() => filteredPlaces.value.slice(0, placeLimit.value))
watch(placeKw, () => { placeLimit.value = PLACE_STEP })

/**
 * 底图变过、需要人复核的点位。
 *
 * <p>坐标不会自己变，变的是"世界坐标落在底图的哪个像素上"。所以它不会报错、
 * 不会消失 —— 只会悄悄指错地方。这个清单就是让那件事**可见**。
 */
const anchor = ref<AnchorReport | null>(null)

/**
 * 候选地图列表。
 *
 * <p>同步**不筛地图**（命名空间里有什么就拉什么，谁也不丢），用哪几张在这里定。
 * 这样 wiki 哪天换了新地图，它会自己出现在列表里等人决定 —— 而不是被静默跳过。
 */
const mapList = ref<MapCandidates | null>(null)

/* ==================== 同步与状态：这一页的叙事 ==================== */

/**
 * 报头里的"当前世界" —— 目录按点位数排序，取第一张。
 *
 * <p>这正是公开站实际显示的那张（{@code MapView.vue} 取 {@code maps[0]}），
 * 而目录已经按启用范围过滤过，所以这里说的就是访客看到的东西。
 */
const primarySpec = computed(() => catalog.value?.maps?.[0] ?? null)

/** 现在对外显示的到底哪一张 —— 候选表标题行用它，省掉一整块报头 */
const displayMap = computed(() =>
  primarySpec.value?.map ?? mapList.value?.candidates?.find((c) => c.active)?.map ?? '—'
)

/**
 * 某张地图的底图情况 —— 候选表的「底图 / 可渲染」两列。
 *
 * <p>这两列原本在下面那张「画布」表里，10 行里 5 行永远不可渲染、3 行是 {@code Tabs/*}
 * 指向同一张底图的重复页，真正在用的只有 2 行。而它要回答的问题——
 * "这张图能不能渲染"——恰恰是**决定要不要启用它**时的判断材料，
 * 所以并到候选表里，而不是单独占一张表。
 */
function canvasOf(map: string): { label: string; renderable: boolean } {
  const list = (status.value?.canvases ?? []).filter((c) => c.map === map)
  const ok = list.filter((c) => c.renderable)
  if (ok.length) {
    const tiles = ok.reduce((n, c) => n + (c.tiles || 0), 0)
    return { label: ok.length + ' 张 · ' + fmt(tiles) + ' 瓦片', renderable: true }
  }
  return { label: list.find((c) => c.background)?.background || '—', renderable: false }
}

/** 解析失败的页。同步页那张表只在有它们时才值得展开 */
const failedPages = computed(() => (status.value?.pages ?? []).filter((p) => p.error))

/**
 * 长任务**按任务**组织，而不是按按钮。
 *
 * <p>上一版把任务状态放在卡片上半、把五个按钮放在下半，中间夹一段说明文章 ——
 * 于是"这个按钮是干什么的、上次跑成什么样"要在屏幕上跳两次才连得起来。
 * 一个任务的状态与它的触发方式必须待在一起。
 */
/**
 * 每个任务在**库/磁盘上**留下了什么。
 *
 * <p>这是这一块的主角：任务是本进程的状态，重启之后就"没跑过"，而数据一直在库里。
 * 重启后想知道"要不要重新同步 / 重新预热"，看的是这一行，不是任务状态。
 */
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

/**
 * 分单位覆盖：行 = 画布（含分组图标），列 = 档位。
 *
 * <p>列顺序取自后端返回的顺序（后端按档位从细到粗发），前端不猜 —— 档位是配置项，
 * 写死五列迟早会和配置对不上。
 */
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

/** 补齐的格子要静，缺的格子要响 —— 一屏里只有缺的那几格该被眼睛抓到 */
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
  // 一行 = 一个数据对象 + 它的现状 + 触发方式。每个按钮花多少钱写在按钮自己的 title 里 ——
  // 那是看过一次就记住的东西，不该天天占着页面
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

/**
 * 任务的标签。**「成功」与「没跑过」都不给标签** ——
 * 它们回答的是"本进程跑过没有"，而这一页要回答的是"库里现在有什么"，那由数据行说。
 * 标签只留给两种情况：正在跑，以及真的失败了（后者要人去看一眼）。
 */
function jobTag(j?: JobState): { tone: 'warn' | 'bad'; text: string } | null {
  if (!j) return null
  if (j.running) return { tone: 'warn', text: '运行中' }
  if (j.finishedAt && !j.ok) return { tone: 'bad', text: '失败' }
  return null
}

/**
 * 清空同步进来的地图数据。
 *
 * <p>确认框里必须写清**删什么、不删什么、代价是什么**：这是一个点错了就恢复不了的动作，
 * 而其中"不删你自己建的点位、备注、图层配置"才是让人敢按下去的那一句。
 * 它和上面三个任务一样有回执 —— 删了多少页/点位/瓦片，看「地图数据」那一行。
 */
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

/**
 * 待办 —— 这一页唯一配得上暖色的东西。
 *
 * <p>没有待办时整块不出现，整页就只剩灰阶：**"没有颜色"本身就是"没事"的信号**。
 * 项目的设计令牌里写着"灵火一屏之内只给最该先看一眼的东西"，这里就是那句话的落点。
 */
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
  // wiki 上出现了点数更多的地图而当前用的不是它 —— 这正是"改名换图"落地时的样子
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

/** 展开一块诊断并把它滚进视野。长页面里瞬移会迷路，但动效要克制且尊重 reduced-motion */
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

/**
 * ISO 瞬时 → "刚刚 / 12 分钟前 / 3 小时前 / 10-14 17:37"。
 *
 * <p>这一页问的是"数据还新不新"，先回答"多久以前"；精确时刻留在 title 里。
 */
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

/** ISO 瞬时 → 本地 MM-DD HH:mm。管理端看的是"什么时候跑完的"，时区不该由读者换算 */
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

/* ==================== 装载 ==================== */

async function boot() {
  loading.value = true
  error.value = ''
  try {
    // 画地图只需要目录 + 点位。状态、待复核、候选地图都是「补信息」，
    // 让它们挡在地图的 loading 后面，等于每次打开都要多等一次 status 的往返。
    catalog.value = await adminApi.get<Catalog>('/map/catalog')
    const first = catalog.value.maps[0]
    mapName.value = first ? first.map : ''
    if (mapName.value) await loadMarkers()
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
  // 地图已经能看了，剩下的状态栏在后台补 —— 不 await，慢的只有它自己
  void loadExtras()
}

/**
 * 状态栏的三份数据：运行状态、待复核点位、候选地图。
 *
 * <p>并发拉取，且互不影响：任何一份失败只让对应的面板空着，不把整页打回错误态。
 * 这三份都不参与首屏绘制，所以放在 {@link boot} 的 loading 之外。
 */
async function loadExtras() {
  await Promise.allSettled([loadStatus(), loadAnchor(), loadMaps()])
}

async function loadMarkers() {
  const r = await adminApi.get<{ markers: Marker[] }>('/map/markers?map=' + encodeURIComponent(mapName.value))
  markers.value = r.markers
  syncVisibleGroups()
  selected.value = null
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
    // 启用状态一变，目录/图层/问答全都跟着变 —— 重新拉一遍
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
    /*
     * 点到空白处 = "我不改这个了"。
     *
     * 这里以前只清了 selected、**没关面板**，于是 panelKind 变成 null：
     * 面板还开着，但里面一张卡片都不渲染，标题又退回"新增点位" ——
     * 看到的就是一块空白、标题写着新增、点 × 也说不清该不该关的面板。
     * 收起面板（顺带退回浏览模式）才是"点空白处"该有的结果。
     */
    selected.value = null
    panelOpen.value = false
    mode.value = 'view'
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
  // 清除不可撤销：派生块一删，地点问答立刻答不出来，只能重新调向量接口再派一遍
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
  // 同步可能把画布定义换掉了，也可能带来了新地图 —— 两样都刷新一次
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
  return '/api/public/v1/v1/map/tile?map=' + encodeURIComponent(layerMap.value)
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

    <!-- 命中列表：浮在页头下面。点一条就跳过去并带出它的编辑面板 -->
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
              <!-- 标题跟着 panelKind 走，和"显示哪张卡片"用同一个判据 ——
                   各写一套的话，两边一旦不一致就会出现"标题说新增、内容是空的" -->
              <span class="panel-title">
                {{ panelKind === 'note' ? 'wiki 点位备注'
                  : panelKind === 'place' ? '编辑点位' : '新增点位' }}
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

      <!-- ==================== 点位列表 ====================
           ⚠️ 用 v-if 而不是 v-show：这张表现在是全图点位（四位数），
           v-show 会在打开页面时就把它渲染进 DOM，等于把刚省下的加载时间又还回去。
           只在切到这个 tab 时才挂载。 -->
      <div v-if="tab === 'places'" class="table-wrap">
        <!--
          底图变了导致点位可能整体偏移。
          **坐标不会变、也不会报错**，只会悄悄指错地方 —— 所以这里必须主动喊一声。
        -->
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
          <!-- 列宽：名称给 38%，其余按内容定宽，备注吃掉剩余 —— 否则名称列会撑到 800px，
               分类被推到屏幕右侧，看起来像两组互不相干的字段 -->
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
            <!--
              ⚠️ 类名不能叫 row：base.css 里的 .row 是 display:flex，
              落在 <tr> 上会把表格行拆成 flex 容器 —— 单元格不再对齐表头，
              实测表头在右边、值在左边。这里用 place-row。
            -->
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
        <p v-if="layerLoading" class="muted">正在载入…</p>

        <template v-else-if="layerMaps.length">
          <!--
            只有一张地图时不渲染切换条：一颗孤零零的 chip 既点不动也占地方，
            而它的存在只是因为"当年同步把 wiki 上 6 张地图全拉进来了"。
            见 MapSync.maps（白名单）与 MapResidueCleaner（清存量）。
          -->
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



        <!-- ══════════ 待办 ══════════
             只在该出现时出现。它是全页唯一配用暖色的东西 —— 没有它时整页是灰阶，
             而"没有颜色"本身就是"没事"的信号。 -->
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

        <!-- ══════════ 用哪几张地图 ══════════
             标题行直接给出"现在显示哪张"，展开才是可切换的列表 ——
             所以不需要再来一块报头说同一件事。
             wiki 换了新图时这里会自己多出一行，这是整套设计的落点。 -->
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

        <!-- ══════════ 长任务 ══════════
             按**任务**组织：一个任务的状态与它的触发方式必须待在一起。 -->
        <section class="ops">
          <h3 class="sect">任务</h3>
          <ul class="op-list">
            <li v-for="row in jobRows" :key="row.key" class="op">
              <div class="op-main">
                <div class="op-head">
                  <span class="op-name">{{ row.label }}</span>
                  <Tag v-if="row.tag" :tone="row.tag.tone">{{ row.tag.text }}</Tag>
                </div>

                <!-- 跑着：进度条就是这一行的状态，数字由它自己带 -->
                <Bar
                  v-if="row.state?.running && row.state.total > 0"
                  class="op-bar"
                  :label="'已处理 ' + row.state.done + ' / ' + row.state.total"
                  :value="row.state.done"
                  :max="row.state.total"
                  :show-value="false"
                />

                <!-- 主语是**库里现在有什么**（跨重启），不是"任务跑没跑过"（只属于本进程） -->
                <p v-if="row.facts" class="op-result">{{ row.facts }}</p>

                <!-- 分单位覆盖：出图要的是**某一档**拉齐，总文件数看不出缺在哪一格 -->
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
                <!-- 失败才多说一句：为什么失败、缺了多少 —— 那是唯一需要人去处理的情况 -->
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

        <!-- ══════════ 诊断 ══════════
             日常不用看。以前三块并排常驻展开，把页面后 2/3 变成两张几十行的表。 -->
        <section class="diag-zone">
          <h3 class="sect">诊断</h3>

          <details ref="healthDetails" class="diag">
            <summary>
              <Icon name="chevron-right" :size="14" class="caret" />
              <span class="diag-name">存储与路径</span>
            </summary>
            <dl class="facts">
              <!-- 这里**故意不列**"地图库/点位/备注 可用吗"：不可用时顶部的「待办」
                   已经点名喊过了，列在这里只在一切正常时出现 —— 那时它是噪声 -->
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

          <!--
            同步页：正常时**只有一行**。
            以前它是 12 行 × 4 列的常驻表，而健康状态下整张表的信息量是一个 bit（"0 错误"）——
            12 页里有 7 页是 0 点位的容器页，列出来只是占地方。
            有失败页时才变成可展开的表，而且**只列失败的那几页**：
            失败页故意不推进 revid，下一轮同步会自动重试它，这里要回答的是"哪几页、为什么"。
          -->
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

          <!--
            这里原本还有一张「画布」表：10 行里 5 行永远不可渲染，3 行是 Tabs/* 指向同一张
            底图的重复页，真正在用的只有 2 行。它要回答的"这张图能不能渲染、底图是什么"
            是**决定要不要启用一张新地图**时的判断材料 —— 所以那两列已经并进上面的「地图」表，
            这块独立表格没有继续存在的理由。
          -->
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

/* 点位列表：筛选条与「继续显示」
   1064 个点位一次铺开会把这一页拖慢，所以默认只渲染 200 行，靠筛选缩小范围 */
.place-tools {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4) 0;
}
.place-tools :deep(.inp) { max-width: 320px; }
.place-tools .hint { flex: none; white-space: nowrap; }

.name-cell { display: inline-flex; align-items: center; gap: var(--sp-2); }

/* 手写表格：行必须是 table-row。即使调用方误写了别的类，也不能把表格拆掉 */
.tbl tr { display: table-row; }

/* 点位列表的列宽。名称 38%、备注吃剩余，其余定宽 */
.places-tbl { table-layout: fixed; }
.places-tbl .c-name { width: 38%; }
.places-tbl .c-cat { width: 120px; }
.places-tbl .c-xy { width: 150px; }
.places-tbl .c-ref { width: 70px; }
.places-tbl .c-pri { width: 80px; }
.places-tbl td { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.places-tbl .note-cell { white-space: normal; }

/* 手机上六列必然放不下：固定列宽会按比例压缩，名称列被压到 40px、表头竖排。
   这里改回自动布局，只留名称 / 分类 / 坐标三列，名称拿 42%。 */
@media (max-width: 760px) {
  /* 手机上把 colgroup 整个去掉：留着它，被隐藏的列仍会按 col 宽度占轨道，
     实测表格被撑到 726px，名称之外的列全跑到屏幕外。 */
  .places-tbl { table-layout: auto; }
  .places-tbl colgroup { display: none; }
  /* ⚠️ 必须允许换行：桌面上为了单行整洁给 td 设了 nowrap，
     自动布局下 nowrap 会把「最长那个名字」当成列的最小宽度 ——
     实测有一行名字把名称列撑到 527px，整张表溢出到 726。 */
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

/* ══════════ 同步与状态 ══════════

   这一页是**一张检修单，不是一排卡片**。

   上一版是 auto-fit 网格：两行的键值列表和三四十行的表格被并排拼贴，
   读一页要在屏幕上来回跳；而"待办"和"长任务状态"沉在两张同样大小的卡片里，
   跟存储可用性长得一模一样 —— 于是没人知道该先看哪儿。

   这一版的规矩：
   ① 一列，宽度锁住。信息按「先结论 → 再待办 → 再动作 → 最后诊断」排；
   ② **暖色只给待办**。没有待办时整页是灰阶，而"没有颜色"本身就是"没事"的信号
      （设计令牌里写着：灵火一屏之内只给最该先看一眼的东西）；
   ③ 状态与它的触发方式必须挨在一起。 */

/*
  宽度：这一页是"一列文档"，而外壳是 1400 的宽版（标注那页的地图要占满，所以不能改成
  narrow）。因此这里自己收窄并**居中** —— 少了 margin:auto 的话，这一列会贴在 1400 的左边、
  右边空出一大片，跟上面的吸顶栏对不上。留窄是必要的：长任务的按钮在最右，
  撑满 1350px 会让名字和按钮之间空出一大块。 */
.status {
  display: flex;
  flex-direction: column;
  gap: var(--sp-6);
  /*
   * ⚠️ width 必须显式写。这一列是列向 flex 的项，而**交叉轴上的 auto 外边距会取消拉伸** ——
   * 只写 max-width + margin:auto 时它的宽度是 fit-content：展开「候选地图」那张表，
   * 表格的最大内容宽度就把它撑开，整列跟着变宽（表现为"点开宽度就变来变去"）。
   * width:100% 把宽度定死成"容器宽，封顶 1120"，内容再宽也只影响表自己。
   */
  width: 100%;
  max-width: 1120px;
  margin-inline: auto;
}

/* 区块标签：中文小字 + 刻字间距。结构靠它分段，不靠再加一层框 */
.sect {
  margin: 0 0 var(--sp-3);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semi);
  letter-spacing: var(--tracking-ink);
  color: var(--ink-3);
}



/* ---------- 待办：全页唯一配用暖色的地方 ---------- */
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

/* ---------- 长任务：一行一个任务，状态与按钮挨着 ---------- */
.op-list { list-style: none; margin: 0; padding: 0; }
.op { display: flex; align-items: center; gap: var(--sp-4); padding: var(--sp-3) 0; border-top: 1px solid var(--hairline); }
.op:first-child { border-top: none; padding-top: 0; }
.op-main { flex: 1; min-width: 0; }
.op-head { display: flex; align-items: center; gap: var(--sp-2); }
.op-name { font-size: var(--fs-md); font-weight: var(--fw-medium); color: var(--ink); }
/* 结果比说明亮：说明是静态的，结果是刚发生的事 */
.op-result {
  margin: var(--sp-1) 0 0;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-2);
  word-break: break-word;
}
/* 进度：任务要能"看见在动"，不然几十分钟的预热与卡死没有区别。
   条子用设计系统里的 Bar（与抓取作业同一套），这里只管摆放 */
.op-bar { margin-top: var(--sp-2); max-width: 420px; }
/* 覆盖矩阵：行 = 画布，列 = 档位。补齐的格子是灰的，缺的格子用暖色喊一声 */
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
/* 失败才多说一句：为什么失败、缺了多少。它是全页唯一需要人去处理的那类信息 */
.op-fail {
  margin: var(--sp-1) 0 0;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--blight-lift);
  word-break: break-word;
}
.op-actions { flex: none; display: flex; gap: var(--sp-2); }

/* ---------- 诊断：默认折叠 ---------- */
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
/* 健康时的一行：与可展开的行同款石板，但没有箭头、不可点 —— 它只是告诉你"这没事" */
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
/* ==================== 找点位 ==================== */
.find { display: flex; align-items: center; gap: var(--sp-2); min-width: 260px; }
.find :deep(input) { min-width: 180px; }

/*
 * 命中列表浮在页头下面，不挤占版面。
 * 用 fixed 而不是 absolute：它挂在 AdminPage 的内容流里，而页头是 sticky 的，
 * absolute 会跟着内容滚走。
 */
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
