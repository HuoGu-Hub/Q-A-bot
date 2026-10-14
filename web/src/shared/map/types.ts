/**
 * 地图数据的前端类型 —— 与后端 DTO 一一对应。
 *
 * 单独一份而不是散在各页面里：公开查看器与管理端编辑器用的是**同一批接口**，
 * 各自写一遍类型，迟早会有一边字段名写错而 TypeScript 又看不出来。
 */

/** 一块底图瓦片。{@code col}/{@code row} 是相对 tileAt 的格子坐标，不是像素 */
export interface TileSpec {
  image: string
  col: number
  row: number
}

/**
 * 一张画布 —— 底图 + 坐标系。
 *
 * ⚠️ 同一张地图可能有**多张画布**（坐标系不同）：Embervale 的 Background 与 Collectibles
 * 原点就差着一万多。所以渲染必须按 canvas.page 分组，不能只按地图。
 */
export interface CanvasSpec {
  page: string
  label: string
  topLeft: [number, number]
  bottomRight: [number, number]
  tileAt: [number, number]
  tileSize: [number, number]
  tiles: TileSpec[]
  /** 旧 schema 的单张整图；与 tiles 二选一 */
  background: string
  backdrop: string
  zoomMin: number
  zoomMax: number
}

export interface GroupSpec {
  key: string
  name: string
  icon: string
  collectible: boolean
  landmark: boolean
  /** 分类键。用来决定默认开哪些图层：收集品默认关，不然总览糊成一片 */
  category: string
  count: number
}

export interface MapSpec {
  map: string
  label: string
  markerCount: number
  adminCount: number
  canvases: CanvasSpec[]
  groups: GroupSpec[]
}

export interface Catalog {
  ok: boolean
  enabled: boolean
  /** 1 米等于多少坐标单位；0 = 后端没有输出米 */
  unitsPerMeter: number
  /** 最大放大倍数（屏幕像素 / 原图像素）。超过它就是在放大像素，看着就是糊的 */
  maxZoom: number
  categories: { key: string; label: string }[]
  maps: MapSpec[]
}

/** 一个点位。wiki 点与管理员点在这一层长得一样，靠 source 区分 */
export interface Marker {
  id: string
  map: string
  page: string
  canvas: string
  group: string
  name: string
  /** wiki 上的原始名，便于对照 */
  original: string
  description: string
  article: string
  x: number
  y: number
  image: string
  landmark: boolean
  category: string
  priority: number
  note: string
  source: 'wiki' | 'admin'
}

export interface AnchorRef {
  ref: string
  name: string
  x: number
  y: number
  category: string
}

/** 相对位置描述 —— 由后端算好，前端只负责画与展示 */
export interface Relative {
  direction: string
  screenHint: string
  distance: number
  bucket: string
  bearing: number
  phrase: string
  anchor: AnchorRef
}

export interface LocateHit {
  ref: string
  map: string
  canvas: string
  name: string
  x: number
  y: number
  category: string
  note: string
  source: string
  relative?: Relative
}

export interface LocateResult {
  ok: boolean
  q: string
  found: boolean
  text: string
  hits: LocateHit[]
}

/** 新增/修改点位的请求体 */
export interface PlaceInput {
  map: string
  page: string
  x: number
  y: number
  name: string
  aliases: string[]
  category: string
  note: string
  icon: string
  color: string
  landmark: boolean
  priority: number
  visible: boolean
}

export interface Place extends PlaceInput {
  id: number
  createdAt: string
  updatedAt: string
}

export interface NoteInput {
  map: string
  page: string
  markerId: string
  alias: string
  note: string
  category: string
  landmark: boolean
  priority: number
  hidden: boolean
}

export interface JobState {
  name: string
  label: string
  running: boolean
  ok: boolean
  startedAt: string
  finishedAt: string
  summary: string
}

export interface MapStatus {
  ok: boolean
  enabled: boolean
  unitsPerMeter: number
  directory: string
  mapStoreAvailable: boolean
  placesAvailable: boolean
  notesAvailable: boolean
  tileCache: { enabled: boolean; root: string; files: number; bytes: number }
  jobs: Record<string, JobState>
  canvases: {
    map: string; page: string; hasCrs: boolean; renderable: boolean
    background: string; tiles: number; extent: [number, number]; syncedAt: string
  }[]
  pages: {
    page: string; map: string; revid: number; markerCount: number
    syncedAt: string; error: string
  }[]
  categories: { key: string; label: string }[]
}
