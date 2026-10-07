/**
 * 后端返回的数据类型。
 *
 * 与 Java 侧的 record 一一对应 —— 改后端时记得同步这里。
 * （如果以后接口多了，可以考虑用 springdoc 自动生成，现在手写更直接。）
 */

/* ==================== 知识库 ==================== */

/** 一条知识库分块（公开站只暴露 title/url/text，不暴露内部分数） */
export interface KbEntry {
  title: string
  url: string
  text: string
  cats?: string[]
}

export interface KbSearchResult {
  query: string
  count: number
  /** semantic = 走了向量语义检索（认中文口语）；keyword = 只走了本地术语表关键词 */
  mode?: 'semantic' | 'keyword'
  entries: KbEntry[]
}

export interface KbCategory {
  name: string
  count: number
}

/* ==================== 公开统计 ==================== */

/** 公开统计：刻意不含用户数/群数等内部指标 */
export interface PublicStats {
  /**
   * 提问量。
   * ⚠️ 原来这个字段叫 totalQuestions，但后端取的是「全部群消息」——
   * 含群里没 @ 机器人的闲聊，对外宣称的提问数因此错了约 17 倍。
   * 现在的定义：@ 了机器人 + 机器人有效回应且未被拦截。
   */
  questions: number
  hitRate: number
  kbEntries: number
  glossaryTerms: number
  since: string
}

/* ==================== 管理后台 ==================== */

/**
 * 看板总览。
 *
 * ⚠️ 口径（2026-09-26 确认）：**提问 = @ 了机器人 + 有效回应且未被拦截**
 * （后端 `guard_action='pass'`）。对照下面的字段：
 *   questions ← 唯一能叫「提问」的数
 *   dropped / fixedReplies ← 被拦的两种，都不是提问
 *   commands ← 有回应，但不是提问
 *   total ← 群消息总数，含闲聊与被拦的，**不是**提问数
 */
export interface Overview {
  /** 群消息总数：含没 @ 机器人的闲聊、被黑名单/限流拦下的。不是提问数 */
  total: number
  /** 提问量（= pass）。@ 了机器人 + 有效回应且未被拦截 */
  questions: number
  /** 提问中检索到资料的条数 */
  hits: number
  /** 提问命中率。分母是 questions，不是 total */
  hitRate: number
  /** 提过问的独立用户数。⚠️ 原先没按提问过滤，会把闲聊的人也算进来 */
  users: number
  /** 提过问的独立群数 */
  groups: number
  /** 被拦：没 @ / 黑名单 / Guard 拦截 / 预算超限 / kill-switch */
  dropped: number
  /** 被拦：回固定话术（限流提示 / 敏感词 / 纯表情兜底 / 预算） */
  fixedReplies: number
  /** 命中的指令数（如 /help）。有回应，但不是提问 */
  commands: number
  p50RetrieveMs: number
  p95RetrieveMs: number
  p50TotalMs: number
  p95TotalMs: number
  guardActions: Record<string, number>
  /** 每日提问量（不是每日消息量） */
  daily: DayCount[]
}

export interface DayCount { day: string; count: number }

export interface KeywordStat { zh: string; en: string; count: number; missCount: number }

export interface MissStat {
  zh: string
  en: string
  count: number
  /** 向量检索卡 min-score 阈值【之前】的最高余弦（见 UnmatchedMiss 的说明） */
  bestCosineRaw: number
  samples: string[]
}

/**
 * bestCosineRaw = 向量检索在应用 min-score 阈值【之前】的最高余弦。
 *
 * 用途：区分两种"未命中"——
 *   · 接近阈值（0.35~0.45）：库里其实有相关内容，只是差一点被挡 → 考虑调低阈值
 *   · 很低（0.2 以下）：库里确实没有这份资料 → 该补资料，调阈值没用
 *   · 0：这次没走向量路（向量未启用 / 老记录没有这一列）
 */
export interface UnmatchedMiss { ts: string; question: string; bestCosineRaw: number }

export interface MissResponse {
  withKeyword: MissStat[]
  unmatched: UnmatchedMiss[]
}

export interface Bucket { range: string; count: number }

export interface SourceStat { source: string; count: number; hits: number; hitRate: number }

export interface VerdictStat { verdict: string; count: number }

export interface SourcesResponse {
  sources: SourceStat[]
  verdicts: VerdictStat[]
}

