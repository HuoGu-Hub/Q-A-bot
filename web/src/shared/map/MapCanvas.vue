<script setup lang="ts">
/**
 * 地图画布 —— 底图瓦片 + canvas 打点 + 指针交互。
 *
 * <h2>为什么 marker 画在 canvas 上，而不是一堆 DOM 节点</h2>
 * wiki 那份地图卡顿的主因就在这里：一千多个 marker 都是 DOM 元素，
 * 拖动时每一帧都要为一千多个节点重算样式、重排、重绘。
 * 改成一张 canvas 之后，一帧只做一次绘制；DOM 里只剩"底图容器"与"canvas"两个节点。
 *
 * <h2>为什么底图用 CSS transform 而不是每帧改 left/top</h2>
 * 瓦片数量不多（余烬谷 64 张），但每帧给 64 个 img 写 left/top 会触发**布局**。
 * 改成"一个容器 + transform"之后，平移与缩放都只影响合成层，
 * 浏览器可以整层交给 GPU —— 这是"拖动跟手"的来源。
 *
 * <h2>三处刻意的取舍</h2>
 * <ol>
 *   <li><b>缩略图分级</b>：同一张瓦片按屏幕尺寸选 width 参数（160/320/640/1280）。
 *       缩得很小时拉原图等于白花几十倍流量；放大时用低清图会糊。</li>
 *   <li><b>视口裁剪 + 细节分级</b>：只画视野内的点；缩得太小时画圆点而不是图标
 *       —— 图标在小尺寸下本来就糊成一团，还慢。</li>
 *   <li><b>rAF 合帧</b>：指针事件一秒能来上百次，但屏幕只刷新 60 次。
 *       指针只更新目标值，真正的绘制与 transform 提交统一在 rAF 里做一次。</li>
 * </ol>
 */
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import type { CanvasSpec, Marker, Relative } from './types'
import { categoryColor } from './palette'
import {
  clampView, centerOn, crsOf, extentOf, fitPoints, fitView, hitTest,
  screenOf, visibleMarkers, worldAt, worldOf, worldOfMarker, zoomAt,
  type View,
} from './mapMath'

const props = withDefaults(
  defineProps<{
    canvas: CanvasSpec
    markers: Marker[]
    /** 显示的图层 key（wiki 分组） */
    visibleGroups: Set<string>
    /** 可见的分类（管理员点位用分类过滤） */
    visibleCategories: Set<string>
    /** 编辑模式：单击空白处会派发 pickedPoint */
    mode?: 'view' | 'edit'
    selectedId?: string
    /** 定位结果：画一条从锚点到目标的方位线 */
    highlight?: { toRef: string; relative: Relative } | null
    /** 正在创建的点位的 crs 坐标 */
    draft?: { x: number; y: number } | null
    /** 地图名，拼图片地址用 */
    mapName: string
    /** 最大放大倍数，来自服务端配置；缺省时用 2 */
    maxZoom?: number
  }>(),
  { mode: 'view', selectedId: '', highlight: null, draft: null, maxZoom: 2 },
)

const emit = defineEmits<{
  pick: [Marker | null]
  pickedPoint: [number, number]
  viewChange: [View]
}>()

const host = ref<HTMLElement | null>(null)
const canvasEl = ref<HTMLCanvasElement | null>(null)
const size = ref({ w: 0, h: 0 })
const view = ref<View>({ k: 1, tx: 0, ty: 0 })
const hovered = ref<Marker | null>(null)

/** 缩略图档位 —— 必须与服务端 {@code app.kb.map.widths} 白名单一致，否则会静默退回原图 */
const WIDTHS = [160, 320, 640, 1280]

/**
 * 当前该用哪一档缩略图。
 *
 * 判据是"这张瓦片在屏幕上会有多大"：取**不小于**它的最小档，
 * 保证不会把低清图放大（那是最容易看出来的糊）。
 */
const lod = computed(() => {
  const tilePx = (props.canvas.tileSize?.[0] || 1280) * view.value.k
  const want = WIDTHS.find((w) => w >= tilePx) ?? WIDTHS[WIDTHS.length - 1]
  return want >= 1280 ? 0 : want // 0 = 原图
})

const extent = computed(() => extentOf(props.canvas))
const bgBox = computed(() => {
  const e = extent.value
  return { w: e[0], h: e[1] }
})

