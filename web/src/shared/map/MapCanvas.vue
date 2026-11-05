<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import type { CanvasSpec, MapArea, Marker, Relative } from './types'
import {
  ICON_MIN_ZOOM, clampView, centerOn, crsOf, extentOf, fitPoints, fitView,
  hitTest, hitTestAll, initialView, screenOf, visibleMarkers, worldAt, worldOf,
  worldOfMarker, zoomAtClamped,
  type View,
} from './mapMath'

const props = withDefaults(
  defineProps<{
    canvas: CanvasSpec
    markers: Marker[]
    areas?: MapArea[]
    visibleGroups: Set<string>
    visibleCategories: Set<string>
    mode?: 'view' | 'edit'
    selectedId?: string
    highlight?: { toRef: string; relative: Relative } | null
    draft?: { x: number; y: number } | null
    mapName: string
    maxZoom?: number
  }>(),
  { mode: 'view', selectedId: '', highlight: null, draft: null, maxZoom: 2, areas: () => [] },
)

const emit = defineEmits<{
  pick: [Marker[]]
  pickedPoint: [number, number]
  viewChange: [View]
}>()

const host = ref<HTMLElement | null>(null)
const canvasEl = ref<HTMLCanvasElement | null>(null)
const size = ref({ w: 0, h: 0 })
const view = ref<View>({ k: 1, tx: 0, ty: 0 })
const hovered = ref<Marker | null>(null)

const WIDTHS = [160, 320, 640, 1280]

const BASE_WIDTH = 160

const lod = computed(() => {
  const tilePx = (props.canvas.tileSize?.[0] || 1280) * view.value.k
  const want = WIDTHS.find((w) => w >= tilePx) ?? WIDTHS[WIDTHS.length - 1]
  return want >= 1280 ? 0 : want
})

const extent = computed(() => extentOf(props.canvas))
const bgBox = computed(() => {
  const e = extent.value
  return { w: e[0], h: e[1] }
})

const bgBoxStyle = computed(() => ({
  left: '0px',
  top: '0px',
  width: bgBox.value.w + 'px',
  height: bgBox.value.h + 'px',
}))

const worldStyle = computed(() => {
  const v = view.value
  return {
    width: extent.value[0] + 'px',
    height: extent.value[1] + 'px',
    transform: 'translate3d(' + v.tx + 'px,' + v.ty + 'px,0) scale(' + v.k + ')',
  }
})

function tileBox(t: { left: number; top: number; w: number; h: number }) {
  return {
    left: t.left + 'px',
    top: t.top + 'px',
    width: t.w + 'px',
    height: t.h + 'px',
  }
}

const MAX_RETRY = 3

const retryVersion = ref<Record<string, number>>({})

function retryKey(name: string, w: number, withPage: boolean): string {
  return (withPage ? 'p:' : 'n:') + name + '@' + w
}

function scheduleTileRetry(name: string, w: number, withPage: boolean): void {
  const key = retryKey(name, w, withPage)
  const attempt = retryVersion.value[key] ?? 0
  if (attempt >= MAX_RETRY) return
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

const shown = computed(() =>
  props.markers.filter((m) => {
    if (m.source === 'admin') return props.visibleCategories.has(m.category)
    return props.visibleGroups.has(m.group)
  }),
)

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
    labelBg: get('--map-label-bg', 'rgba(8,10,14,.82)'),
    labelInk: get('--map-label-ink', '#eae5da'),
    labelEdge: get('--map-label-edge', 'rgba(255,255,255,.12)'),
    labelAccent: get('--map-label-accent', '#ffd08a'),
    ring: get('--map-ring', 'rgba(234,229,218,.55)'),
  }
}

const iconCache = new Map<string, HTMLImageElement>()

function iconBox(k: number): number {
  return Math.max(14, Math.min(26, 8 + 24 * k))
}

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

function loadIcon(img: HTMLImageElement, file: string, attempt: number,
                  resource = false): void {
  img.src = resource ? resourceIconUrl(file, attempt) : tileUrl(file, 0, false, attempt)
  img.onload = () => schedule()
  img.onerror = () => {
    if (attempt >= MAX_RETRY) return
    window.setTimeout(() => loadIcon(img, file, attempt + 1, resource), 600 * (attempt + 1))
  }
}

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
  const raw = v.k >= ICON_MIN_ZOOM ? visibleMarkers(props.canvas, shown.value, v, w, h) : []
  const list = thinResources(raw)
  const box = iconBox(v.k)

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
      ctx.beginPath()
      ctx.arc(sx, sy, box / 2, 0, Math.PI * 2)
      ctx.lineWidth = 1.5
      ctx.strokeStyle = palette.value.ring
      ctx.stroke()
      continue
    }
    if (img) {
      const s = m.source === 'resource'
        ? Math.max(18, Math.min(32, 11 + 26 * v.k))
        : (selected ? box + 4 : box)
      ctx.save()
      ctx.shadowColor = m.source === 'resource'
        ? 'rgba(255,248,230,.55)'
        : 'rgba(8,10,14,.65)'
      ctx.shadowBlur = 2
      ctx.drawImage(img, sx - s / 2, sy - s / 2, s, s)
      ctx.restore()
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

function drawAreas(ctx: CanvasRenderingContext2D, v: View, w: number, h: number) {
  const areas = props.areas ?? []
  if (!areas.length) return
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
  ctx.strokeRect(bx + 0.5, by + 0.5, bw - 1, bh - 1)
  ctx.fillStyle = palette.value.labelInk
  ctx.fillText(label, sx, sy - 17)
}

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
      view.value = zoomAtClamped(props.canvas, size.value.w, size.value.h,
        view.value, mid[0], mid[1], factor, props.maxZoom)
      schedule()
    }
    return
  }

  if (!dragging) {
    const p = localPoint(e)
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
  if (moved > 6) return
  const p = localPoint(e)
  if (props.mode === 'edit') {
    const wp = worldAt(view.value, p[0], p[1])
    emit('pickedPoint', ...crsOf(props.canvas, wp[0], wp[1]))
    return
  }
  emit('pick', hitTestAll(props.canvas, shown.value, view.value, p[0], p[1]))
}

function onWheel(e: WheelEvent) {
  const p = localPoint(e)
  const factor = e.deltaY < 0 ? 1.12 : 1 / 1.12
  view.value = zoomAtClamped(props.canvas, size.value.w, size.value.h,
    view.value, p[0], p[1], factor, props.maxZoom)
  schedule()
}

function onDoubleClick(e: MouseEvent) {
  const p = localPoint(e)
  view.value = zoomAtClamped(props.canvas, size.value.w, size.value.h,
    view.value, p[0], p[1], 1.8, props.maxZoom)
  schedule()
}

let ro: ResizeObserver | null = null
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
    view.value = zoomAtClamped(props.canvas, size.value.w, size.value.h,
      view.value, size.value.w / 2, size.value.h / 2, factor, props.maxZoom)
    schedule()
  },
  focus(x: number, y: number, k?: number) {
    const wp = worldOf(props.canvas, x, y)
    const base = { k: k ?? Math.max(view.value.k, 0.6), tx: view.value.tx, ty: view.value.ty }
    view.value = clampView(props.canvas, size.value.w, size.value.h,
      centerOn(base, wp[0], wp[1], size.value.w, size.value.h), props.maxZoom)
    schedule()
  },
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
    <div v-if="size.w > 0" class="world" :style="worldStyle">
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
