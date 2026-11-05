<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { publicApi, describeError } from '@shared/api/client'
import Icon from '@shared/ui/Icon.vue'
import Button from '@shared/ui/Button.vue'
import Input from '@shared/ui/Input.vue'
import Tag from '@shared/ui/Tag.vue'
import MapCanvas from '@shared/map/MapCanvas.vue'
import type {
  Catalog,
  LocateHit,
  LocateResult,
  MapArea,
  MapSpec,
  Marker,
  Relative,
  ResourceCatalog,
  ResourceCategory,
  ResourceLayer,
} from '@shared/map/types'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const error = ref('')
const catalog = ref<Catalog | null>(null)
const mapName = ref('')
const markers = ref<Marker[]>([])
const markersLoading = ref(false)

const resourceCategories = ref<ResourceCategory[]>([])
const resourceVersion = ref('')
const resourceMessage = ref('')
const resourceMarkers = ref<Marker[]>([])
const resourceAreas = ref<MapArea[]>([])
const loadedResources = ref<Set<string>>(new Set())
const loadingResources = ref<Set<string>>(new Set())
const openCategories = ref<Set<string>>(new Set())

function sidebarIcon(icon: string): string {
  return '/api/public/v1/map/resource-icon?name=' + encodeURIComponent(icon)
}

const shownResource = computed(() => {
  let layers = 0
  let points = 0
  let areas = 0
  for (const cat of resourceCategories.value) {
    for (const l of cat.layers) {
      if (!visibleGroups.value.has(l.key)) continue
      layers += 1
      if (l.shape === 'area') areas += l.count
      else points += l.count
    }
  }
  return { layers, points, areas }
})

const visibleGroups = ref<Set<string>>(new Set())
const visibleCategories = ref<Set<string>>(new Set())
const pickedStack = ref<Marker[]>([])
const selected = computed(() => pickedStack.value[0] ?? null)
const highlight = ref<{ toRef: string; relative: Relative } | null>(null)

const stackCards = computed(() =>
  pickedStack.value.map((m, i) => ({ m, i })),
)

const query = ref('')
const locating = ref(false)
const located = ref<LocateResult | null>(null)

const mapCanvas = ref<InstanceType<typeof MapCanvas> | null>(null)

const railOpen = ref(
  typeof window === 'undefined' || !window.matchMedia('(max-width: 760px)').matches
)

const currentMap = computed<MapSpec | null>(
  () => catalog.value?.maps.find((m) => m.map === mapName.value) ?? null,
)

const canvasIndex = ref(0)
const currentCanvas = computed(() => {
  const list = currentMap.value?.canvases ?? []
  return list[Math.min(canvasIndex.value, Math.max(0, list.length - 1))] ?? null
})

const markersOnCanvas = computed(() =>
  [...markers.value, ...resourceMarkers.value].filter(
    (m) => !currentCanvas.value || !m.canvas || m.canvas === currentCanvas.value.page,
  ),
)

const groupIcons = computed(() => {
  const out = new Map<string, string>()
  for (const g of currentMap.value?.groups ?? []) {
    if (g.icon) out.set(g.key, g.icon)
  }
  return out
})

const categoryLabel = computed(() => {
  const out = new Map<string, string>()
  for (const c of catalog.value?.categories ?? []) out.set(c.key, c.label)
  return out
})

const adminCategories = computed(() => {
  const seen = new Set<string>()
  for (const m of markers.value) {
    if (m.source === 'admin') seen.add(m.category)
  }
  return [...seen].sort()
})

