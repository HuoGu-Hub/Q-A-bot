import type { CanvasSpec, Marker } from './types'

export interface View {
  k: number
  tx: number
  ty: number
}

export function extentOf(canvas: CanvasSpec): [number, number] {
  return [
    Math.abs(canvas.bottomRight[0] - canvas.topLeft[0]),
    Math.abs(canvas.topLeft[1] - canvas.bottomRight[1]),
  ]
}

export function worldOf(canvas: CanvasSpec, x: number, y: number): [number, number] {
  return [x - canvas.topLeft[0], canvas.topLeft[1] - y]
}

export function crsOf(canvas: CanvasSpec, wx: number, wy: number): [number, number] {
  return [wx + canvas.topLeft[0], canvas.topLeft[1] - wy]
}

export function screenOf(view: View, wx: number, wy: number): [number, number] {
  return [wx * view.k + view.tx, wy * view.k + view.ty]
}

export function worldAt(view: View, sx: number, sy: number): [number, number] {
  return [(sx - view.tx) / view.k, (sy - view.ty) / view.k]
}

export function worldOfMarker(canvas: CanvasSpec, m: Marker): [number, number] {
  return worldOf(canvas, m.x, m.y)
}

export function containScale(canvas: CanvasSpec, vw: number, vh: number): number {
  const [w, h] = extentOf(canvas)
  if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0) return 0.05
  return Math.min(vw / w, vh / h)
}

export function minScale(canvas: CanvasSpec, vw: number, vh: number): number {
  return containScale(canvas, vw, vh) * 0.9
}

export const DEFAULT_MAX_ZOOM = 2

export const ICON_MIN_ZOOM = 0.22

export function clampScale(canvas: CanvasSpec, vw: number, vh: number, k: number,
                           maxZoom: number = DEFAULT_MAX_ZOOM): number {
  const cap = maxZoom > 0 ? maxZoom : DEFAULT_MAX_ZOOM
  return Math.max(minScale(canvas, vw, vh), Math.min(cap, k))
}

export function clampView(canvas: CanvasSpec, vw: number, vh: number, view: View,
                          maxZoom: number = DEFAULT_MAX_ZOOM): View {
  const [w, h] = extentOf(canvas)
  const k = clampScale(canvas, vw, vh, view.k, maxZoom)
  const sw = w * k
  const sh = h * k
  const keepX = Math.min(sw, vw * 0.85)
  const keepY = Math.min(sh, vh * 0.85)
  const tx = Math.min(vw - keepX, Math.max(-sw + keepX, view.tx))
  const ty = Math.min(vh - keepY, Math.max(-sh + keepY, view.ty))
  return { k, tx, ty }
}

export function zoomAt(view: View, sx: number, sy: number, factor: number): View {
  const k = view.k * factor
  if (k <= 0) return view
  const ratio = k / view.k
  return {
    k,
    tx: sx - (sx - view.tx) * ratio,
    ty: sy - (sy - view.ty) * ratio,
  }
}

export function zoomAtClamped(
  canvas: CanvasSpec,
  vw: number,
  vh: number,
  view: View,
  sx: number,
  sy: number,
  factor: number,
  maxZoom: number = DEFAULT_MAX_ZOOM,
): View {
  const k = clampScale(canvas, vw, vh, view.k * factor, maxZoom)
  const ratio = view.k > 0 ? k / view.k : 1
  return clampView(canvas, vw, vh, zoomAt(view, sx, sy, ratio), maxZoom)
}

export function initialView(canvas: CanvasSpec, vw: number, vh: number,
                           maxZoom: number = DEFAULT_MAX_ZOOM): View {
  const fit = fitView(canvas, vw, vh)
  const k = clampScale(canvas, vw, vh, Math.max(fit.k, ICON_MIN_ZOOM * 1.15), maxZoom)
  const [w, h] = extentOf(canvas)
  return clampView(canvas, vw, vh,
    { k, tx: vw / 2 - (w / 2) * k, ty: vh / 2 - (h / 2) * k }, maxZoom)
}

export function fitView(canvas: CanvasSpec, vw: number, vh: number): View {
  const [w, h] = extentOf(canvas)
  if (w <= 0 || h <= 0) return { k: 1, tx: 0, ty: 0 }
  const k = containScale(canvas, vw, vh)
  return {
    k,
    tx: (vw - w * k) / 2,
    ty: (vh - h * k) / 2,
  }
}

export function centerOn(view: View, wx: number, wy: number, vw: number, vh: number): View {
  return { k: view.k, tx: vw / 2 - wx * view.k, ty: vh / 2 - wy * view.k }
}

export function fitPoints(
  canvas: CanvasSpec,
  pts: [number, number][],
  vw: number,
  vh: number,
  maxZoom: number = DEFAULT_MAX_ZOOM,
): View {
  if (pts.length === 0) return fitView(canvas, vw, vh)
  const xs = pts.map((p) => p[0])
  const ys = pts.map((p) => p[1])
  const minX = Math.min(...xs)
  const maxX = Math.max(...xs)
  const minY = Math.min(...ys)
  const maxY = Math.max(...ys)
  const spanX = Math.max(1, maxX - minX)
  const spanY = Math.max(1, maxY - minY)
  const k = clampScale(canvas, vw, vh, Math.min(vw / spanX, vh / spanY) * 0.75, maxZoom)
  const cx = (minX + maxX) / 2
  const cy = (minY + maxY) / 2
  return clampView(canvas, vw, vh, { k, tx: vw / 2 - cx * k, ty: vh / 2 - cy * k }, maxZoom)
}

export function visibleMarkers(
  canvas: CanvasSpec,
  markers: Marker[],
  view: View,
  vw: number,
  vh: number,
  pad = 64,
): Marker[] {
  const out: Marker[] = []
  for (const m of markers) {
    const [wx, wy] = worldOfMarker(canvas, m)
    const sx = wx * view.k + view.tx
    const sy = wy * view.k + view.ty
    if (sx >= -pad && sx <= vw + pad && sy >= -pad && sy <= vh + pad) out.push(m)
  }
  return out
}

export function hitTest(
  canvas: CanvasSpec,
  markers: Marker[],
  view: View,
  sx: number,
  sy: number,
  radius = 14,
): Marker | null {
  let best: Marker | null = null
  let bestD = radius * radius
  for (const m of markers) {
    const [wx, wy] = worldOfMarker(canvas, m)
    const dx = wx * view.k + view.tx - sx
    const dy = wy * view.k + view.ty - sy
    const d = dx * dx + dy * dy
    if (d <= bestD) {
      bestD = d
      best = m
    }
  }
  return best
}

export function hitTestAll(
  canvas: CanvasSpec,
  markers: Marker[],
  view: View,
  sx: number,
  sy: number,
  radius = 14,
): Marker[] {
  const hits: { m: Marker; d: number }[] = []
  const r2 = radius * radius
  for (const m of markers) {
    const [wx, wy] = worldOfMarker(canvas, m)
    const dx = wx * view.k + view.tx - sx
    const dy = wy * view.k + view.ty - sy
    const d = dx * dx + dy * dy
    if (d <= r2) {
      hits.push({ m, d })
    }
  }
  hits.sort((a, b) => a.d - b.d)
  return hits.map((h) => h.m)
}