export interface RecordRow {
  id: number
  ts: string
  groupId: number
  userId: number
  hitCount: number
  bestCosine: number | null
  sources: string
  guardAction: string
  retrieveMs: number
  totalMs: number
  question: string | null
  answer: string | null
  verdict: string | null
}

export interface VisitDay { day: string; count: number }

export interface VisitSummary {
  total: number
  visitors: number
  daily: VisitDay[]
  paths: SourceStat[]
}

/* ==================== 系统日志 ==================== */

export interface LogEntry {
  seq: number
  ts: string
  level: 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | string
  message: string
  throwable?: string
}

export interface LogHistory {
  entries: LogEntry[]
  currentSeq: number
}

export interface LogStatus {
  enabled: boolean
  bufferSize: number
  capacity: number
  totalWritten: number
  dropped: number
  maskSensitive: boolean
  currentSeq: number
  activeStreams: number
}

/* ==================== 词条（术语表并入 kb_term 之后） ====================
   术语表原先是一个独立的 TSV 文件，和「关键词」各管一半同一批对象。
   后端把它收成 kb_term 单表后，两边的接口合并成 /kb/terms*，
   下面这套类型是它的唯一来源 —— 旧的 GlossaryXxx 已随面板一并删除。 */

/**
 * 一条词条 = 一个 Wiki 页面。
 *
 * en 是主键（页面标题），正文块按它分组；zh / status 是原先术语表那部分。
 * aliases 由后端按 、/｜| 从 zh 拆好，前端不再各拆一遍（拆法不一致就是 bug）。
 */
export interface KbTerm {
  /** 英文名 = Wiki 页面标题，也是主键 */
  en: string
  /** 中文名（空字符串 = 还没中文名）；多别名用「、」分隔 */
  zh: string
  /** 中文名拆出来的别名 */
  aliases: string[]
  /** 'draft' | 'verified' | 'rejected'｜'' = 未收录中文名 */
  status: string
  board: string
  boardLabel: string
  cats: string[]
  chunkCount: number
  chars: number
  url: string
  /** 该词条的**全部**文本块都已下架 */
  retired: boolean
}

/**
 * 各口径的词条数（预设筛选与进度条共用）。
 *
 * ⚠️ 与已删除的 GlossaryCounts 一样：**不受 q / 其它筛选影响** ——
 * 它表达的是「全库有多少条处于这个状态」，不是「当前搜索结果里有多少条」。
 * 否则筛一下数字就变，进度条会跟着跳。
 */
export interface KbTermCounts {
  all: number
  /** 有正文 或 有中文名 —— 列表默认口径 */
  main: number
  withChunks: number
  noChunk: number
  draft: number
  verified: number
  rejected: number
  unnamed: number
}

export interface KbTermBoard {
  key: string
  label: string
  icon: string
  desc: string
  terms: number
  chunks: number
}

export interface KbTermListResponse {
  total: number
  items: KbTerm[]
  counts: KbTermCounts
  boards: KbTermBoard[]
  /** 词条库（kb_term）是否就绪；false 时列表为空 */
  available: boolean
}

/** 批量改状态的结果。missing 是没对上的英文名 —— 一条对不上不会连累其余的。 */
export interface KbTermBatchResponse {
  ok: boolean
  updated: number
  unchanged: number
  missing: string[]
  counts: KbTermCounts
}

export interface KbTermChunk {
  /** 块 id（如 flame-altar-0）。原来是行号 —— 块身份改造之后改为 id */
  id: string
  text: string
  url: string
  chars: number
  retired: boolean
}

/** 单条取块：title 可能带 /（如 Sets/Clothes），所以一律走 query 参数 */
export interface KbTermChunksResponse {
  title: string
  chunks: KbTermChunk[]
}

/** Excel/WPS 旁路：导出拿到的是 JSON 包着的表格文本（client 永远走 res.json()） */
export interface KbTermExportResponse {
  ok: boolean
  /** 老字段名，值同 text —— 后端两个都给了 */
  tsv: string
  /** 按 ?format= 决定的文本：csv（带 UTF-8 BOM）或 tsv */
  text?: string
  /** format=xlsx 时的文件字节（base64）—— 二进制塞不进 JSON 文本字段 */
  xlsxBase64?: string
  format?: 'xlsx' | 'tsv'
  count: number
}

export interface KbTermImportResponse {
  ok: boolean
  created: number
  updated: number
  skipped: number
}

/* ==================== 指令系统 ==================== */

