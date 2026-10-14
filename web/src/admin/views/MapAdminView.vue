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
import { computed, onMounted, ref, watch } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import AdminPage from '@shared/ui/AdminPage.vue'
import Button from '@shared/ui/Button.vue'
import Icon from '@shared/ui/Icon.vue'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Tag from '@shared/ui/Tag.vue'
import Tabs from '@shared/ui/Tabs.vue'
import { toast } from '@shared/ui/toast'
import MapCanvas from '@shared/map/MapCanvas.vue'
import type { Catalog, MapSpec, MapStatus, Marker, PlaceInput, Relative } from '@shared/map/types'

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
const visibleGroups = computed(() => new Set((currentMap.value?.groups ?? []).map((g) => g.key)))
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
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function loadMarkers() {
  const r = await adminApi.get<{ markers: Marker[] }>('/map/markers?map=' + encodeURIComponent(mapName.value))
  markers.value = r.markers
  selected.value = null
}

async function loadStatus() {
  status.value = await adminApi.get<MapStatus>('/map/status')
}

onMounted(boot)

watch(mapName, async (next, prev) => {
  if (!next || next === prev) return
  canvasIndex.value = 0
  await loadMarkers()
})

watch(groupIcons, () => mapCanvas.value?.setGroupIcons(groupIcons.value), { flush: 'post' })

/* ==================== 地图交互 ==================== */

function onPick(m: Marker | null) {
  if (!m) {
    selected.value = null
    return
  }
  selected.value = m
  highlight.value = null
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
}

/** 从列表跳到一个点位：先切到它所在的画布，再选中它 */
function jumpTo(m: Marker) {
  const list = currentMap.value?.canvases ?? []
  const idx = list.findIndex((c) => c.page === m.canvas)
  if (idx >= 0) canvasIndex.value = idx
  tab.value = 'mark'
  onPick(m)
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
    if (form.value.id > 0) {
      await adminApi.put('/map/places/' + form.value.id, body)
      toast('已更新点位')
    } else {
      await adminApi.post('/map/places', body)
      toast('已新增点位')
    }
    draft.value = null
    await loadMarkers()
    await loadStatus()
  } catch (e) {
    toast(describeError(e), 'error')
  } finally {
    saving.value = false
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
    if (!running) return
  }
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

/** 切到这一页就重新拉一次：刚同步过的话，分组清单可能已经变了 */
watch(tab, (t) => {
  if (t === 'defaults') void loadLayers()
})

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
        { key: 'defaults', label: '默认显示' },
        { key: 'status', label: '同步与状态' },
      ]" />
    </template>
    <template #actions>
      <Button size="sm" @click="mode = mode === 'edit' ? 'view' : 'edit'">
        {{ mode === 'edit' ? '切到浏览' : '切到标注' }}
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

        <div v-else class="board">
        <aside class="panel-col">
          <!-- 新建 / 编辑本地点位 -->
          <div class="card">
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

          <!-- wiki 点位备注 -->
          <div class="card" :class="{ dim: selected?.source !== 'wiki' }">
            <h2 class="card-title">wiki 点位备注</h2>
            <p v-if="selected?.source !== 'wiki'" class="hint">
              在地图上点一个 wiki 点位（尖塔、神龛一类），这里就能给它补中文名与说明。
            </p>
            <template v-else>
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

        <div class="stage">
          <MapCanvas
            v-if="currentCanvas"
            ref="mapCanvas"
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
        </div>
        </div>
      </div>

      <!-- ==================== 点位列表 ==================== -->
      <div v-show="tab === 'places'" class="table-wrap">
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
      <!-- ==================== 默认显示 ==================== -->
      <div v-show="tab === 'defaults'" class="defaults">
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

            <div class="layer-actions">
              <Button
                variant="primary"
                size="sm"
                :disabled="layerSaving"
                @click="saveLayers([...layerOn])"
              >{{ layerSaving ? '保存中…' : '保存' }}</Button>
              <Button size="sm" :disabled="layerSaving" @click="saveLayers(null)">
                恢复未配置
              </Button>
            </div>
          </div>
        </template>

        <div v-else class="empty-state">
          <h2>还没有地图数据</h2>
          <p>先到「同步与状态」同步一次 wiki 地图。</p>
        </div>
      </div>

      <div v-show="tab === 'status'" class="status">
        <div class="card">
          <h2 class="card-title">数据</h2>
          <p class="kv"><span>存储</span><code>{{ status?.directory }}</code></p>
          <p class="kv"><span>地图库</span>{{ status?.mapStoreAvailable ? '可用' : '不可用' }}</p>
          <p class="kv"><span>点位存储</span>{{ status?.placesAvailable ? '可用' : '不可用' }}</p>
          <p class="kv"><span>备注存储</span>{{ status?.notesAvailable ? '可用' : '不可用' }}</p>
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
            <Button size="sm" @click="runCorpus(false)">派生地点语料</Button>
            <Button size="sm" @click="runCorpus(true)">清除地点语料</Button>
          </div>
          <p class="hint">
            同步是增量的：只拉版本变过的页，常态下几秒完成。
            底图预热要拉几百张瓦片，出站请求是节流的，第一次大约一两分钟 —— 做过一次之后
            所有访客都命中本地缓存。地点语料要调向量接口，也慢。
            三件事都在后台跑，进度看上面。
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

.board {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  gap: var(--sp-4);
  align-items: start;
}
@media (max-width: 1080px) {
  .board { grid-template-columns: minmax(0, 1fr); }
}
.panel-col { display: flex; flex-direction: column; gap: var(--sp-3); }

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

.stage {
  position: relative;
  height: clamp(520px, 78vh, 1040px);
  border-radius: var(--r-md);
  overflow: hidden;
  box-shadow: var(--shadow-lift);
  background: var(--stone-void);
}

.table-wrap { overflow-x: auto; background: var(--stone-300); border-radius: var(--r-md); box-shadow: var(--bevel-raised); }
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
