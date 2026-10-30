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
import type { CanvasSpec, MapArea, Marker, Relative } from './types'
import {
  ICON_MIN_ZOOM, clampView, centerOn, crsOf, extentOf, fitPoints, fitView,
  hitTest, hitTestAll, initialView, screenOf, visibleMarkers, worldAt, worldOf,
  worldOfMarker, zoomAt,
  type View,
} from './mapMath'

const props = withDefaults(
  defineProps<{
    canvas: CanvasSpec
    markers: Marker[]
    /** 林地面等矩形层；与 markers 分开，因为它们不是点 */
    areas?: MapArea[]
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
  { mode: 'view', selectedId: '', highlight: null, draft: null, maxZoom: 2, areas: () => [] },
)

const emit = defineEmits<{
  /**
   * 选中了点位。**是一个数组**，空数组表示点到了空白处。
   *
   * <p>为什么不是单个：同一个像素上可能压着好几个点位，
   * 只给最近的那个等于让用户永远看不到另外几个。上层用堆叠卡片展示它们。
   */
  pick: [Marker[]]
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
 * 底座层的档位。
 *
 * <p>固定用最低档，而且**它自己永远不换档** —— 它的唯一职责就是"换档期间不露黑底"，
 * 一旦它也跟着换，就等于把黑闪问题原样搬到了下一层。
 */
const BASE_WIDTH = 160

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

/** 旧 schema 那张整图的位置与尺寸 —— 底座层与细节层共用 */
const bgBoxStyle = computed(() => ({
  left: '0px',
  top: '0px',
  width: bgBox.value.w + 'px',
  height: bgBox.value.h + 'px',
}))

/** 世界容器的 transform —— 拖拽时唯一会变的样式 */
const worldStyle = computed(() => {
  const v = view.value
  return {
    width: extent.value[0] + 'px',
    height: extent.value[1] + 'px',
    transform: 'translate3d(' + v.tx + 'px,' + v.ty + 'px,0) scale(' + v.k + ')',
  }
})

/** 瓦片在 world 层里的位置与尺寸 —— 底座层与细节层共用同一份，两层才能严丝合缝 */
function tileBox(t: { left: number; top: number; w: number; h: number }) {
  return {
    left: t.left + 'px',
    top: t.top + 'px',
    width: t.w + 'px',
    height: t.h + 'px',
  }
}

/**
 * 图片加载失败后的重试次数上限。
 *
 * <p><b>为什么必须重试</b>：取图接口是异步的，队列满了会回 503，而上游确实可能
 * 没有这张图会回 404。浏览器对 {@code <img>} 的失败**不会自己重发** ——
 * 不重试的话那一格就一直空着，且 {@link iconCache} 里存的还是那张加载失败的对象，
 * 要等组件重挂才会再试一次。
 *
 * <p><b>为什么有上限</b>：无脑重试对着真的不存在的图只会白刷日志。
 * 三次之后放弃，让那一格保持缺失 —— 比一直转圈诚实。
 */
const MAX_RETRY = 3

/**
 * 重试版本号：key = 图层 + 文件名 + 档位，值 = 已经重试过几次。
 *
 * <p>它的作用是**换 URL**。src 不变的话浏览器会直接复用上次的失败结果，
 * 根本不会再发一次请求，所以重试必须让 URL 变。
 */
const retryVersion = ref<Record<string, number>>({})

function retryKey(name: string, w: number, withPage: boolean): string {
  return (withPage ? 'p:' : 'n:') + name + '@' + w
}

/** 记一次失败并安排重试；到上限就不再折腾 */
function scheduleTileRetry(name: string, w: number, withPage: boolean): void {
  const key = retryKey(name, w, withPage)
  const attempt = retryVersion.value[key] ?? 0
  if (attempt >= MAX_RETRY) return
  // 退避递增：队列满通常几秒内就缓解，一上来就密集重试只会让它更慢。
  // ⚠️ 新值必须在定时器里**基于当时的 map** 算：同一时刻可能有别的瓦片也在失败，
  //    提前算好的快照会把它刚写进去的次数覆盖掉
  window.setTimeout(() => {
    retryVersion.value = { ...retryVersion.value, [key]: attempt + 1 }
  }, 600 * (attempt + 1))
}

function tileUrl(name: string, w: number, withPage = true, bust?: number): string {
  const q = new URLSearchParams({ map: props.mapName, name })
  if (withPage && props.canvas.page) q.set('page', props.canvas.page)
  if (w > 0) q.set('w', String(w))
  const v = bust ?? retryVersion.value[retryKey(name, w, withPage)]
  if (v) q.set('v', String(v))
  return '/api/public/v1/map/tile?' + q.toString()
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
  labelBg: 'rgba(8,10,14,.82)',
  labelInk: '#eae5da',
  labelEdge: 'rgba(255,255,255,.12)',
  labelAccent: '#ffd08a',
  ring: 'rgba(234,229,218,.55)',
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
    // 名牌与方位徽标：日间是纸底深字，夜间是深底浅字，两边都必须能读
    labelBg: get('--map-label-bg', 'rgba(8,10,14,.82)'),
    labelInk: get('--map-label-ink', '#eae5da'),
    labelEdge: get('--map-label-edge', 'rgba(255,255,255,.12)'),
    labelAccent: get('--map-label-accent', '#ffd08a'),
    ring: get('--map-ring', 'rgba(234,229,218,.55)'),
  }
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
  const resource = !!m.icon
  const file = m.icon || groupIcons.value.get(m.group)
  if (!file) return null
  const key = (resource ? 'r:' : 'w:') + file
  const cached = iconCache.get(key)
  if (cached) return cached.complete && cached.naturalWidth > 0 ? cached : null
  const img = new Image()
  img.decoding = 'async'
  loadIcon(img, file, 0, resource)
  iconCache.set(key, img)
  return null
}

function resourceIconUrl(file: string, attempt: number): string {
  const base = '/api/public/v1/map/resource-icon?name=' + encodeURIComponent(file)
  return attempt > 0 ? base + '&r=' + attempt : base
}

/**
 * 加载一张分组图标，失败就换 URL 重来。
 *
 * <p><b>为什么图标也要重试</b>：图标走的是 {@code <canvas>} 绘制，没有
 * {@code <img @error>} 那条路，失败必须自己接住。而这张元素会被
 * {@link iconCache} 记下来 —— 不重试就等于"这个分组从此没有图标"，
 * 要等组件重挂才会再试。
 */
function loadIcon(img: HTMLImageElement, file: string, attempt: number,
                  resource = false): void {
  // 图标原图只有 56px，画到 22~26 CSS 像素本来就是缩小，不需要服务端再缩
  //（48 也不在服务端的宽度白名单里，会被静默退回原图 —— 不如直接要原图）
  // attempt=0 时不带 v，正常路径的 URL 保持干净，浏览器缓存才有意义
  img.src = resource ? resourceIconUrl(file, attempt) : tileUrl(file, 0, false, attempt)
  // 图标是异步到的：到一张就重绘一次，而不是等全部到齐
  img.onload = () => schedule()
  img.onerror = () => {
    if (attempt >= MAX_RETRY) return
    window.setTimeout(() => loadIcon(img, file, attempt + 1, resource), 600 * (attempt + 1))
  }
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
  drawAreas(ctx, v, w, h)
  // 缩放不够就一个点都不画：那个尺度上图标只会叠成一团，还挡住底图
  const raw = v.k >= ICON_MIN_ZOOM ? visibleMarkers(props.canvas, shown.value, v, w, h) : []
  const list = thinResources(raw)
  const box = iconBox(v.k)

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

    const img = iconFor(m)
    if (!img && m.color) {
      // 资源点没有图标：纯色圆点 + 细描边，不铺暗底
      const r = Math.max(3.2, Math.min(6.5, 2.5 + 4 * v.k))
      ctx.beginPath()
      ctx.arc(sx, sy, r, 0, Math.PI * 2)
      ctx.fillStyle = m.color
      ctx.fill()
      ctx.lineWidth = 1
      ctx.strokeStyle = 'rgba(8,10,14,.5)'
      ctx.stroke()
      if (selected) {
        ctx.beginPath()
        ctx.arc(sx, sy, r + 5, 0, Math.PI * 2)
        ctx.lineWidth = 1.6
        ctx.strokeStyle = palette.value.emberHot
        ctx.stroke()
        drawMarkerLabel(ctx, sx, sy, m.name)
      }
      continue
    }
    if (!img) {
      /*
       * 图标还没到：画一个**空心圈**占位。
       *
       * 为什么不能干脆不画：图标要走 wiki 取，冷缓存或上游限流时首屏会持续几十秒，
       * 那段时间整张地图上一个点都没有 —— 看上去就是"点位全丢了"，而这正是用户报过的现象。
       *
       * 为什么是空圈而不是实心圆点：实心圆点是"缩放太小"的降级显示，语义是"这就是最终样子"；
       * 空圈是"正在加载"，两者必须长得不一样，否则用户会以为地图就长这样。
       */
      ctx.beginPath()
      ctx.arc(sx, sy, box / 2, 0, Math.PI * 2)
      ctx.lineWidth = 1.5
      ctx.strokeStyle = palette.value.ring
      ctx.stroke()
      continue
    }
    if (img) {
      // 物品图标比 wiki 的线稿稍大，否则缩略图上只是一颗深色小点
      const s = m.source === 'resource'
        ? Math.max(18, Math.min(32, 11 + 26 * v.k))
        : (selected ? box + 4 : box)
      // 透明底保留，只用阴影勾边：浅色线稿在浅色地图上也能看见
      ctx.save()
      ctx.shadowColor = m.source === 'resource'
        ? 'rgba(255,248,230,.55)'
        : 'rgba(8,10,14,.65)'
      ctx.shadowBlur = 2
      ctx.drawImage(img, sx - s / 2, sy - s / 2, s, s)
      ctx.restore()
      // 自定义点位：外圈一道灵火，不靠换颜色区分来源
      if (m.source === 'admin') {
        ctx.beginPath()
        ctx.arc(sx, sy, s / 2 + 3, 0, Math.PI * 2)
        ctx.lineWidth = 1.6
        ctx.strokeStyle = palette.value.emberHot
        ctx.stroke()
      }
    }

    if (selected) {
      drawMarkerLabel(ctx, sx, sy, m.name)
    }
  }

  drawHighlight(ctx, v, w, h)
  drawDraft(ctx, v)
}