async function boot() {
  loading.value = true
  error.value = ''
  try {
    const c = await publicApi.get<Catalog>('/map/catalog')
    catalog.value = c
    const wanted = typeof route.query.map === 'string' ? route.query.map : ''
    const first = c.maps.find((m) => m.map === wanted) ?? c.maps[0]
    await selectMap(first ? first.map : '')
    const q = typeof route.query.q === 'string' ? route.query.q : ''
    if (q) {
      query.value = q
      await locate()
    }
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

let mapTask: Promise<void> | null = null

async function selectMap(name: string): Promise<void> {
  if (!name || name === mapName.value) return
  mapName.value = name
  await nextTick()
  await mapTask
}

let loadingMap = ''
let loadingPromise: Promise<void> | null = null

async function loadMarkers(map: string) {
  if (loadingMap === map && loadingPromise) return loadingPromise
  loadingMap = map
  loadingPromise = doLoadMarkers(map).finally(() => {
    if (loadingMap === map) loadingPromise = null
  })
  return loadingPromise
}

async function doLoadMarkers(map: string) {
  markersLoading.value = true
  try {
    const r = await publicApi.get<{ markers: Marker[] }>('/map/markers?map=' + encodeURIComponent(map))
    markers.value = r.markers
    const groups = new Set<string>()
    const picked = currentMap.value?.defaultGroups
    if (picked) {
      for (const k of picked) groups.add(k)
    } else {
      for (const g of currentMap.value?.groups ?? []) {
        if (g.category !== 'collection') groups.add(g.key)
      }
    }
    visibleGroups.value = groups
    const cats = new Set<string>()
    for (const c of catalog.value?.categories ?? []) cats.add(c.key)
    visibleCategories.value = cats
    pickedStack.value = []
    highlight.value = null
    resourceMarkers.value = []
    resourceAreas.value = []
    loadedResources.value = new Set()
    openCategories.value = new Set()
    await loadResourceCatalog(map)
    const wanted = typeof route.query.layer === 'string' ? route.query.layer : ''
    if (wanted) {
      const cat = resourceCategories.value.find((c) => c.layers.some((l) => l.key === wanted))
      const hit = cat?.layers.find((l) => l.key === wanted)
      if (cat) {
        const opened = new Set(openCategories.value)
        opened.add(cat.key)
        openCategories.value = opened
      }
      if (hit) await toggleResource(hit)
    }
  } finally {
    markersLoading.value = false
  }
}

async function loadResourceCatalog(map: string) {
  resourceCategories.value = []
  resourceVersion.value = ''
  resourceMessage.value = ''
  try {
    const r = await publicApi.get<ResourceCatalog>(
      '/map/resource-groups?map=' + encodeURIComponent(map),
    )
    resourceCategories.value = r.categories ?? []
    resourceVersion.value = r.version || ''
    resourceMessage.value = r.message || ''
  } catch (e) {
    resourceMessage.value = describeError(e)
  }
}

function onCategoryToggle(key: string, event: Event) {
  const open = (event.target as HTMLDetailsElement).open
  const next = new Set(openCategories.value)
  if (open) next.add(key)
  else next.delete(key)
  openCategories.value = next
}

async function toggleResource(layer: ResourceLayer) {
  const next = new Set(visibleGroups.value)
  if (next.has(layer.key)) {
    next.delete(layer.key)
    visibleGroups.value = next
    return
  }
  next.add(layer.key)
  visibleGroups.value = next
  if (loadedResources.value.has(layer.key) || loadingResources.value.has(layer.key)) return

  const loading = new Set(loadingResources.value)
  loading.add(layer.key)
  loadingResources.value = loading
  try {
    const canvasPage = currentCanvas.value?.page ?? ''
    if (layer.shape === 'area') {
      const r = await publicApi.get<{
        areas: { x0: number; z0: number; x1: number; z1: number; name: string }[]
      }>(
        '/map/resource-areas?map=' + encodeURIComponent(mapName.value) +
          '&layer=' + encodeURIComponent(layer.key),
      )
      const add: MapArea[] = r.areas.map(({ x0, z0, x1, z1, name }, i) => ({
        id: 'area:' + layer.key + ':' + i,
        group: layer.key,
        canvas: canvasPage,
        x0,
        z0,
        x1,
        z1,
        color: layer.color,
        name: name || layer.name,
      }))
      resourceAreas.value = resourceAreas.value.filter((a) => a.group !== layer.key).concat(add)
      const done = new Set(loadedResources.value)
      done.add(layer.key)
      loadedResources.value = done
      return
    }
    const r = await publicApi.get<{
      points: { x: number; z: number; name: string; region: number; regionName: string }[]
    }>(
      '/map/resources?map=' + encodeURIComponent(mapName.value) +
        '&layer=' + encodeURIComponent(layer.key),
    )
    const add: Marker[] = r.points.map(({ x, z, name }, i) => ({
      id: 'res:' + layer.key + ':' + i,
      map: mapName.value,
      page: '',
      canvas: canvasPage,
      group: layer.key,
      name: name || layer.name,
      original: '',
      description: '',
      article: '',
      x,
      y: z,
      image: '',
      landmark: false,
      category: 'resource',
      priority: 0,
      note: '',
      source: 'resource' as const,
      color: layer.color,
      icon: layer.icon || '',
    }))
    resourceMarkers.value = resourceMarkers.value.filter((m) => m.group !== layer.key).concat(add)
    const done = new Set(loadedResources.value)
    done.add(layer.key)
    loadedResources.value = done
  } catch (e) {
    const rollback = new Set(visibleGroups.value)
    rollback.delete(layer.key)
    visibleGroups.value = rollback
    resourceMessage.value = describeError(e)
  } finally {
    const idle = new Set(loadingResources.value)
    idle.delete(layer.key)
    loadingResources.value = idle
  }
}

onMounted(boot)

watch(mapName, (next, prev) => {
  if (!next || next === prev) return
  canvasIndex.value = 0
  mapTask = loadMarkers(next).then(() => {
    router.replace({ query: { ...route.query, map: next } })
  })
})

watch(canvasIndex, () => {
  pickedStack.value = []
  highlight.value = null
})

watch([groupIcons, mapCanvas], () => mapCanvas.value?.setGroupIcons(groupIcons.value), { flush: 'post' })

async function locate() {
  const q = query.value.trim()
  if (!q || locating.value) return
  locating.value = true
  error.value = ''
  try {
    located.value = await publicApi.get<LocateResult>('/map/locate?q=' + encodeURIComponent(q))
    const first = located.value.hits[0]
    if (first) focusHit(first)
    router.replace({ query: { ...route.query, q } })
  } catch (e) {
    error.value = describeError(e)
  } finally {
    locating.value = false
  }
}

async function focusHit(hit: LocateHit) {
  if (hit.map && hit.map !== mapName.value) {
    await selectMap(hit.map)
  }
  const list = currentMap.value?.canvases ?? []
  const idx = list.findIndex((c) => c.page === hit.canvas)
  if (idx >= 0) canvasIndex.value = idx
  await new Promise((r) => requestAnimationFrame(() => r(null)))

  const target = markers.value.find((m) => m.id === hit.ref)
  if (!target) {
    mapCanvas.value?.focus(hit.x, hit.y, 0.8)
  } else if (hit.relative && target) {
    mapCanvas.value?.showPair(hit.relative.anchor.x, hit.relative.anchor.y, target.x, target.y)
    highlight.value = { toRef: target.id, relative: hit.relative }
  } else {
    mapCanvas.value?.focus(target.x, target.y, 0.8)
  }
  if (target) pickedStack.value = [target]
}

function onPick(list: Marker[]) {
  pickedStack.value = list
  if (!list.length) highlight.value = null
}

function toggleGroup(key: string) {
  const next = new Set(visibleGroups.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  visibleGroups.value = next
}

function toggleCategory(key: string) {
  const next = new Set(visibleCategories.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  visibleCategories.value = next
}

function setAllGroups(on: boolean) {
  visibleGroups.value = on ? new Set((currentMap.value?.groups ?? []).map((g) => g.key)) : new Set()
}

function toggleLore() {
  const keys = (currentMap.value?.groups ?? []).filter((g) => g.key.startsWith('Lore')).map((g) => g.key)
  const anyOn = keys.some((k) => visibleGroups.value.has(k))
  const next = new Set(visibleGroups.value)
  for (const k of keys) {
    if (anyOn) next.delete(k)
    else next.add(k)
  }
  visibleGroups.value = next
}

const loreOn = computed(() =>
  (currentMap.value?.groups ?? []).some((g) => g.key.startsWith('Lore') && visibleGroups.value.has(g.key)),
)

function wikiUrl(m: Marker): string {
  if (!m.article) return ''
  return 'https://enshrouded.wiki.gg/wiki/' + encodeURIComponent(m.article.replace(/ /g, '_'))
}

function fmt(n: number): string {
  return Math.round(n).toLocaleString('zh-CN')
}
</script>

<template>
  <div class="map-page">
    <div class="stage">
      <MapCanvas
        v-if="currentCanvas"
        ref="mapCanvas"
        :canvas="currentCanvas"
        :markers="markersOnCanvas"
        :areas="resourceAreas"
        :visible-groups="visibleGroups"
        :visible-categories="visibleCategories"
        :selected-id="selected?.id ?? ''"
        :highlight="highlight"
        :map-name="mapName"
        :max-zoom="catalog?.maxZoom ?? 2"
        mode="view"
        @pick="onPick"
      />

      <p v-if="error" class="overlay alert" role="alert">{{ error }}</p>
      <p v-if="loading" class="overlay notice">正在载入地图数据…</p>

      <div v-else-if="!catalog || !catalog.maps.length" class="overlay empty-state">
        <Icon name="map-pin" :size="26" />
        <h2>还没有地图数据</h2>
      </div>

      <template v-else>
        <button
          v-if="!railOpen"
          type="button"
          class="rail-toggle"
          aria-controls="map-rail"
          :aria-expanded="false"
          @click="railOpen = true"
        >
          <Icon name="menu" :size="16" />
          面板
        </button>

        <aside id="map-rail" class="rail" :class="{ 'is-collapsed': !railOpen }" aria-label="地图控制">
          <div class="rail-head">
            <button
              type="button"
              class="rail-handle"
              aria-controls="map-rail"
              :aria-expanded="railOpen"
              @click="railOpen = !railOpen"
            >
              <span class="rail-title">{{ currentMap?.label ?? '地图' }}</span>
              <Icon name="chevron-down" :size="15" class="rail-chev" :class="{ up: railOpen }" />
            </button>
            <button
              type="button"
              class="rail-collapse"
              aria-controls="map-rail"
              :aria-expanded="railOpen"
              aria-label="收起面板"
              @click="railOpen = false"
            >
              <Icon name="close" :size="15" />
            </button>
          </div>

        <div class="rail-body">
        <div class="block">
          <h2 class="block-title">找地方</h2>
          <form class="find" @submit.prevent="locate">
            <Input v-model="query" placeholder="地点 / 资源 / NPC" autocapitalize="off" />
            <Button type="submit" variant="primary" size="sm" :disabled="locating">
              <Icon name="search" :size="15" />
              {{ locating ? '查找中' : '查找' }}
            </Button>
          </form>

          <ol v-if="located && located.hits.length" class="hits">
            <li v-for="h in located.hits" :key="h.ref + h.x + h.y">
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
          <p v-else-if="located && !located.found" class="hint warn">
            地图上还没有标过「{{ located.q }}」。
          </p>
        </div>

        <div v-if="resourceCategories.length || resourceMessage" class="block">
          <div class="block-head">
            <h2 class="block-title">资源点位</h2>
            <span v-if="resourceVersion" class="res-version">数据 {{ resourceVersion }}</span>
          </div>
          <p v-if="shownResource.layers" class="res-summary">
            已显示 {{ shownResource.layers }} 层 · {{ shownResource.points }} 个点<template v-if="shownResource.areas"> · {{ shownResource.areas }} 块林地</template>
          </p>
          <p v-if="!resourceCategories.length" class="hint">{{ resourceMessage }}</p>
          <details
            v-for="cat in resourceCategories"
            :key="cat.key"
            class="res-cat"
            :open="openCategories.has(cat.key)"
            @toggle="onCategoryToggle(cat.key, $event)"
          >
            <summary>
              <span class="res-cat-name">{{ cat.name }}</span>
              <span class="res-cat-n">{{ cat.count }}</span>
            </summary>
            <ul class="layers">
              <li v-for="l in cat.layers" :key="l.key">
                <label class="layer">
                  <input
                    type="checkbox"
                    :checked="visibleGroups.has(l.key)"
                    :disabled="loadingResources.has(l.key)"
                    @change="toggleResource(l)"
                  />
                  <img v-if="l.icon" class="res-icon" :src="sidebarIcon(l.icon)" alt="" loading="lazy" decoding="async" />
                  <span v-else class="res-dot" :style="{ background: l.color }" />
                  <span class="layer-name">{{ l.name }}</span>
                  <span class="layer-n">{{ l.count }}</span>
                </label>
              </li>
            </ul>
          </details>
        </div>

        <div class="block">
          <div class="block-head">
            <h2 class="block-title">图层</h2>
            <div class="mini-actions">
              <button type="button" class="mini" @click="setAllGroups(true)">全开</button>
              <button type="button" class="mini" @click="setAllGroups(false)">全关</button>
            </div>
          </div>
          <ul class="layers">
            <li v-for="g in currentMap?.groups" :key="g.key">
              <label class="layer">
                <input
                  type="checkbox"
                  :checked="visibleGroups.has(g.key)"
                  @change="toggleGroup(g.key)"
                />
                <img
                  v-if="g.icon"
                  class="layer-icon"
                  :src="'/api/public/v1/map/tile?map=' + encodeURIComponent(mapName) + '&name=' + encodeURIComponent(g.icon) + '&w=48'"
                  alt=""
                  loading="lazy"
                />
                <span class="layer-name">{{ g.name }}</span>
                <span class="layer-n">{{ g.count }}</span>
              </label>
            </li>
          </ul>
          <label v-if="currentMap?.groups.some((g) => g.key.startsWith('Lore'))" class="layer stand-alone">
            <input type="checkbox" :checked="loreOn" @change="toggleLore" />
            <span class="layer-name">世界观文本</span>
          </label>
        </div>

        <div v-if="adminCategories.length" class="block">
          <h2 class="block-title">本地补充点</h2>
          <ul class="layers">
            <li v-for="c in adminCategories" :key="c">
              <label class="layer">
                <input
                  type="checkbox"
                  :checked="visibleCategories.has(c)"
                  @change="toggleCategory(c)"
                />
                <span class="dot" :class="'d-' + c" />
                <span class="layer-name">{{ categoryLabel.get(c) ?? c }}</span>
                <span class="layer-n">
                  {{ markers.filter((m) => m.source === 'admin' && m.category === c).length }}
                </span>
              </label>
            </li>
          </ul>
        </div>
        </div>
        </aside>

        <div class="stage-tools" role="group" aria-label="缩放">
          <button type="button" class="tool" aria-label="放大" @click="mapCanvas?.zoomBy(1.35)">
            <Icon name="plus" :size="16" />
          </button>
          <button type="button" class="tool" aria-label="缩小" @click="mapCanvas?.zoomBy(1 / 1.35)">
            <Icon name="minus" :size="16" />
          </button>
          <button type="button" class="tool" aria-label="适应整图" @click="mapCanvas?.fit()">
            <Icon name="display" :size="16" />
          </button>
        </div>

        <p v-if="markersLoading" class="stage-loading">正在载入点位…</p>

        <div
          v-if="stackCards.length"
          class="detail-stack"
          :class="{ many: stackCards.length > 1 }"
          tabindex="0"
          :aria-label="stackCards.length > 1 ? '重合点位，共 ' + stackCards.length + ' 个' : '点位详情'"
        >
          <article
            v-for="c in stackCards"
            :key="c.m.id"
            class="detail"
            :style="{ '--i': c.i }"
          >
            <header class="detail-head">
              <h3>{{ c.m.name }}</h3>
              <button
                v-if="c.i === 0"
                type="button"
                class="mini x"
                aria-label="关闭详情"
                @click="pickedStack = []"
              >
                <Icon name="close" :size="15" />
              </button>
            </header>
            <p class="detail-tags">
              <Tag :tone="c.m.source === 'admin' ? 'flame' : 'neutral'">
                {{ c.m.source === 'admin' ? '本地补充' : c.m.source === 'resource' ? '资源' : 'wiki' }}
              </Tag>
              <Tag tone="neutral">{{ categoryLabel.get(c.m.category) ?? c.m.category }}</Tag>
              <Tag v-if="c.m.landmark" tone="warn">可作方位参照</Tag>
            </p>
            <p v-if="c.m.original && c.m.original !== c.m.name" class="detail-line">
              wiki 原名：{{ c.m.original }}
            </p>
            <p class="detail-line mono">坐标 ({{ fmt(c.m.x) }}, {{ fmt(c.m.y) }})</p>
            <p v-if="c.m.note" class="detail-note">{{ c.m.note }}</p>
            <p v-if="c.m.description" class="detail-desc">{{ c.m.description }}</p>
            <p v-if="c.m.article" class="detail-line">
              <a :href="wikiUrl(c.m)" target="_blank" rel="noopener noreferrer">
                在 wiki 上看这个词条
                <Icon name="external-link" :size="13" />
              </a>
            </p>
          </article>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.map-page {
  height: 100%;
  min-height: 0;
  overflow: hidden;
  padding: var(--sp-4) var(--sp-3);
  display: flex;
  justify-content: center;
}
@media (max-width: 760px) {
  .map-page { padding: var(--sp-2); }
  .stage { width: 100%; }
}

.overlay {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  margin: 0;
  max-width: min(520px, calc(100% - var(--sp-6)));
  z-index: 4;
}
.overlay.alert {
  top: var(--sp-4);
  transform: translateX(-50%);
  padding: var(--sp-3) var(--sp-4);
  border-left: 3px solid var(--blight);
  background: color-mix(in srgb, var(--stone-300) 94%, transparent);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  color: var(--blight-lift);
}
.overlay.notice {
  padding: 6px 14px;
  border-radius: var(--r-pill);
  background: color-mix(in srgb, var(--stone-400) 90%, transparent);
  color: var(--ink-2);
  font-size: var(--fs-sm);
}

.empty-state {
  padding: var(--sp-6) var(--sp-5);
  text-align: center;
  background: color-mix(in srgb, var(--stone-300) 94%, transparent);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-pop), var(--bevel-raised);
  color: var(--ink-2);
}
.empty-state h2 { margin: var(--sp-3) 0 var(--sp-2); font-size: var(--fs-lg); color: var(--ink); }
.empty-state p { margin: 0 auto; max-width: 46ch; }

.rail {
  position: absolute;
  left: var(--sp-3);
  top: var(--sp-3);
  bottom: var(--sp-3);
  width: 288px;
  z-index: 3;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  overflow-y: auto;
  padding: var(--sp-3) var(--sp-4) var(--sp-4);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--stone-300) 76%, transparent);
  box-shadow: var(--shadow-pop), var(--bevel-raised);
  backdrop-filter: blur(8px);
  overscroll-behavior: contain;
}
.rail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  position: sticky;
  top: calc(var(--sp-3) * -1);
  margin: calc(var(--sp-3) * -1) calc(var(--sp-4) * -1) 0;
  padding: var(--sp-2) var(--sp-3) var(--sp-2) var(--sp-4);
  background: color-mix(in srgb, var(--stone-300) 86%, transparent);
  border-bottom: 1px solid var(--edge-soft);
}
.rail-handle {
  flex: 1;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  min-height: 32px;
  padding: 0;
  background: none;
  border: 0;
  color: inherit;
  font: inherit;
  cursor: pointer;
  text-align: left;
}
.rail-chev { color: var(--ink-3); transition: transform var(--dur) var(--ease); }
.rail-chev.up { transform: rotate(180deg); }
.rail-title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semi);
  letter-spacing: .04em;
  color: var(--ink-2);
}
.rail-toggle,
.rail-collapse {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  border: 1px solid var(--edge);
  background: color-mix(in srgb, var(--stone-400) 90%, transparent);
  color: var(--ink-2);
  cursor: pointer;
  backdrop-filter: blur(8px);
}
.rail-collapse { display: grid; place-items: center; width: 32px; height: 32px; border-radius: var(--r-sm); }
.rail-toggle {
  position: absolute;
  left: var(--sp-3);
  top: var(--sp-3);
  z-index: 3;
  padding: 7px 12px;
  border-radius: var(--r-pill);
  font-size: var(--fs-sm);
}
.rail-toggle:hover, .rail-collapse:hover { color: var(--ember-hot); border-color: var(--ember); }
@media (min-width: 761px) {
  .rail.is-collapsed { display: none; }
}