/** 世界容器的 transform —— 拖拽时唯一会变的样式 */
const worldStyle = computed(() => {
  const v = view.value
  return {
    width: extent.value[0] + 'px',
    height: extent.value[1] + 'px',
    transform: 'translate3d(' + v.tx + 'px,' + v.ty + 'px,0) scale(' + v.k + ')',
  }
})

function tileUrl(name: string, w: number, withPage = true): string {
  const q = new URLSearchParams({ map: props.mapName, name })
  if (withPage && props.canvas.page) q.set('page', props.canvas.page)
  if (w > 0) q.set('w', String(w))
  return '/api/public/map/tile?' + q.toString()
}

/**
 * 每张瓦片在 world 坐标里的位置。
 *
 * 瓦片的 position 是**格子坐标**且 y 轴向上：列 col、行 row 的矩形从
 * (at.x + col*tw, at.y + row*th) 到右上角各加一个瓦片尺寸。
 * 换成 world（左上为原点、y 向下）之后，top 要用瓦片的**上边**算，
 * 所以是 row+1 —— 这里错一位整张图就会裂成两半。
 */
const tiles = computed(() => {
  const c = props.canvas
  const tw = c.tileSize?.[0] || 1280
  const th = c.tileSize?.[1] || 1280
  const at = c.tileAt ?? c.topLeft
  return c.tiles.map((t) => ({
    key: t.image,
    image: t.image,
    left: at[0] + t.col * tw - c.topLeft[0],
    top: c.topLeft[1] - (at[1] + (t.row + 1) * th),
    w: tw,
    h: th,
  }))
})

/* ==================== 可见点位 ==================== */

const shown = computed(() =>
  props.markers.filter((m) => {
    if (m.source === 'admin') return props.visibleCategories.has(m.category)
    return props.visibleGroups.has(m.group)
  }),
)

/* ==================== 主题色 ==================== */

const palette = shallowRef({
  ember: '#f2ab55',
  emberHot: '#ffd08a',
  vital: '#7ea36a',
  mist: '#8b9aab',
  warn: '#cfa334',
  ink: '#eae5da',
  inkDim: '#8b95a3',
  backdrop: props.canvas.backdrop || '#2e2e2c',
})

/** 颜色从主题令牌读一次，而不是写死 —— 页面换主题时地图跟着换 */
function readPalette() {
  const el = host.value
  const cs = el ? getComputedStyle(el) : null
  const get = (name: string, fb: string) => (cs?.getPropertyValue(name) || '').trim() || fb
  palette.value = {
    ember: get('--ember', '#f2ab55'),
    emberHot: get('--ember-hot', '#ffd08a'),
    vital: get('--vital', '#7ea36a'),
    mist: get('--mist', '#8b9aab'),
    warn: get('--warn', '#cfa334'),
    ink: get('--ink', '#eae5da'),
    inkDim: get('--ink-3', '#8b95a3'),
    backdrop: props.canvas.backdrop || '#2e2e2c',
  }
}

/**
 * 圆点颜色按**分类**取，自定义点位与 wiki 点位同色 ——
 * "是不是本地点位"用外圈一道灵火描边表示，不占用一个颜色维度。
 * 这样"绿色=资源"在两种来源上都成立，图例才说得通。
 */
function colorOf(m: Marker): string {
  return categoryColor(m.category)
}

/* ==================== 图标缓存 ==================== */

const iconCache = new Map<string, HTMLImageElement>()

/**
 * 图标在屏幕上的边长。
 *
 * <p><b>为什么不按缩放级别切换"图标 / 圆点"</b>：上一版在 k < 0.35 时只画圆点，
 * 而总览恰好就在那个区间 —— 于是用户看到的永远是一堆分不出类型的小圆点，
 * 而"分不出这是资源还是敌寨"正是他打开地图要解决的问题。
 *
 * <p>现在**永远画图标**，只让尺寸跟着缩放走：缩小时 14px、放大时 26px。
 * 总览上 14px 的图标仍然能看出形状差异，而且覆盖率只有一成多，不会糊成一片。
 */
function iconBox(k: number): number {
  return Math.max(14, Math.min(26, 8 + 24 * k))
}