/** 林地之类的面数据：半透明填充 + 细边，画在所有点下面 */
function drawAreas(ctx: CanvasRenderingContext2D, v: View, w: number, h: number) {
  const areas = props.areas ?? []
  if (!areas.length) return
  // 同一层的矩形合成一条路径再 fill：相邻格子的接缝不会叠成深色条纹
  const groups = new Map<string, { color: string; rects: number[][] }>()
  for (const a of areas) {
    if (!props.visibleGroups.has(a.group)) continue
    if (a.canvas && a.canvas !== props.canvas.page) continue
    const tl = worldOf(props.canvas, a.x0, a.z1)
    const br = worldOf(props.canvas, a.x1, a.z0)
    const x = tl[0] * v.k + v.tx
    const y = tl[1] * v.k + v.ty
    const ww = (br[0] - tl[0]) * v.k
    const hh = (br[1] - tl[1]) * v.k
    if (ww <= 0 || hh <= 0) continue
    if (x + ww < 0 || y + hh < 0 || x > w || y > h) continue
    const g = groups.get(a.group) ?? { color: a.color, rects: [] }
    g.rects.push([x, y, ww, hh])
    groups.set(a.group, g)
  }
  ctx.save()
  for (const g of groups.values()) {
    ctx.beginPath()
    for (const r of g.rects) ctx.rect(r[0], r[1], r[2], r[3])
    ctx.globalAlpha = 0.28
    ctx.fillStyle = g.color
    ctx.fill()
  }
  ctx.restore()
}