@media (max-width: 760px) {
  .rail {
    left: 0;
    right: 0;
    top: auto;
    bottom: calc(var(--tabbar-h) + var(--safe-b));
    width: auto;
    max-height: 64svh;
    padding: 0;
    border-radius: var(--r-lg) var(--r-lg) 0 0;
    border-bottom: 0;
    background: var(--stone-300);
    gap: 0;
    overflow: hidden;
  }
  .rail.is-collapsed { max-height: 46px; }
  .rail.is-collapsed .rail-body { display: none; }
  .rail-head {
    position: relative;
    margin: 0;
    padding: 0 var(--sp-4);
    min-height: 46px;
    align-items: center;
    background: var(--stone-300);
    border-bottom: 1px solid var(--hairline);
  }
  .rail-head::before {
    content: '';
    position: absolute;
    top: 7px;
    left: 50%;
    width: 40px;
    height: 4px;
    margin-left: -20px;
    border-radius: var(--r-pill);
    background: var(--edge-hover);
  }
  .rail-handle { padding-top: 6px; }
  .rail-collapse { display: none; }
  .rail-body {
    padding: var(--sp-2) var(--sp-4) var(--sp-5);
    overflow-y: auto;
    overscroll-behavior: contain;
  }
  .rail-toggle { display: none; }

}
.block + .block { padding-top: var(--sp-3); border-top: 1px solid var(--edge-soft); }
.block-title {
  margin: 0 0 var(--sp-2);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semi);
  letter-spacing: .04em;
  color: var(--ink-3);
}
.block-head { display: flex; align-items: baseline; justify-content: space-between; gap: var(--sp-2); }
.mini-actions { display: flex; gap: var(--sp-2); }
.mini {
  background: none;
  border: none;
  padding: 3px 4px;
  min-height: 28px;
  color: var(--ink-3);
  font-size: var(--fs-xs);
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.mini:hover { color: var(--ember); }
.mini.x { padding: 2px 4px; text-decoration: none; }

.chips { display: flex; flex-wrap: wrap; gap: var(--sp-2); }
.chips.sub { margin-top: var(--sp-2); }
.chip {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 5px 10px;
  border-radius: var(--r-pill);
  border: 1px solid var(--edge);
  background: var(--stone-400);
  color: var(--ink-2);
  font-size: var(--fs-sm);
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}
.chip:hover { border-color: var(--edge-hover); background: var(--stone-500); }
.chip.on { border-color: var(--ember); color: var(--ember-hot); background: var(--ember-veil); }
.chip.small { font-size: var(--fs-xs); padding: 3px 9px; }
.chip-n { color: var(--ink-4); font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }

.find { display: flex; gap: var(--sp-2); align-items: flex-start; }
.hint { margin: var(--sp-2) 0 0; font-size: var(--fs-xs); color: var(--ink-3); }
.hint.warn { color: var(--warn); }

.hits { list-style: none; margin: var(--sp-3) 0 0; padding: 0; display: flex; flex-direction: column; gap: var(--sp-2); }
.hit {
  width: 100%;
  text-align: left;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--r-sm);
  border: 1px solid var(--edge-soft);
  background: var(--stone-400);
  color: var(--ink);
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.hit:hover { border-color: var(--ember); background: var(--stone-500); }
.hit-name { display: flex; align-items: center; gap: var(--sp-2); font-size: var(--fs-sm); font-weight: var(--fw-medium); }
.hit-rel { font-size: var(--fs-xs); color: var(--ink-3); }

.layers { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }
.layer {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  min-height: 36px;
  padding: 4px 6px;
  border-radius: var(--r-xs);
  cursor: pointer;
  font-size: var(--fs-sm);
}
.layer input { width: 18px; height: 18px; }
.layer:hover { background: var(--surface-hover); }
.layer input { accent-color: var(--ember); }
.layer-icon { width: 16px; height: 16px; object-fit: contain; }
.layer-name { flex: 1; color: var(--ink-2); }
.layer-n { color: var(--ink-4); font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }
.stand-alone { margin-top: var(--sp-2); border-top: 1px solid var(--edge-soft); padding-top: var(--sp-2); }

.res-version { color: var(--ink-4); font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }
.res-summary { margin: 2px 0 var(--sp-1); color: var(--ink-3); font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }
.res-cat { border-top: 1px solid var(--edge-soft); }
.res-cat:first-of-type { border-top: 0; }
.res-cat summary {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  min-height: 34px;
  padding: 4px 6px;
  list-style: none;
  cursor: pointer;
  font-size: var(--fs-sm);
  color: var(--ink-2);
}
.res-cat summary::-webkit-details-marker { display: none; }
.res-cat summary::before {
  content: '▸';
  color: var(--ink-4);
  font-size: 10px;
  transition: transform var(--dur-fast) var(--ease);
}
.res-cat[open] summary::before { transform: rotate(90deg); }
.res-cat summary:hover { background: var(--surface-hover); border-radius: var(--r-xs); }
.res-cat-name { flex: 1; }
.res-cat-n { color: var(--ink-4); font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }
.res-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex: 0 0 auto;
  box-shadow: 0 0 0 1px rgb(0 0 0 / 25%) inset;
}
.res-icon {
  width: 20px;
  height: 20px;
  object-fit: contain;
  flex: 0 0 auto;
  image-rendering: auto;
}