/** 分组 key → 图标文件名。由父组件注入，省得为了图标名多调一次接口 */
const groupIcons = ref<Map<string, string>>(new Map())

function iconFor(m: Marker): HTMLImageElement | null {
  const file = groupIcons.value.get(m.group)
  if (!file) return null
  const cached = iconCache.get(file)
  if (cached) return cached.complete && cached.naturalWidth > 0 ? cached : null
  const img = new Image()
  img.decoding = 'async'
  // 图标原图只有 56px，画到 22~26 CSS 像素本来就是缩小，不需要服务端再缩
  //（48 也不在服务端的宽度白名单里，会被静默退回原图 —— 不如直接要原图）
  img.src = tileUrl(file, 0, false)
  // 图标是异步到的：到一张就重绘一次，而不是等全部到齐
  img.onload = () => schedule()
  iconCache.set(file, img)
  return null
}

/* ==================== 绘制 ==================== */

let raf = 0
let needsFit = true

function schedule() {
  if (raf) return
  raf = requestAnimationFrame(() => {
    raf = 0
    draw()
    emit('viewChange', view.value)
  })
}

function draw() {
  const el = canvasEl.value
  const w = size.value.w
  const h = size.value.h
  if (!el || w <= 0 || h <= 0) return
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  if (el.width !== Math.round(w * dpr) || el.height !== Math.round(h * dpr)) {
    el.width = Math.round(w * dpr)
    el.height = Math.round(h * dpr)
  }
  const ctx = el.getContext('2d')
  if (!ctx) return
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, w, h)

  const v = view.value
  const list = visibleMarkers(props.canvas, shown.value, v, w, h)
  const box = iconBox(v.k)
  /**
   * 屏幕上每个点位平均能分到多大一块地方。
   *
   * <p>不够摆下两个图标就退回**分类圆点**：总览上几百个图标互相压在一起，
   * 和"分不出类型"没有区别 —— 而圆点至少能用颜色把资源/地标/兴趣点/收集品分开，
   * 配合侧栏图例就能回答"这是资源还是敌寨"。放大到点与点拉开了，图标自然回来。
   */
  const spacing = Math.sqrt((w * h) / Math.max(1, list.length))
  // 3.2 倍边长：两个图标之间要留出足够空隙才叫"分辨得出"，挨着摆和叠着摆没区别。
  // 实测总览（四百多个点）落在这一侧走圆点，放大到十几倍就切回图标。
  const useIcons = spacing >= box * 3.2

  // 普通点先画、地标后画、选中的最后画 —— 保证"重要"和"被选中"的压在最上面
  const ordered = [...list].sort((a, b) => rank(a) - rank(b))
  for (const m of ordered) {
    const wp = worldOfMarker(props.canvas, m)
    const sp = screenOf(v, wp[0], wp[1])
    const sx = sp[0]
    const sy = sp[1]
    const selected = m.id === props.selectedId
    const hover = hovered.value === m

    if (selected) {
      ctx.beginPath()
      ctx.arc(sx, sy, 13, 0, Math.PI * 2)
      ctx.fillStyle = 'rgba(242,171,85,.20)'
      ctx.fill()
    }

    // 选中的那一个永远画图标 —— 用户刚点过它，必须看得清
    const img = useIcons || selected ? iconFor(m) : null
    if (img) {
      const s = selected ? box + 4 : box
      // 图标来自 wiki，深浅底图上都需要一圈暗底才看得清
      ctx.beginPath()
      ctx.arc(sx, sy, s / 2 + 1.5, 0, Math.PI * 2)
      ctx.fillStyle = 'rgba(8,10,14,.72)'
      ctx.fill()
      ctx.drawImage(img, sx - s / 2, sy - s / 2, s, s)
      // 自定义点位：外圈一道灵火，不靠换颜色区分来源
      if (m.source === 'admin') {
        ctx.beginPath()
        ctx.arc(sx, sy, s / 2 + 3, 0, Math.PI * 2)
        ctx.lineWidth = 1.6
        ctx.strokeStyle = palette.value.emberHot
        ctx.stroke()
      }
    } else {
      // 圆点要够大才看得出颜色差异 —— 3px 的色块在深色底图上几乎认不出来
      const r = selected || hover ? 6 : 4
      ctx.beginPath()
      ctx.arc(sx, sy, r, 0, Math.PI * 2)
      ctx.fillStyle = colorOf(m)
      ctx.fill()
      ctx.lineWidth = 1.2
      ctx.strokeStyle = 'rgba(8,10,14,.8)'
      ctx.stroke()
    }

    if (selected) {
      ctx.font = '600 12px system-ui, sans-serif'
      ctx.textAlign = 'center'
      const label = m.name
      const tw = ctx.measureText(label).width
      ctx.fillStyle = 'rgba(8,10,14,.82)'
      ctx.fillRect(sx - tw / 2 - 6, sy - 30, tw + 12, 18)
      ctx.fillStyle = palette.value.ink
      ctx.fillText(label, sx, sy - 17)
    }
  }

  drawHighlight(ctx, v, w, h)
  drawDraft(ctx, v)
}