/** 选中的点位在头顶画一块名牌，底与描边都跟主题走 */
function drawMarkerLabel(ctx: CanvasRenderingContext2D, sx: number, sy: number, label: string) {
  ctx.font = '600 12px system-ui, sans-serif'
  ctx.textAlign = 'center'
  const tw = ctx.measureText(label).width
  const bx = sx - tw / 2 - 6
  const by = sy - 30
  const bw = tw + 12
  const bh = 18
  ctx.fillStyle = palette.value.labelBg
  ctx.fillRect(bx, by, bw, bh)
  ctx.lineWidth = 1
  ctx.strokeStyle = palette.value.labelEdge
  // +0.5 让 1px 描边落在像素中心，否则边缘会糊成两像素灰
  ctx.strokeRect(bx + 0.5, by + 0.5, bw - 1, bh - 1)
  ctx.fillStyle = palette.value.labelInk
  ctx.fillText(label, sx, sy - 17)
}

/**
 * 资源点太多时按等距抽样。
 *
 * <p>36,126 个点全画会拖慢每一帧，而全图尺度上它们本来就是一片麻点。
 * 抽样只影响"一次画多少"，不影响数据：放大到局部后视口里的点变少，
 * 抽样自然停止，每个点都会出现。
 */
function thinResources(list: Marker[]): Marker[] {
  const wiki: Marker[] = []
  const res: Marker[] = []
  for (const m of list) {
    if (m.source === 'resource') res.push(m)
    else wiki.push(m)
  }
  if (res.length <= 4000) return wiki.concat(res)
  const step = Math.ceil(res.length / 4000)
  const kept: Marker[] = []
  for (let i = 0; i < res.length; i += step) kept.push(res[i])
  return wiki.concat(kept)
}