export interface BotCommand {
  id: number
  trigger: string
  reply: string
  description: string
  scope: string
  groupIds: number[]
  minRole: string
  enabled: boolean
  sortOrder: number
  builtin: boolean
  /** template = 话术（不调模型、不计费）；agent = 智能问答（调模型，会走成本预算） */
  kind: string
  /** agent 类的回答模式：kb = 走知识库（默认）；none = 不用知识库 */
  mode: string
}

export interface CommandList {
  available: boolean
  commands: BotCommand[]
  requireMention: boolean
  requireSlash: boolean
  rateLimitPerMinute: number
  allowUserIds: boolean
}

export interface CommandVariable {
  name: string
  label: string
  example: string
  advanced: boolean
}

export interface CommandVariables {
  variables: CommandVariable[]
  preview: Record<string, string>
  allowUserIds: boolean
}

export interface CommandUsage {
  trigger: string
  count: number
}

export interface CommandStats {
  topUsed: CommandUsage[]
  unmatched: CommandUsage[]
  total: number
}

/* ==================== 知识库块（文档导入之后的新模型） ====================
   与旧的「词条 / 文本块（按行号操作）」并存到切换为止。
   这里全部按 **块 id** 操作 —— 行号那套是旧存储的产物，切过去就退休。 */

/** 块存储概览 */
export interface KbBlockStats {
  available: boolean
  /** 块总数 */
  blocks: number
  /** 其中已下架 */
  retired: number
  /** 文档数 */
  documents: number
  /** 已算好向量的块数（少于 blocks 说明有半成品） */
  vectors: number
  /**
   * 已算好**标题向量**的块数（C 路：用物品名提问时靠它把本体顶上来）。
   * 少于 blocks 不影响用，只是那些块吃不到这一路 —— 补一次即可。
   */
  titleVectors: number
}

/** 一份文档的块数概览 */
export interface KbDocSummary {
  docId: string
  blocks: number
  retired: number
}

/** 一块（管理端视图） */
export interface KbBlockView {
  id: string
  docId: string
  title: string
  text: string
  url: string
  tags: string[]
  source: string
  retired: boolean
  updatedAt: string
}

/**
 * 导入预览（**只算不改**）。
 *
 * 规则：文件里出现的 id 存在就覆盖、不存在就新增；**没出现的块一个都不动**。
 */
export interface KbImportPreview {
  ok: boolean
  added: number
  updated: number
  unchanged: number
  /** 库里有多少块这次完全没被碰 */
  untouchedExisting: number
  addedIds: string[]
  updatedIds: string[]
  warnings: string[]
}

/** 导入结果 */
export interface KbImportResult {
  ok: boolean
  added: number
  updated: number
  unchanged: number
  untouched: number
  /** 这次登记/补齐的词条数（导入一份文档 = 它作为一个页面进词条表） */
  terms: number
  warnings: string[]
}

/* ==================== Bot 设置 ==================== */

export interface SettingItem {
  key: string
  label: string
  group: string
  type: string
  hint: string
  value: unknown
}

export interface SettingsView {
  groups: Record<string, SettingItem[]>
  applyCount: number
  note: string
}

/* ==================== 模型配置 ==================== */

export interface ModelProvider {
  name: string
  configured: boolean
  ready: boolean
  modelName: string
}

export interface ModelList {
  providers: ModelProvider[]
  defaultProvider: string
  fallbackChain: string[]
  note: string
}

export interface ProbeResult {
  ok: boolean
  elapsedMs?: number
  reply?: string
  error?: string
}

export interface AvailableModels {
  ok: boolean
  models: string[]
  count?: number
  error?: string
}

/* ==================== 问答广场 ==================== */

export interface PlazaAnswer {
  statId: number
  question: string
  answer: string
  up: number
  down: number
  outdated: number
  badge: string | null
}

export interface PlazaKeywordPage {
  keyword: string
  termEn: string | null
  askedCount: number
  needsNewAnswer: boolean
  needsHelp: boolean
  onlyVoted: boolean
  answers: PlazaAnswer[]
}

export interface PlazaKeywordItem {
  keyword: string
  termEn: string | null
  count: number
  votedCount: number
}

export interface PlazaKeywords {
  keywords: PlazaKeywordItem[]
  count: number
}

/* ==================== 问答广场（管理端） ==================== */

export interface PlazaOverview {
  enabled: boolean
  onlyVoted: boolean
  voteCount: number
  helpCount: number
  downvoteThreshold: number
  usage: {
    askTodayTotal: number
    askLimitGlobal: number
    askLimitPerKeyword: number
    helpCount: number
  }
}

