<script setup lang="ts">
/**
 * 公开站地图查看器。
 *
 * <h2>这一页的一句话</h2>
 * "把 wiki 的地图变成能拖、能搜、能指着地图告诉你往哪走的一张图。"
 *
 * <h2>信息架构</h2>
 * 左边一条控制栏（换地图 / 找地方 / 开关图层），右边全是地图 ——
 * 地图是主角，控件只在需要时占据视线。窄屏时控制栏收成一条横向工具条。
 *
 * <h2>为什么"找地方"走 /map/locate 而不是前端过滤</h2>
 * 因为答案不是"匹配到哪些点"，而是"这些点在地图上哪儿、相对哪个地标"。
 * 那需要全量点位算最近地标 —— 前端只拿得到当前地图的点，而且会算重。
 * 放后端算还有一个好处：机器人在群里回答用的是同一段代码，网页上看到什么、群里就说什么。
 */
import { computed, onMounted, ref, watch } from 'vue'
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

/* ==================== 资源点位 ==================== */

const resourceCategories = ref<ResourceCategory[]>([])
const resourceVersion = ref('')
const resourceMessage = ref('')
const resourceMarkers = ref<Marker[]>([])
/** 已经取过坐标的层；开关关掉再打开不重复请求 */
const loadedResources = ref<Set<string>>(new Set())
const loadingResources = ref<Set<string>>(new Set())
/** 展开的资源分类；deep link 落到某一层时自动展开它所在的分区 */
const openCategories = ref<Set<string>>(new Set())

const visibleGroups = ref<Set<string>>(new Set())
const visibleCategories = ref<Set<string>>(new Set())
/**
 * 选中的点位**栈**：同一个像素上可能压着好几个点位。
 *
 * <p>为什么是栈而不是单个：wiki 上一个尖塔既是传送点、又是收集品、还可能是任务点，
 * 它们坐标完全相同。以前只取最近的那个，另外几个等于永远点不开。
 */
const pickedStack = ref<Marker[]>([])
/** 栈顶那张，也就是最近的那个 —— 高亮、详情里的"主"信息都用它 */
const selected = computed(() => pickedStack.value[0] ?? null)
const highlight = ref<{ toRef: string; relative: Relative } | null>(null)

/**
 * 堆叠顺序：**最近的在最前**（i = 0）。
 *
 * <p>DOM 顺序保持"近的在前"，靠 z-index 决定谁压在上面 ——
 * 因为 CSS 要靠 :first-child 把栈顶那张留在正常流里撑住容器高度，
 * 否则整叠卡片都是绝对定位，容器高度塌成 0，鼠标就没有可悬停的面积。
 */
const stackCards = computed(() =>
  pickedStack.value.map((m, i) => ({ m, i })),
)

const query = ref('')
const locating = ref(false)
const located = ref<LocateResult | null>(null)

const mapCanvas = ref<InstanceType<typeof MapCanvas> | null>(null)

/**
 * 悬浮控制栏是否展开。
 *
 * <p>桌面默认展开：第一次进来必须看得见「哪里能搜、哪里能开关图层」。
 * 手机默认收起：一进来就展开会把地图盖掉六成，而这一页的主角是地图本身。
 */
const railOpen = ref(
  typeof window === 'undefined' || !window.matchMedia('(max-width: 760px)').matches
)

const currentMap = computed<MapSpec | null>(
  () => catalog.value?.maps.find((m) => m.map === mapName.value) ?? null,
)

/** 一张地图可能有多张画布。同一时间只显示一张，切画布等于切底图 */
const canvasIndex = ref(0)
const currentCanvas = computed(() => {
  const list = currentMap.value?.canvases ?? []
  return list[Math.min(canvasIndex.value, Math.max(0, list.length - 1))] ?? null
})

/** 当前画布上的点位 —— 跨画布的点不能一起画（坐标原点不同） */
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

/* ==================== 装载 ==================== */

