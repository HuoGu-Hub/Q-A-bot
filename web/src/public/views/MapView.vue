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
import type { Catalog, LocateHit, LocateResult, MapSpec, Marker, Relative } from '@shared/map/types'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const error = ref('')
const catalog = ref<Catalog | null>(null)
const mapName = ref('')
const markers = ref<Marker[]>([])
const markersLoading = ref(false)

const visibleGroups = ref<Set<string>>(new Set())
const visibleCategories = ref<Set<string>>(new Set())
const selected = ref<Marker | null>(null)
const highlight = ref<{ toRef: string; relative: Relative } | null>(null)

const query = ref('')
const locating = ref(false)
const located = ref<LocateResult | null>(null)

const mapCanvas = ref<InstanceType<typeof MapCanvas> | null>(null)

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
  markers.value.filter((m) => !currentCanvas.value || !m.canvas || m.canvas === currentCanvas.value.page),
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
    // 默认图层：Lore 是世界观文本（几百条），一上来全开会把地图糊掉
    const groups = new Set<string>()
    for (const g of currentMap.value?.groups ?? []) {
      if (!g.key.startsWith('Lore')) groups.add(g.key)
    }
    visibleGroups.value = groups
    const cats = new Set<string>()
    for (const c of catalog.value?.categories ?? []) cats.add(c.key)
    visibleCategories.value = cats
    selected.value = null
    highlight.value = null
  } finally {
    markersLoading.value = false
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
  selected.value = null
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
  if (target) selected.value = target
}