.dot { width: 9px; height: 9px; border-radius: 50%; background: var(--mist); }
.dot.d-resource { background: var(--vital); }
.dot.d-landmark { background: var(--ember); }
.dot.d-npc { background: var(--mist); }
.dot.d-poi { background: var(--ink-3); }
.dot.d-custom { background: var(--ember-hot); }

.stage {
  position: relative;
  width: min(100%, max(680px, 75vw));
  height: 100%;
  min-height: 0;
  overflow: hidden;
  border-radius: var(--r-md);
}
.stage-tools {
  position: absolute;
  left: var(--sp-3);
  bottom: var(--sp-3);
  z-index: 2;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.tool {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: var(--r-sm);
  border: 1px solid var(--edge);
  background: color-mix(in srgb, var(--stone-400) 88%, transparent);
  color: var(--ink-2);
  cursor: pointer;
  backdrop-filter: blur(6px);
}
.tool:hover { color: var(--ember-hot); border-color: var(--ember); }
.stage-loading {
  position: absolute;
  z-index: 2;
  top: var(--sp-3);
  left: 50%;
  transform: translateX(-50%);
  margin: 0;
  padding: 4px 10px;
  border-radius: var(--r-pill);
  background: color-mix(in srgb, var(--stone-400) 90%, transparent);
  color: var(--ink-2);
  font-size: var(--fs-xs);
}

.detail-stack {
  position: absolute;
  z-index: 2;
  right: var(--sp-3);
  bottom: var(--sp-3);
  width: min(340px, calc(100% - var(--sp-5)));
  max-height: calc(100% - var(--sp-6));
  outline: none;
}
.detail {
  position: relative;
  max-height: calc(100% - var(--sp-6));
  overflow-y: auto;
  padding: var(--sp-3) var(--sp-4) var(--sp-4);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--stone-300) 94%, transparent);
  box-shadow: var(--shadow-pop), var(--bevel-raised);
  backdrop-filter: blur(8px);
}
.detail-stack.many .detail {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  z-index: calc(20 - var(--i));
  transform: translateY(calc(var(--i) * -16px));
  transition: transform var(--dur) var(--ease);
}
.detail-stack.many .detail:first-child {
  position: relative;
  z-index: 20;
}