async function boot() {
  loading.value = true
  error.value = ''
  try {
    const c = await publicApi.get<Catalog>('/map/catalog')
    catalog.value = c
    const wanted = typeof route.query.map === 'string' ? route.query.map : ''
    const first = c.maps.find((m) => m.map === wanted) ?? c.maps[0]
    mapName.value = first ? first.map : ''
    if (mapName.value) await loadMarkers(mapName.value)
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

async function loadMarkers(map: string) {
  markersLoading.value = true
  try {
    const r = await publicApi.get<{ markers: Marker[] }>('/map/markers?map=' + encodeURIComponent(map))
    markers.value = r.markers
    const groups = new Set<string>()
    const picked = currentMap.value?.defaultGroups
    if (picked) {
      // 管理员在后台挑过：**完全按他的选择**，不再叠加经验值 ——
      // 否则"我在后台关掉了收集品"这件事会在前端被悄悄推翻
      for (const k of picked) groups.add(k)
    } else {
      // 还没配置过：用内置经验值兜底，别让新地图一上来是一片空白。
      // 只开"找地方"用得上的 —— 收集品与世界观文本加起来五百多条，
      // 全开会让总览糊成一片，而它们不是"资源在哪、敌营在哪"这类问题的答案。
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

/** 资源目录只有图层与点数，坐标按层懒加载 */
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
  if (loadedResources.value.has(layer.key)) return

  const loading = new Set(loadingResources.value)
  loading.add(layer.key)
  loadingResources.value = loading
  try {
    const r = await publicApi.get<{ points: [number, number, string][] }>(
      '/map/resources?map=' + encodeURIComponent(mapName.value) +
        '&layer=' + encodeURIComponent(layer.key),
    )
    const canvasPage = currentCanvas.value?.page ?? ''
    const add: Marker[] = r.points.map(([x, z, name], i) => ({
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
    }))
    resourceMarkers.value = [...resourceMarkers.value, ...add]
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

watch(mapName, async (next, prev) => {
  if (!next || next === prev) return
  canvasIndex.value = 0
  await loadMarkers(next)
  router.replace({ query: { ...route.query, map: next } })
})

watch(canvasIndex, () => {
  pickedStack.value = []
  highlight.value = null
})

// 图标表与画布实例都可能在数据之后才就绪，两个都盯着才不会漏掉其中一次
watch([groupIcons, mapCanvas], () => mapCanvas.value?.setGroupIcons(groupIcons.value), { flush: 'post' })

/* ==================== 找地方 ==================== */

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

/**
 * 跳到一条命中：换地图 → 换画布 → 把锚点与目标同时框进视野。
 *
 * "同时框进"是刻意的：只说"在某某东北"而不把那个参照物一起显示出来，
 * 用户还得自己去搜参照物在哪 —— 那等于把计算过的信息又丢掉了。
 */
async function focusHit(hit: LocateHit) {
  if (hit.map && hit.map !== mapName.value) {
    mapName.value = hit.map
    await loadMarkers(hit.map)
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

/* ==================== 图层 ==================== */

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
            <!--
              手机端整条标题栏就是开关：收起后只剩这 46px 的把手，
              点它重新展开。上一版只有「收起」没有「展开」，收起之后再也叫不回来。
            -->
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
                  <span class="res-dot" :style="{ background: l.color }" />
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
                  :src="'/api/public/map/tile?map=' + encodeURIComponent(mapName) + '&name=' + encodeURIComponent(g.icon) + '&w=48'"
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

        <!--
          详情卡：右下角。地图不被遮挡太多，又离手最近。
          多个点位重合时叠成一副牌 —— 平时只露出每张的顶边，看得出"不止一张"，
          悬停或键盘聚焦才摊开。摊开是**位移**而不是改布局，所以不会把地图推来推去。
        -->
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
/*
 * 地图页是**全屏页**：外壳去掉了页脚与留白（见 App.vue 的 is-full），
 * 这一层把自己撑满，并且自己**绝不滚动** —— 页面能滚的话，拖地图时会连带把页面拖动。
 */
.map-page {
  height: 100%;
  min-height: 0;
  overflow: hidden;
  /* 左右只留一道最小边距，真正的留白交给舞台自己的宽度上限 */
  padding: var(--sp-4) var(--sp-3);
  display: flex;
  justify-content: center;
}
@media (max-width: 760px) {
  /* 手机上横向一寸都不能浪费；底部留给底栏的空白由外壳那层负责 */
  .map-page { padding: var(--sp-2); }
  .stage { width: 100%; }
}

/* 空态 / 加载 / 报错都浮在地图之上，而不是把地图顶下去 */
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

/* ==================== 悬浮控制栏 ====================
   面板**浮在地图上**，不再占一列：地图才是主角，控制栏不该永久吃掉 288px 的宽度。
   收起之后只剩左上角一颗按钮，地图回到满宽。 */
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
  /*
   * 半透明 + 模糊。**别调回 92%**：那个浓度下底图完全看不见，
   * 面板就变成一块贴在页面上的石板，失去了"浮在地图上"的意义。
   * 76% 是实测还能看清面板里文字、同时底图的地形与颜色明显透出来的位置；
   * 模糊保留一点，是为了让透出来的底图不至于干扰读字。
   */
  background: color-mix(in srgb, var(--stone-300) 76%, transparent);
  box-shadow: var(--shadow-pop), var(--bevel-raised);
  backdrop-filter: blur(8px);
  /* 面板自己滚到底时不要把页面一起带着滚 */
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
  /* 标题栏比面板主体实一点：它是吸顶的，下面滚过的内容不能透上来 */
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
/* 桌面：收起时整块藏起来，左上角留一颗「面板」按钮 */
@media (min-width: 761px) {
  .rail.is-collapsed { display: none; }
}

/* 手机上整块变成底部弹层：
   收起时只剩一条 46px 的把手，地图可见面积从「六成被盖住」变成「九成可见」。
   这是这一页移动端最重要的一个改动 —— 上一版控制栏浮在左上，一进来就遮掉 62% 的图。 */
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
  /* 收起：只留把手。不再整块消失，否则总要回左上角找那个「面板」按钮 */
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
  /* 把手：40×4 的短横，告诉用户这张纸可以推上来 */
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
  /* 手机端收起就是「把手」，不需要额外的关闭按钮：留着它也关不掉什么 */
  .rail-collapse { display: none; }
  .rail-body {
    padding: var(--sp-2) var(--sp-4) var(--sp-5);
    overflow-y: auto;
    overscroll-behavior: contain;
  }
  /* 浮动的「面板」按钮只服务桌面；手机上收起是把手的事 */
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
  /* 行内文字按钮，但命中区要够：加内边距把高度撑到 28，不改变观感 */
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
  /* 整行可点，命中高度 36：原生复选框只有 13px，但它不是唯一的点击面 */
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

.dot { width: 9px; height: 9px; border-radius: 50%; background: var(--mist); }
.dot.d-resource { background: var(--vital); }
.dot.d-landmark { background: var(--ember); }
.dot.d-npc { background: var(--mist); }
.dot.d-poi { background: var(--ink-3); }
.dot.d-custom { background: var(--ember-hot); }

/* ==================== 舞台 ==================== */
.stage {
  position: relative;
  /*
   * 只占视口宽度的 75%，居中。
   *
   * 为什么是 min(100%, max(680px, 75vw)) 而不是一个百分比：手机上 75vw 只有两百多像素，
   * 地图会窄到没法看。这样写等于"宽屏按比例留白、窄屏自动吃满可用宽度"，
   * 一个表达式覆盖两头，不需要再写一套媒体查询。
   */
  width: min(100%, max(680px, 75vw));
  height: 100%;
  min-height: 0;
  overflow: hidden;
  /*
   * 这里**不铺任何底色、不加投影**。
   *
   * 之前用的是 wiki 给的 backdrop 色（#2e2e2c）加一层深底 —— 结果画布区域是一块
   * 比页面更亮的方块，四周留白一放大就非常明显，看着像盖了张蒙版。
   * 现在画布与页面同底：瓦片没盖到的地方直接露出页面背景，边界由地图自身决定。
   */
  border-radius: var(--r-md);
}
/* 缩放按钮放左下：左上留给控制栏，右下留给详情卡 */
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

/* ==================== 详情 ==================== */
/* ==================== 详情卡（可堆叠） ==================== */
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
/*
 * 多张重合：叠成一副牌，每张只往上露出 16px 的顶边。
 * 用绝对定位 + 位移，不改布局 —— 改布局会让整叠卡片的尺寸随悬停跳动。
 */
.detail-stack.many .detail {
  position: absolute;
  left: 0;
  right: 0;
  /*
   * 对齐的是**顶边**不是底边。
   * 底对齐 + 上移会让比栈顶矮的卡片整个藏到它后面（矮的那张顶边更低），
   * 看上去还是只有一张；顶对齐则不论高矮都稳定露出 i×16px 的一条边。
   */
  top: 0;
  /* 越靠前的越在上面：栈顶那张要压住后面露出来的边 */
  z-index: calc(20 - var(--i));
  transform: translateY(calc(var(--i) * -16px));
  transition: transform var(--dur) var(--ease);
}
/*
 * 栈顶那张留在正常流里，把容器撑到它自己的高度。
 * 不给它这个位置的话，整叠卡片全是绝对定位，容器高度是 0 ——
 * 视觉上照常显示，但鼠标找不到可悬停的面积，摊开就永远触发不了。
 */
.detail-stack.many .detail:first-child {
  position: relative;
  z-index: 20;
}

/* 悬停或键盘聚焦才摊开：每张按自身高度让开，露出完整内容 */
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

/* ==================== 窄屏收尾 ====================
   ⚠️ 这两条必须放在文件最后：.stage-tools 与 .detail-stack 的基础规则在上面，
   同权重下后写的那条胜出，写早了会被基础规则覆盖。 */
@media (max-width: 760px) {
  /* 缩放按钮挪到右上，避开底部的弹层与详情卡 */
  .stage-tools {
    left: auto;
    right: var(--sp-2);
    bottom: auto;
    top: var(--sp-2);
    flex-direction: row;
  }
  /* 手机上缩放按钮放大到 40：悬在地图上的小按钮最容易点空 */
  .tool { width: 40px; height: 40px; }
  /* 详情卡：贴在弹层之上，通栏。只留 8px 边距，手指点得到关闭 */
  .detail-stack {
    left: var(--sp-2);
    right: var(--sp-2);
    bottom: calc(var(--tabbar-h) + var(--safe-b) + 54px);
    width: auto;
    max-height: 46svh;
  }
}
</style>