function rank(m: Marker): number {
  if (m.id === props.selectedId) return 3
  if (m.source === 'resource') return 0
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
  const bx = mx - tw / 2 - 7
  const by = my - 11
  ctx.fillStyle = palette.value.labelBg
  ctx.fillRect(bx, by, tw + 14, 20)
  ctx.lineWidth = 1
  ctx.strokeStyle = palette.value.labelEdge
  ctx.strokeRect(bx + 0.5, by + 0.5, tw + 13, 19)
  ctx.fillStyle = palette.value.labelAccent
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
    // 新增点位模式下点位不可点，悬停也就不该给出"可点"的反馈
    const hit = props.mode === 'edit'
      ? null
      : hitTest(props.canvas, shown.value, view.value, p[0], p[1])
    if (hit !== hovered.value) {
      hovered.value = hit
      host.value.style.cursor = props.mode === 'edit' ? 'crosshair' : hit ? 'pointer' : 'grab'
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
  if (props.mode === 'edit') {
    /*
     * 新增点位模式：**完全不做命中测试**。
     *
     * 踩过一次坑：以前先测命中、命中就选中，测不到才落点。
     * 于是"想在敌营旁边插一个矿点"，手一抖点在敌营图标上就变成编辑敌营了 ——
     * 而用户当时心里想的是"我在新增"。既然按钮叫新增点位，
     * 这个模式下每一次点击都必须是新增，哪怕正好压在已有点位上。
     * 想编辑已有点位，退出这个模式再点它。
     */
    const wp = worldAt(view.value, p[0], p[1])
    emit('pickedPoint', ...crsOf(props.canvas, wp[0], wp[1]))
    return
  }
  emit('pick', hitTestAll(props.canvas, shown.value, view.value, p[0], p[1]))
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
/**
 * 主题变化监听。
 *
 * <p>画布上的点位、文字、选中框全部是 canvas 画的，不经过 CSS 继承 ——
 * 只改 data-theme 不会让它们变色，必须重新读一次令牌再重绘。
 * 这是切主题时最容易漏的一处：页面都换了，只有地图还停在旧配色。
 */
let themeObserver: MutationObserver | null = null

onMounted(() => {
  readPalette()
  themeObserver = new MutationObserver(() => {
    readPalette()
    schedule()
  })
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  ro = new ResizeObserver(() => {
    const el = host.value
    if (!el) return
    size.value = { w: el.clientWidth, h: el.clientHeight }
    if (needsFit && size.value.w > 0 && size.value.h > 0) {
      needsFit = false
      view.value = initialView(props.canvas, size.value.w, size.value.h, props.maxZoom)
    } else {
      view.value = clampView(props.canvas, size.value.w, size.value.h, view.value, props.maxZoom)
    }
    schedule()
  })
  if (host.value) ro.observe(host.value)
})

onBeforeUnmount(() => {
  ro?.disconnect()
  themeObserver?.disconnect()
  if (raf) cancelAnimationFrame(raf)
})

// 换画布要重新适配：不同底图的坐标系与尺寸都不一样，沿用旧视图必然错位
watch(
  () => props.canvas.page,
  () => {
    if (size.value.w > 0) {
      view.value = initialView(props.canvas, size.value.w, size.value.h, props.maxZoom)
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
      <!--
        ==================== 瓦片分两层 ====================

        **底座层**：固定最低档位，src 永不改变。
        **细节层**：跟随缩放换档。

        为什么必须分两层：细节层换档改的是同一张 img 的 src，而浏览器会在
        src 改变的那一刻**立刻丢掉旧图**、等新图到达 —— 于是整张地图在换档瞬间变黑。
        滚轮缩放会连续跨过好几档，看起来就是"一闪一闪的黑色频闪"。

        有了底座层，换档期间露出来的是低清地图而不是黑底，观感上只是"由糊变清"。
        底座只有 160px 一档、64 张，一次之后就全在浏览器缓存里，代价很小。
      -->
      <template v-if="tiles.length">
        <img
          v-for="t in tiles"
          :key="'base-' + t.key"
          class="tile"
          :src="tileUrl(t.image, BASE_WIDTH)"
          :style="tileBox(t)"
          @error="scheduleTileRetry(t.image, BASE_WIDTH, true)"
          alt=""
          draggable="false"
          decoding="async"
        />
        <template v-if="lod !== BASE_WIDTH">
          <img
            v-for="t in tiles"
            :key="'detail-' + t.key"
            class="tile"
            :src="tileUrl(t.image, lod)"
            :style="tileBox(t)"
            @error="scheduleTileRetry(t.image, lod, true)"
            alt=""
            draggable="false"
            decoding="async"
          />
        </template>
      </template>
      <!-- 旧 schema：一整张大图，同样分两层 -->
      <template v-else-if="canvas.background">
        <img
          class="tile"
          :src="tileUrl(canvas.background, BASE_WIDTH, false)"
          :style="bgBoxStyle"
          @error="scheduleTileRetry(canvas.background, BASE_WIDTH, false)"
          alt=""
          draggable="false"
          decoding="async"
        />
        <img
          v-if="lod !== BASE_WIDTH"
          class="tile"
          :src="tileUrl(canvas.background, lod, false)"
          :style="bgBoxStyle"
          @error="scheduleTileRetry(canvas.background, lod, false)"
          alt=""
          draggable="false"
          decoding="async"
        />
      </template>
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