function rank(m: Marker): number {
  if (m.id === props.selectedId) return 3
  if (m.landmark || m.source === 'admin') return 2
  return 1
}

/**
 * 方位线：从参照地标指到目标点，中点标出方向与距离。
 *
 * 目标点用 {@code ref} 精确定位而**不是按名字找** —— 同名点位可能有好几处
 * （火焰神龛一个词条 63 个），按名字会画到另一个上面，那是"看起来对但其实错"的典型。
 */
function drawHighlight(ctx: CanvasRenderingContext2D, v: View, w: number, h: number) {
  const hl = props.highlight
  if (!hl) return
  const target = shown.value.find((m) => m.id === hl.toRef)
  if (!target) return
  const a = worldOf(props.canvas, hl.relative.anchor.x, hl.relative.anchor.y)
  const t = worldOfMarker(props.canvas, target)
  const ap = screenOf(v, a[0], a[1])
  const tp = screenOf(v, t[0], t[1])
  const ax = ap[0]
  const ay = ap[1]
  const tx = tp[0]
  const ty = tp[1]
  if (Math.min(ax, tx) > w + 200 || Math.max(ax, tx) < -200) return
  if (Math.min(ay, ty) > h + 200 || Math.max(ay, ty) < -200) return

  ctx.save()
  ctx.setLineDash([6, 5])
  ctx.lineWidth = 1.6
  ctx.strokeStyle = palette.value.emberHot
  ctx.beginPath()
  ctx.moveTo(ax, ay)
  ctx.lineTo(tx, ty)
  ctx.stroke()
  ctx.setLineDash([])
  for (const p of [[ax, ay], [tx, ty]]) {
    ctx.beginPath()
    ctx.arc(p[0], p[1], 6, 0, Math.PI * 2)
    ctx.lineWidth = 2
    ctx.strokeStyle = palette.value.emberHot
    ctx.stroke()
  }

  const mx = (ax + tx) / 2
  const my = (ay + ty) / 2
  const text = hl.relative.direction + ' · 约' + hl.relative.distance
  ctx.font = '600 12px system-ui, sans-serif'
  ctx.textAlign = 'center'
  const tw = ctx.measureText(text).width
  ctx.fillStyle = 'rgba(8,10,14,.85)'
  ctx.fillRect(mx - tw / 2 - 7, my - 11, tw + 14, 20)
  ctx.fillStyle = palette.value.emberHot
  ctx.fillText(text, mx, my + 3)
  ctx.restore()
}

/** 编辑模式下的待落点：十字准星，松手前就能看清落在哪 */
function drawDraft(ctx: CanvasRenderingContext2D, v: View) {
  const d = props.draft
  if (!d) return
  const p = worldOf(props.canvas, d.x, d.y)
  const sp = screenOf(v, p[0], p[1])
  const sx = sp[0]
  const sy = sp[1]
  ctx.save()
  ctx.strokeStyle = palette.value.emberHot
  ctx.lineWidth = 1.6
  ctx.beginPath()
  ctx.arc(sx, sy, 10, 0, Math.PI * 2)
  ctx.stroke()
  ctx.beginPath()
  ctx.moveTo(sx - 16, sy)
  ctx.lineTo(sx + 16, sy)
  ctx.moveTo(sx, sy - 16)
  ctx.lineTo(sx, sy + 16)
  ctx.stroke()
  ctx.restore()
}

/* ==================== 交互 ==================== */

let dragging = false
let moved = 0
let lastX = 0
let lastY = 0
const pointers = new Map<number, { x: number; y: number }>()
let pinchBase = 0

