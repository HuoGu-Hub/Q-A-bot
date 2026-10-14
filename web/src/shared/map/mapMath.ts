import type { CanvasSpec, Marker } from './types'

/**
 * 地图坐标 ↔ 屏幕坐标的**纯函数**。
 *
 * 为什么要单独一个文件：这套换算是拖拽、缩放、打点、命中检测、画罗盘线的共同地基，
 * 混在组件里就没法单独测，也没法在公开端与管理端各用一份而保证行为一致
 * （管理端点下去落的位置必须和查看器显示的是同一个点）。
 *
 * 三层坐标：
 *   crs    —— wiki 给的 x/y（y 轴向上、原点在左下，数值可能是负数）
 *   world  —— 画布像素，左上角为 (0,0)，尺寸 = 画布 extent
 *   screen —— 视口像素，受 view 的平移与缩放影响
 */

export interface View {
  /** 屏幕像素 / world 像素 */
  k: number
  /** world 原点在屏幕上的位置 */
  tx: number
  ty: number
}

/** 画布的世界尺寸 */
export function extentOf(canvas: CanvasSpec): [number, number] {
  return [
    Math.abs(canvas.bottomRight[0] - canvas.topLeft[0]),
    Math.abs(canvas.topLeft[1] - canvas.bottomRight[1]),
  ]
}

/** crs → world */
export function worldOf(canvas: CanvasSpec, x: number, y: number): [number, number] {
  return [x - canvas.topLeft[0], canvas.topLeft[1] - y]
}

/** world → crs */
export function crsOf(canvas: CanvasSpec, wx: number, wy: number): [number, number] {
  return [wx + canvas.topLeft[0], canvas.topLeft[1] - wy]
}

/** world → screen */
export function screenOf(view: View, wx: number, wy: number): [number, number] {
  return [wx * view.k + view.tx, wy * view.k + view.ty]
}

/** screen → world */
export function worldAt(view: View, sx: number, sy: number): [number, number] {
  return [(sx - view.tx) / view.k, (sy - view.ty) / view.k]
}

/** 一个点位的 world 坐标 */
export function worldOfMarker(canvas: CanvasSpec, m: Marker): [number, number] {
  return worldOf(canvas, m.x, m.y)
}

/**
 * 让整张画布**完整装进视口**的缩放。
 *
 * 为什么初始视图用 contain 而不是 cover：cover 会把地图裁掉一部分，
 * 用户第一眼看到的是"半张图"，得先缩一次才知道全貌。
 * 看地图的第一件事是"我在哪、有多大"，所以先给全貌。
 */
export function containScale(canvas: CanvasSpec, vw: number, vh: number): number {
  const [w, h] = extentOf(canvas)
  if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0) return 0.05
  return Math.min(vw / w, vh / h)
}

/**
 * 缩放下限：略小于"完整装进视口"，再往外就只剩空白了。
 *
 * 留 0.9 的余量是为了让用户能把地图推离中心一点去对照边缘，
 * 但不允许缩到整张图只剩一个小点。
 */
export function minScale(canvas: CanvasSpec, vw: number, vh: number): number {
  return containScale(canvas, vw, vh) * 0.9
}

/**
 * 缩放上限的兜底值。真实值来自服务端的 {@code app.kb.map.max-zoom}，
 * 由目录接口下发 —— 它是"屏幕像素 / 原图像素"的比例，只有前端知道屏幕。
 */
export const DEFAULT_MAX_ZOOM = 2

/**
 * 低于这个缩放，**完全不画点位**。
 *
 * <p>为什么是"不画"而不是"改画圆点"：总览上几百个圆点同样分不出谁是谁，
 * 而且它们正好盖住了底图 —— 而那个缩放下用户要看的就是地形本身。
 * 也就是说圆点这个折中两边都不讨好，直接去掉。
 */
export const ICON_MIN_ZOOM = 0.22

/**
 * 夹缩放。
 *
 * 顺序是"先夹上限、再保证不低于 minScale"：画布比视口还小时，
 * 看清全貌比遵守放大上限更重要（否则地图会永远装不进屏幕）。
 */
