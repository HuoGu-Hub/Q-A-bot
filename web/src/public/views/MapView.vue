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

/**
 * 悬浮控制栏是否展开。
 *
 * <p>默认展开：第一次进来必须看得见"哪里能搜、哪里能开关图层" ——
 * 一上来就收起等于把这些功能藏起来。用户收起一次之后，这一次访问内保持收起。
 */
const railOpen = ref(true)

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

/**
 * 分布：当前画布上**实际可见**的分类与数量。
 *
 * <p>不配色块了。原来每个分类带一个颜色圆点，是为了解释地图上的兜底圆点；
 * 现在点位一律画分组图标、缩得太小就不画，颜色在地图上不再出现，
 * 再摆一排色块等于指着一个地图上不存在的东西让人对照。
 */
const legend = computed(() => {
  const counts = new Map<string, number>()
  for (const m of markersOnCanvas.value) {
    // 只数可见的点：图层关掉的分类不该还挂在上面
    const on = m.source === 'admin'
      ? visibleCategories.value.has(m.category)
      : visibleGroups.value.has(m.group)
    if (!on) continue
    counts.set(m.category, (counts.get(m.category) ?? 0) + 1)
  }
  return [...counts.entries()]
    .map(([key, count]) => ({ key, count, label: categoryLabel.value.get(key) ?? key }))
    .sort((a, b) => b.count - a.count)
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
    // 默认图层：只开"找地方"用得上的。
    // 收集品（化石、乐谱、壁毯）与世界观文本加起来五百多条，全开会让总览糊成一片，
    // 而它们并不是"资源在哪、敌营在哪"这类问题的答案。想要的人自己在图层里打开。
    const groups = new Set<string>()
    for (const g of currentMap.value?.groups ?? []) {
      if (g.category !== 'collection') groups.add(g.key)
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
        <p>
          地图数据由管理员在后台「地图标注」页触发一次同步后就有了。
          同步会从 wiki 拉取底图信息与全部点位，只跑一次，之后是增量的。
        </p>
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

        <aside v-show="railOpen" id="map-rail" class="rail" aria-label="地图控制">
          <div class="rail-head">
            <span class="rail-title">{{ currentMap?.label ?? '地图' }}</span>
            <button
              type="button"
              class="rail-collapse"
              aria-controls="map-rail"
              :aria-expanded="true"
              aria-label="收起面板"
              @click="railOpen = false"
            >
              <Icon name="close" :size="15" />
            </button>
          </div>
        <!-- 一张地图有多个画布时才需要切换；地图本身不做切换入口 -->
        <div v-if="(currentMap?.canvases.length ?? 0) > 1" class="block">
          <h2 class="block-title">图层</h2>
          <div class="chips">
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

        <div v-if="legend.length" class="block">
          <h2 class="block-title">分布</h2>
          <ul class="legend">
            <li v-for="c in legend" :key="c.key">
              <span class="layer-name">{{ c.label }}</span>
              <span class="layer-n">{{ c.count }}</span>
            </li>
          </ul>
          <p class="hint">
            每个点位画的是它所属分组的图标，具体分组见下面的图层列表。
            带灵火外圈的是本站补充的点位；缩得太小时不画点位，放大即可看到。
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
.rail-collapse { padding: 4px 6px; border-radius: var(--r-sm); }
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
@media (max-width: 760px) {
  /* 手机上不给固定 288px：左右留边、纵向收一半，别把地图全盖住 */
  .rail {
    left: var(--sp-2);
    right: var(--sp-2);
    top: var(--sp-2);
    bottom: auto;
    width: auto;
    max-height: 62%;
  }
  .rail-toggle { left: var(--sp-2); top: var(--sp-2); }
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

.legend { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }
.legend li { display: flex; align-items: center; gap: var(--sp-2); padding: 3px 6px; font-size: var(--fs-sm); }
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
.detail {
  position: absolute;
  z-index: 2;
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