function pointerDistance(): number {
  const pts = [...pointers.values()]
  if (pts.length < 2) return 0
  return Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y)
}

function midOfPointers(): [number, number] {
  const rect = host.value ? host.value.getBoundingClientRect() : ({ left: 0, top: 0 } as DOMRect)
  const pts = [...pointers.values()]
  return [(pts[0].x + pts[1].x) / 2 - rect.left, (pts[0].y + pts[1].y) / 2 - rect.top]
}

function localPoint(e: { clientX: number; clientY: number }): [number, number] {
  const rect = host.value ? host.value.getBoundingClientRect() : ({ left: 0, top: 0 } as DOMRect)
  return [e.clientX - rect.left, e.clientY - rect.top]
}

function onPointerDown(e: PointerEvent) {
  if (!host.value) return
  host.value.setPointerCapture(e.pointerId)
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  // 第二根手指按下时立刻停掉拖拽：否则"两指缩放"会同时被当成拖动，画面乱跳
  if (pointers.size === 2) {
    pinchBase = pointerDistance()
    dragging = false
    return
  }
  dragging = true
  moved = 0
  lastX = e.clientX
  lastY = e.clientY
}

function onPointerMove(e: PointerEvent) {
  if (!host.value) return
  if (pointers.has(e.pointerId)) pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })

  if (pointers.size === 2) {
    const d = pointerDistance()
    if (pinchBase > 0 && d > 0) {
      const mid = midOfPointers()
      const factor = d / pinchBase
      pinchBase = d
      view.value = clampView(props.canvas, size.value.w, size.value.h,
        zoomAt(view.value, mid[0], mid[1], factor), props.maxZoom)
      schedule()
    }
    return
  }

  if (!dragging) {
    const p = localPoint(e)
    const hit = hitTest(props.canvas, shown.value, view.value, p[0], p[1])
    if (hit !== hovered.value) {
      hovered.value = hit
      host.value.style.cursor = hit ? 'pointer' : props.mode === 'edit' ? 'crosshair' : 'grab'
      schedule()
    }
    return
  }

  const dx = e.clientX - lastX
  const dy = e.clientY - lastY
  lastX = e.clientX
  lastY = e.clientY
  moved += Math.abs(dx) + Math.abs(dy)
  const v = view.value
  view.value = clampView(props.canvas, size.value.w, size.value.h,
    { k: v.k, tx: v.tx + dx, ty: v.ty + dy }, props.maxZoom)
  schedule()
}

function onPointerUp(e: PointerEvent) {
  pointers.delete(e.pointerId)
  if (pointers.size < 2) pinchBase = 0
  if (!dragging) return
  dragging = false
  // 拖过就不算点击 —— 否则拖着看图会一路弹详情出来
  if (moved > 6) return
  const p = localPoint(e)
  const hit = hitTest(props.canvas, shown.value, view.value, p[0], p[1])
  if (hit) {
    emit('pick', hit)
    return
  }
  if (props.mode === 'edit') {
    const wp = worldAt(view.value, p[0], p[1])
    emit('pickedPoint', ...crsOf(props.canvas, wp[0], wp[1]))
  } else {
    emit('pick', null)
  }
}

function onWheel(e: WheelEvent) {
  const p = localPoint(e)
  const factor = e.deltaY < 0 ? 1.12 : 1 / 1.12
  view.value = clampView(props.canvas, size.value.w, size.value.h,
    zoomAt(view.value, p[0], p[1], factor), props.maxZoom)
  schedule()
}

function onDoubleClick(e: MouseEvent) {
  const p = localPoint(e)
  view.value = clampView(props.canvas, size.value.w, size.value.h,
    zoomAt(view.value, p[0], p[1], 1.8), props.maxZoom)
  schedule()
}

/* ==================== 生命周期 ==================== */

let ro: ResizeObserver | null = null

onMounted(() => {
  readPalette()
  ro = new ResizeObserver(() => {
    const el = host.value
    if (!el) return
    size.value = { w: el.clientWidth, h: el.clientHeight }
    if (needsFit && size.value.w > 0 && size.value.h > 0) {
      needsFit = false
      view.value = fitView(props.canvas, size.value.w, size.value.h)
    } else {
      view.value = clampView(props.canvas, size.value.w, size.value.h, view.value, props.maxZoom)
    }
    schedule()
  })
  if (host.value) ro.observe(host.value)
})