.detail-stack.many:hover .detail,
.detail-stack.many:focus-within .detail {
  transform: translateY(calc(var(--i) * -1 * (100% + 8px)));
}
.detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--sp-2); }
.detail-head h3 { margin: 0; font-size: var(--fs-lg); font-family: var(--font-title); }
.detail-tags { display: flex; flex-wrap: wrap; gap: var(--sp-2); margin: var(--sp-2) 0 var(--sp-3); }
.detail-line { margin: 0 0 var(--sp-2); font-size: var(--fs-sm); color: var(--ink-2); }
.detail-line.mono { font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--ink-3); }
.detail-note {
  margin: 0 0 var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  border-left: 3px solid var(--ember);
  background: var(--ember-veil);
  border-radius: 0 var(--r-xs) var(--r-xs) 0;
  font-size: var(--fs-sm);
  color: var(--ink);
}
.detail-desc { margin: 0 0 var(--sp-3); font-size: var(--fs-sm); color: var(--ink-3); }
.detail a { color: var(--ember); display: inline-flex; align-items: center; gap: 4px; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }

@media (max-width: 760px) {
  .stage-tools {
    left: auto;
    right: var(--sp-2);
    bottom: auto;
    top: var(--sp-2);
    flex-direction: row;
  }
  .tool { width: 40px; height: 40px; }
  .detail-stack {
    left: var(--sp-2);
    right: var(--sp-2);
    bottom: calc(var(--tabbar-h) + var(--safe-b) + 54px);
    width: auto;
    max-height: 46svh;
  }
}
</style>