export function clampScale(canvas: CanvasSpec, vw: number, vh: number, k: number,
                           maxZoom: number = DEFAULT_MAX_ZOOM): number {
  const cap = maxZoom > 0 ? maxZoom : DEFAULT_MAX_ZOOM
  return Math.max(minScale(canvas, vw, vh), Math.min(cap, k))
}

/**
 * 把视图夹在合理范围：画布不能被拖到完全看不见。
 *
 * 规则是"每一边至少留 15% 的内容可见"——比"必须完整可见"宽松，
 * 允许把边角挪到视野外去看相邻区域，但不允许拖走之后一片空白。
 */
export function clampView(canvas: CanvasSpec, vw: number, vh: number, view: View,
                          maxZoom: number = DEFAULT_MAX_ZOOM): View {
  const [w, h] = extentOf(canvas)
  const k = clampScale(canvas, vw, vh, view.k, maxZoom)
  const sw = w * k
  const sh = h * k
  /**
   * 每一边至少要留在视口里的**内容**宽度。
   *
   * 取 min 是因为"画布比视口还小"时必须换一套语义：那时不能让画布被推出视野，
   * 只能让它贴左边或贴右边。
   */
  const keepX = Math.min(sw, vw * 0.85)
  const keepY = Math.min(sh, vh * 0.85)
  // 画布左边界 tx 的合法区间：右边缘至少 keepX 进视口，左边缘至少 keepX 离左边缘
  const tx = Math.min(vw - keepX, Math.max(-sw + keepX, view.tx))
  const ty = Math.min(vh - keepY, Math.max(-sh + keepY, view.ty))
  return { k, tx, ty }
}

/** 以屏幕上的某点为锚做缩放：该点下方的地图内容保持不动 */
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

/**
 * 首屏视图：**至少放大到看得清图标**，同时不缩过头。
 *
 * <p>取 contain 与 ICON_MIN_ZOOM 的较大者，再留一点余量（1.15 倍）。
 * 对小地图（黑沼只有 1485×1267）contain 本来就远大于阈值，于是仍然一屏看全；
 * 对余烬谷这种 10240 的大图，contain 只有 0.08，一屏看全等于看一片点，
 * 所以首屏落在 0.25 左右 —— 大约显示四成地图，图标清晰、还看得出整体走向。
 *
 * <p>放大锚点是**画布中心**：从左上角放大等于把用户丢到地图角落里。
 */
export function initialView(canvas: CanvasSpec, vw: number, vh: number,
                           maxZoom: number = DEFAULT_MAX_ZOOM): View {
  const fit = fitView(canvas, vw, vh)
  const k = clampScale(canvas, vw, vh, Math.max(fit.k, ICON_MIN_ZOOM * 1.15), maxZoom)
  const [w, h] = extentOf(canvas)
  return clampView(canvas, vw, vh,
    { k, tx: vw / 2 - (w / 2) * k, ty: vh / 2 - (h / 2) * k }, maxZoom)
}

/** 让整张画布完整可见并居中 —— 首次打开与"复位"用 */
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

/** 把某个 world 点放到视口中心，保持当前缩放 */
export function centerOn(view: View, wx: number, wy: number, vw: number, vh: number): View {
  return { k: view.k, tx: vw / 2 - wx * view.k, ty: vh / 2 - wy * view.k }
}

/**
 * 让两个点都进视野（定位结果里有"锚点 ↔ 目标"两个点时用）。
 *
 * 这是在"看得见两个点"与"别缩得太小"之间的取舍：先算包住两点的缩放，
 * 再留 25% 边距，最后夹回允许范围。
 */
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

/**
 * 在该缩放下，"当前视口里"的点位。
 *
 * 视口裁剪是流畅度的关键：不裁剪的话每帧都要画上千个点，
 * 而屏幕实际只有一两百个。
 */
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

/** 命中检测：屏幕坐标附近最近的点位 */
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