onBeforeUnmount(() => {
  ro?.disconnect()
  if (raf) cancelAnimationFrame(raf)
})

// 换画布要重新适配：不同底图的坐标系与尺寸都不一样，沿用旧视图必然错位
watch(
  () => props.canvas.page,
  () => {
    if (size.value.w > 0) {
      view.value = fitView(props.canvas, size.value.w, size.value.h)
      needsFit = false
    } else {
      needsFit = true
    }
    schedule()
  },
)

watch(() => [props.markers, props.selectedId, props.highlight, props.draft], schedule)
watch(() => [props.visibleGroups, props.visibleCategories], schedule)

defineExpose({
  /** 父组件注入分组图标表 */
  setGroupIcons(map: Map<string, string>) {
    groupIcons.value = map
    iconCache.clear()
    schedule()
  },
  fit() {
    view.value = fitView(props.canvas, size.value.w, size.value.h)
    schedule()
  },
  zoomBy(factor: number) {
    view.value = clampView(props.canvas, size.value.w, size.value.h,
      zoomAt(view.value, size.value.w / 2, size.value.h / 2, factor), props.maxZoom)
    schedule()
  },
  /** 把某个 crs 坐标放到视野中心 */
  focus(x: number, y: number, k?: number) {
    const wp = worldOf(props.canvas, x, y)
    const base = { k: k ?? Math.max(view.value.k, 0.6), tx: view.value.tx, ty: view.value.ty }
    view.value = clampView(props.canvas, size.value.w, size.value.h,
      centerOn(base, wp[0], wp[1], size.value.w, size.value.h), props.maxZoom)
    schedule()
  },
  /** 让锚点与目标同时进视野 */
  showPair(fromX: number, fromY: number, toX: number, toY: number) {
    view.value = fitPoints(props.canvas,
      [worldOf(props.canvas, fromX, fromY), worldOf(props.canvas, toX, toY)],
      size.value.w, size.value.h, props.maxZoom)
    schedule()
  },
  scale() {
    return view.value.k
  },
})
</script>

<template>
  <div
    ref="host"
    class="map-host"
    :style="{ background: palette.backdrop }"
    role="application"
    aria-label="地图画布"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
    @wheel.prevent="onWheel"
    @dblclick="onDoubleClick"
  >
    <!--
      size 还没测出来时**不渲染瓦片**：那时 view 还是 k=1 的初值，
      会按原图档位发起一整轮请求，而第一次 fit 之后这些请求全部作废。
      实测这会让首屏白白多下十几兆。
    -->
    <div v-if="size.w > 0" class="world" :style="worldStyle">
      <!-- 新 schema：瓦片集 -->
      <img
        v-for="t in tiles"
        :key="t.key"
        class="tile"
        :src="tileUrl(t.image, lod)"
        :style="{ left: t.left + 'px', top: t.top + 'px', width: t.w + 'px', height: t.h + 'px' }"
        alt=""
        draggable="false"
        decoding="async"
      />
      <!-- 旧 schema：一整张大图 -->
      <img
        v-if="!tiles.length && canvas.background"
        class="tile"
        :src="tileUrl(canvas.background, lod, false)"
        :style="{ left: '0px', top: '0px', width: bgBox.w + 'px', height: bgBox.h + 'px' }"
        alt=""
        draggable="false"
        decoding="async"
      />
    </div>

    <canvas ref="canvasEl" class="dots" :style="{ width: size.w + 'px', height: size.h + 'px' }" />

    <p class="sr-only" role="status" aria-live="polite">{{ shown.length }} 个点位可见</p>
  </div>
</template>

<style scoped>
.map-host {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  touch-action: none;
  cursor: grab;
  user-select: none;
  /* 隔离外部布局：地图内部的重绘不该反复触发外层排版 */
  contain: layout paint;
}
.world {
  position: absolute;
  left: 0;
  top: 0;
  transform-origin: 0 0;
  will-change: transform;
}
.tile {
  position: absolute;
  display: block;
  pointer-events: none;
}
.dots {
  position: absolute;
  left: 0;
  top: 0;
  pointer-events: none;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
</style>