export interface PlazaAdminAnswer {
  statId: number
  ts: string
  groupId: number
  userId: number
  source: string
  question: string
  answer: string
  up: number
  down: number
  outdated: number
}

export interface PlazaHelpRequest {
  ts: string
  keyword: string | null
  question: string
  groupId: number
  userId: number
  status: string
}

/* ==================== 知识库管理 ==================== */




/* ==================== 分类规划 ==================== */

export interface CategoryGroup {
  key: string
  label: string
  icon: string
  desc: string
}

export interface CategoryStat {
  key: string
  label: string
  icon: string
  desc: string
  rawCount: number
  chunkCount: number
}

export interface CategoryItem {
  raw: string
  count: number
  groupKey: string
  groupLabel: string
  labelZh: string
  /** 被手动改过（否则跟着自动规则走） */
  custom: boolean
  hidden: boolean
}

export interface CategoryList {
  items: CategoryItem[]
  groups: CategoryGroup[]
  stats: CategoryStat[]
  customCount: number
  total: number
}

/**
 * 分类穿透里的一条：一个「条目」= 一个可检索块。
 *
 * 字段与后端 CategoryService.EntryItem 一一对应 —— 正文只给摘要（snippet），
 * 列表是用来「认人」的，不是用来通读的。
 */
export interface CategoryEntry {
  id: string
  docId: string
  title: string
  url: string
  tags: string[]
  curated: boolean
  contentAt: string
  snippet: string
}

/** 分类穿透结果：total 是命中总数，items 是当前页 */
export interface CategoryEntryPage {
  total: number
  items: CategoryEntry[]
}

export interface KbGroupTag {
  raw: string
  label: string
  count: number
}

export interface KbGroup {
  key: string
  label: string
  icon: string
  desc: string
  entryCount: number
  tags: KbGroupTag[]
}

export interface KbBrowseResult {
  entries: { title: string; url: string; text: string; tags: string[] }[]
  total: number
  page: number
  pageSize: number
}
/* ==================== 首页轮播 ==================== */

/**
 * 一张轮播图。管理端与公开站共用同一份形状。
 *
 * ⚠️ `width`/`height` 可能为 0：后端读不出尺寸时就是 0（实测 WebP 会这样）。
 * 前端必须容错成 16:9，不能拿 0 去算比例 —— 那会得到一条高度为 0 的缝。
 */
export interface CarouselItem {
  id: number
  /** 直接能放进 <img src>（形如 /api/public/site/image/3），长缓存 */
  imageUrl: string
  /** 点击跳转地址，可空 */
  link: string
  /** 图下方的说明文字，可空 */
  caption: string
  /** 0 = 尺寸未知 */
  width: number
  height: number
  enabled: boolean
  /** 升序展示 */
  sort: number
  createdAt: string
}

/** GET /admin/api/carousel —— 列表 + 当前限额（限额用来决定上传控件能不能点） */
export interface CarouselListResponse {
  ok: boolean
  items: CarouselItem[]
  maxCount: number
  /** 业务上限（KB）：超过它后端会明确告诉你"图片太大" */
  maxSizeKb: number
  /**
   * 容器的接收上限（KB，来自 spring.servlet.multipart.max-file-size）。
   *
   * <p>它比 {@link maxSizeKb} 宽 —— 存在的意义是**超限时也能说清原因**：
   * 超过这个数，请求会被容器在读完之前掐断，浏览器只会给一句 "Failed to fetch"，
   * 前端拿这个值先挡一道，用户才知道是"太大了"而不是"网断了"。
   */
  uploadCeilingKb: number
  intervalMs: number
  enabled: boolean
}

/** GET /api/public/carousel —— 只含启用的，已按 sort 升序 */
export interface CarouselPublicResponse {
  items: CarouselItem[]
  /**
   * 切换间隔（毫秒）。
   *
   * ⚠️ 契约里公开接口只承诺 `items`，所以这里是**可选**的：
   * 后端给了就用它（这样后台的「切换间隔」才真的生效），没给就退回组件默认的 4000。
   */
  intervalMs?: number
}

/** 单张图的上传 / 编辑响应 */
export interface CarouselItemResponse {
  ok: boolean
  item: CarouselItem
}

/** 排序 / 删除之后返回整份新列表 —— 前端直接拿它替换，不自己猜顺序 */
export interface CarouselItemsResponse {
  ok: boolean
  items: CarouselItem[]
}

