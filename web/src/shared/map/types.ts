export interface TileSpec {
  image: string
  col: number
  row: number
}

export interface CanvasSpec {
  page: string
  label: string
  topLeft: [number, number]
  bottomRight: [number, number]
  tileAt: [number, number]
  tileSize: [number, number]
  tiles: TileSpec[]
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
  defaultGroups: string[] | null
}

export interface ResourceLayer {
  key: string
  name: string
  kind: string
  subtype: string
  count: number
  color: string
  icon: string
  shape: 'point' | 'area'
}

export interface ResourceCategory {
  key: string
  name: string
  count: number
  layers: ResourceLayer[]
}

export interface ResourceCatalog {
  map: string
  imported: boolean
  version: string
  importedAt: string
  message: string
  categories: ResourceCategory[]
}

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
  enabled: boolean
  unitsPerMeter: number
  maxZoom: number
  categories: { key: string; label: string }[]
  maps: MapSpec[]
}

export interface Marker {
  id: string
  map: string
  page: string
  canvas: string
  group: string
  name: string
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
  color?: string
  icon?: string
}

export interface AnchorRef {
  ref: string
  name: string
  x: number
  y: number
  category: string
}

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
  q: string
  found: boolean
  text: string
  hits: LocateHit[]
}

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

export interface JobState {
  name: string
  label: string
  running: boolean
  ok: boolean
  startedAt: string
  finishedAt: string
  summary: string
  done: number
  total: number
}

export interface AnchorReview {
  id: number
  map: string
  page: string
  name: string
  x: number
  y: number
  storedFp: string
  currentFp: string
  reason: string
}

export interface AnchorReport {
  ok: boolean
  total: number
  pending: number
  items: AnchorReview[]
}

export interface MapCandidate {
  map: string
  markers: number
  lastChanged: string
  active: boolean
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
  anchorPending: number
  placeTotal: number
  tileCache: { enabled: boolean; root: string; files: number; bytes: number; latestAt: string }
  jobs: Record<string, JobState>
  canvases: {
    map: string; renderable: boolean
    background: string; tiles: number
  }[]
  pages: {
    page: string; map: string; revid: number; markerCount: number
    syncedAt: string; error: string
  }[]
  corpus: { blocks: number; latestAt: string }
  warmCoverage: {
    map: string; mapLabel: string; unit: string; tier: string
    have: number; need: number
  }[]
  categories: { key: string; label: string }[]
}