function onPick(m: Marker | null) {
  selected.value = m
  if (!m) highlight.value = null
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
  <section class="page map-page">
    <header class="head">
      <p class="eyebrow">雾锁王国 · 地图</p>
      <h1>全地图查看器</h1>
      <p class="lede">
        底图与点位来自 Enshrouded Wiki，另加本站补充的资源点与备注。
        拖动看图、滚轮缩放、点任意点位看详情；搜「铁矿」这类词会告诉你它离哪个地标最近、在地图的哪个方向。
      </p>
    </header>

    <p v-if="error" class="alert" role="alert">{{ error }}</p>

    <p v-if="loading" class="muted">正在载入地图数据…</p>

    <div v-else-if="!catalog || !catalog.maps.length" class="empty-state">
      <Icon name="map-pin" :size="26" />
      <h2>还没有地图数据</h2>
      <p>
        地图数据由管理员在后台「地图标注」页触发一次同步后就有了。
        同步会从 wiki 拉取底图信息与全部点位，只跑一次，之后是增量的。
      </p>
    </div>

    <div v-else class="board">
      <!-- ==================== 控制栏 ==================== -->
      <aside class="rail" aria-label="地图控制">
        <div class="block">
          <h2 class="block-title">地图</h2>
          <div class="chips">
            <button
              v-for="m in catalog.maps"
              :key="m.map"
              type="button"
              class="chip"
              :class="{ on: m.map === mapName }"
              :aria-pressed="m.map === mapName"
              @click="mapName = m.map"
            >
              {{ m.label }}
              <span class="chip-n">{{ fmt(m.markerCount) }}</span>
            </button>
          </div>
          <div v-if="(currentMap?.canvases.length ?? 0) > 1" class="chips sub">
            <button
              v-for="(c, i) in currentMap?.canvases"
              :key="c.page"
              type="button"
              class="chip small"
              :class="{ on: i === canvasIndex }"
              :aria-pressed="i === canvasIndex"
              @click="canvasIndex = i"
            >{{ c.label }}</button>
          </div>
        </div>

        <div class="block">
          <h2 class="block-title">找地方</h2>
          <form class="find" @submit.prevent="locate">
            <Input v-model="query" placeholder="铁矿 / 远古尖塔 / 木匠" autocapitalize="off" />
            <Button type="submit" variant="primary" size="sm" :disabled="locating">
              <Icon name="search" :size="15" />
              {{ locating ? '查找中' : '查找' }}
            </Button>
          </form>
          <p class="hint">按名字、别名、备注找；结果会标出它相对最近地标的方位。</p>

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
            地图上还没有标过「{{ located.q }}」。资源点可以让管理员补一个。
          </p>
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
            <span class="layer-n">几百条，默认关</span>
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
      </aside>

      <!-- ==================== 地图 ==================== -->
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
          mode="view"
          @pick="onPick"
        />

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

        <!-- 详情卡：右下角。地图不被遮挡太多，又离手最近 -->
        <article v-if="selected" class="detail">
          <header class="detail-head">
            <h3>{{ selected.name }}</h3>
            <button type="button" class="mini x" aria-label="关闭详情" @click="selected = null">
              <Icon name="close" :size="15" />
            </button>
          </header>
          <p class="detail-tags">
            <Tag :tone="selected.source === 'admin' ? 'flame' : 'neutral'">
              {{ selected.source === 'admin' ? '本地补充' : 'wiki' }}
            </Tag>
            <Tag tone="neutral">{{ categoryLabel.get(selected.category) ?? selected.category }}</Tag>
            <Tag v-if="selected.landmark" tone="warn">可作方位参照</Tag>
          </p>
          <p v-if="selected.original && selected.original !== selected.name" class="detail-line">
            wiki 原名：{{ selected.original }}
          </p>
          <p class="detail-line mono">坐标 ({{ fmt(selected.x) }}, {{ fmt(selected.y) }})</p>
          <p v-if="selected.note" class="detail-note">{{ selected.note }}</p>
          <p v-if="selected.description" class="detail-desc">{{ selected.description }}</p>
          <p v-if="selected.article" class="detail-line">
            <a :href="wikiUrl(selected)" target="_blank" rel="noopener noreferrer">
              在 wiki 上看这个词条
              <Icon name="external-link" :size="13" />
            </a>
          </p>
        </article>
      </div>
    </div>
  </section>
</template>

<style scoped>
.map-page { --board-h: clamp(460px, 74vh, 900px); }
.head { margin-bottom: var(--sp-5); }
.eyebrow {
  margin: 0 0 var(--sp-2);
  font-size: var(--fs-micro);
  letter-spacing: var(--tracking-label);
  text-transform: uppercase;
  color: var(--ember);
}
h1 {
  margin: 0 0 var(--sp-3);
  font-family: var(--font-title);
  font-size: var(--fs-2xl);
  letter-spacing: var(--tracking-ink);
  line-height: var(--lh-tight);
}
.lede { margin: 0; max-width: var(--read-max); color: var(--ink-2); }
.muted { color: var(--ink-3); }
.alert {
  margin: 0 0 var(--sp-4);
  padding: var(--sp-3) var(--sp-4);
  border-left: 3px solid var(--blight);
  background: var(--blight-veil);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  color: var(--blight-lift);
}

.empty-state {
  padding: var(--sp-7) var(--sp-5);
  text-align: center;
  background: var(--stone-300);
  border-radius: var(--r-md);
  box-shadow: var(--bevel-raised);
  color: var(--ink-2);
}
.empty-state h2 { margin: var(--sp-3) 0 var(--sp-2); font-size: var(--fs-lg); color: var(--ink); }
.empty-state p { margin: 0 auto; max-width: 46ch; }

/* ==================== 版面 ==================== */
.board {
  display: grid;
  grid-template-columns: 288px minmax(0, 1fr);
  gap: var(--sp-4);
  align-items: stretch;
}
@media (max-width: 900px) {
  .board { grid-template-columns: minmax(0, 1fr); }
}

/* ==================== 控制栏 ==================== */
.rail {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  max-height: var(--board-h);
  overflow-y: auto;
  padding: var(--sp-4);
  background: var(--stone-300);
  border-radius: var(--r-md);
  box-shadow: var(--bevel-raised);
}
@media (max-width: 900px) {
  .rail { max-height: none; order: 2; }
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
  padding: 0;
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
  padding: 4px 6px;
  border-radius: var(--r-xs);
  cursor: pointer;
  font-size: var(--fs-sm);
}
.layer:hover { background: var(--surface-hover); }
.layer input { accent-color: var(--ember); }
.layer-icon { width: 16px; height: 16px; object-fit: contain; }
.layer-name { flex: 1; color: var(--ink-2); }
.layer-n { color: var(--ink-4); font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }
.stand-alone { margin-top: var(--sp-2); border-top: 1px solid var(--edge-soft); padding-top: var(--sp-2); }

.dot { width: 9px; height: 9px; border-radius: 50%; background: var(--mist); }
.dot.d-resource { background: var(--vital); }
.dot.d-landmark { background: var(--ember); }
.dot.d-npc { background: var(--mist); }
.dot.d-poi { background: var(--ink-3); }
.dot.d-custom { background: var(--ember-hot); }

/* ==================== 舞台 ==================== */
.stage {
  position: relative;
  height: var(--board-h);
  border-radius: var(--r-md);
  overflow: hidden;
  box-shadow: var(--shadow-lift), var(--bevel-shelf);
  background: var(--stone-void);
}
@media (max-width: 900px) {
  .stage { order: 1; height: 62vh; }
}
.stage-tools {
  position: absolute;
  left: var(--sp-3);
  top: var(--sp-3);
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
.detail {
  position: absolute;
  right: var(--sp-3);
  bottom: var(--sp-3);
  width: min(340px, calc(100% - var(--sp-5)));
  max-height: calc(100% - var(--sp-6));
  overflow-y: auto;
  padding: var(--sp-3) var(--sp-4) var(--sp-4);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--stone-300) 94%, transparent);
  box-shadow: var(--shadow-pop), var(--bevel-raised);
  backdrop-filter: blur(8px);
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
</style>
