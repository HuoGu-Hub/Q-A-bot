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
  /**
   * 管理员在后台挑好的"默认打开的分组"。
   * null = 这张地图还没配置过，前端用内置经验值兜底；
   * 空数组 = 配置过，就是要全关。两者不能合并。
   */
  defaultGroups: string[] | null
}

/** 资源面板的一行：一种矿 / 一档箱子 / 一种植物…… */
export interface ResourceLayer {
  key: string
  name: string
  kind: string
  subtype: string
  count: number
  color: string
  /** point = 点位，area = 矩形面（林地） */
  shape: 'point' | 'area'
}

export interface ResourceCategory {
  key: string
  name: string
  count: number
  layers: ResourceLayer[]
}

export interface ResourceCatalog {
  ok: boolean
  map: string
  imported: boolean
  version: string
  importedAt: string
  message: string
  categories: ResourceCategory[]
}

/** 一块林地面。x0/z0 是西南角，x1/z1 是东北角 */
export interface MapArea {
  id: string
  group: string
  canvas: string
  x0: number
  z0: number
  x1: number
  z1: number
  color: string
  name: string
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
  source: 'wiki' | 'admin' | 'resource'
  /** 资源点没有图标，用这个颜色画圆点；wiki / 自定义点不填 */
  color?: string
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
  /** 正在跑时的进度：已处理多少项 / 共多少项。total 为 0 = 不知道总数，界面不画进度条 */
  done: number
  total: number
}

/**
 * 一条「底图变了、需要人复核」的自定义点位。
 *
 * <p>自定义点位存的是世界坐标，而"世界坐标落在底图的哪个像素上"由画布决定 ——
 * 画布是从 wiki 同步来的。对方换了取景窗口，点位就会整体偏移，且不会有任何报错。
 * 这个结构就是那个"不会有报错"的替代品。
 */
export interface AnchorReview {
  id: number
  map: string
  page: string
  name: string
  x: number
  y: number
  storedFp: string
  currentFp: string
  /** 人话说明变了哪一块，直接显示给管理员 */
  reason: string
}

export interface AnchorReport {
  ok: boolean
  total: number
  pending: number
  items: AnchorReview[]
}

/**
 * 一张候选地图。
 *
 * <p>同步会把 wiki 上 Map 命名空间里的地图**全部**拉进来（谁也不丢），
 * 用哪几张在这里由人决定。这就是"不把判断写死在配置里"的落点：
 * 游戏更新后 wiki 另起一页，它会自己出现在这份列表里等人决定，
 * 而不是被白名单静默跳过、让旧图继续以「最新」的样子留着。
 */
export interface MapCandidate {
  map: string
  markers: number
  /** wiki 最后改动这页的时间（空 = 没记录）。它是判断"哪张是新的"的客观信号之一 */
  lastChanged: string
  active: boolean
  /** 当前启用它是因为"自动兜底选点数最多那张"，而不是有人选过 */
  auto: boolean
}

export interface MapCandidates {
  ok: boolean
  active: string[]
  candidates: MapCandidate[]
}

export interface MapStatus {
  ok: boolean
  enabled: boolean
  unitsPerMeter: number
  directory: string
  mapStoreAvailable: boolean
  placesAvailable: boolean
  notesAvailable: boolean
  /** 底图变过、需要人复核的点位数。这是这套机制存在的全部意义，必须一眼可见 */
  anchorPending: number
  placeTotal: number
  /** latestAt = 缓存目录里最近一次写入（ISO）。它是跨重启的，任务状态不是 */
  tileCache: { enabled: boolean; root: string; files: number; bytes: number; latestAt: string }
  jobs: Record<string, JobState>
  /**
   * 每张画布一行，**不过滤启用范围** —— 候选地图表要靠它显示"未启用那张能不能渲染"。
   *
   * <p>只留界面真的会读的字段。原来还带 page / hasCrs / extent / syncedAt，
   * 那是给已经删掉的「画布」诊断表用的，留着只会让下一个人以为还有人在读。
   */
  canvases: {
    map: string; renderable: boolean
    background: string; tiles: number
  }[]
  pages: {
    page: string; map: string; revid: number; markerCount: number
    syncedAt: string; error: string
  }[]
  /** 库里的地点语料：条数与最后写入时间。它跨重启，任务状态不跨 */
  corpus: { blocks: number; latestAt: string }
  /**
   * 预热的**分单位覆盖**：哪个画布的哪一档拉齐了。
   *
   * <p>"542 个文件"回答不了"够不够" —— 出图要的是**某一档**完整。
   */
  warmCoverage: {
    map: string; mapLabel: string; unit: string; tier: string
    have: number; need: number
  }[]
  categories: { key: string; label: string }[]
}
