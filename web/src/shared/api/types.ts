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

/* ==================== 词条（一个词条 = 一个分块） ====================
   词条不再是独立的名字表：块本身就是词条，字段是 id / 标题 / 别名 / 分类。
   核对状态与核对模式已经全部删掉。别名是唯一可写的字段。 */

/** 一条词条 = 一个分块。别名空串表示没单独写过（由标题「（English）」派生） */
export interface KbEntryItem {
  id: string
  docId: string
  title: string
  /** 别名（存库那份为空时由标题派生兜底） */
  alias: string
  /** 认得的分类名（空数组 = 未分类） */
  categories: string[]
  /** 来源：doc / manual / wiki */
  source: string
  chars: number
  url: string
  retired: boolean
  /** 是不是未分类（标签认不出，或压根没标签） */
  uncategorized: boolean
}

/** 全表读数（不受搜索/筛选影响） */
export interface KbEntryCounts {
  all: number
  aliased: number
  unaliased: number
  uncategorized: number
  retired: number
}

/** 一个分类的规模 */
export interface KbCategoryStat {
  name: string
  label: string
  count: number
}

export interface KbEntryListResponse {
  total: number
  items: KbEntryItem[]
  counts: KbEntryCounts
  categories: KbCategoryStat[]
  uncategorizedCount: number
  available: boolean
}

/** Excel/WPS 旁路：导出拿到的是 JSON 包着的表格文本（client 永远走 res.json()） */
export interface KbEntryExportResponse {
  ok: boolean
  /** tsv 文本（format=tsv 时） */
  tsv: string
  text?: string
  /** format=xlsx 时的文件字节（base64） */
  xlsxBase64?: string
  format?: 'xlsx' | 'tsv'
  count: number
}

export interface KbEntryImportResponse {
  ok: boolean
  updated: number
  skipped: number
}

/** 按 id 取单块（含正文）—— 词条面板编辑正文用 */
export interface KbBlockItemResponse {
  ok: boolean
  block: KbBlockView
}

/* ==================== 指令系统 ==================== */

/** 一条命令的参数声明。JSON 键 default 在后端 Java 侧叫 defaultValue */
export interface AdminParamSpec {
  name: string
  /** text / int / moment / date / enum */
  type: string
  required: boolean
  default?: string | null
  maxChars?: number
  description?: string | null
  example?: string | null
  /** 仅 enum 用 */
  options?: string[]
  /** 能不能一次给多个值，用顿号隔开 */
  multiple?: boolean
}

export interface BotCommand {
  id: number
  /** 命令路径，用 / 分层：set/output */
  trigger: string
  reply: string
  description: string
  scope: string
  groupIds: number[]
  /** 准入档位：all / admin / owner / whitelist / blacklist */
  access: string
  enabled: boolean
  sortOrder: number
  builtin: boolean
  /** template = 话术（不调模型、不计费）；agent = 智能问答（调模型，会走成本预算） */
  kind: string
  /** agent 类的回答模式：kb = 走知识库（默认）；none = 不用知识库 */
  mode: string
  /** 参数表：群里用 #名字-值# 传值，模板里用 {arg.名字} 取 */
  params: AdminParamSpec[]
  /** 手写的单命令帮助；为空则由参数表自动生成 */
  help?: string | null
  /** action 类绑定的执行器 id，其余类型为空 */
  action?: string | null
  /** 后端算好的完整用法，带参数示例（与群里 {cmd.list} 的写法一致） */
  usage: string
}

/** 一个已注册的动作：配置界面用它渲染下拉与「载入参数表」 */
export interface CommandActionInfo {
  id: string
  /** 给人看的名字，下拉里显示它 */
  label: string
  /** 一句话说明它干什么 */
  description: string
  /** 选中它时自动填的命令路径 */
  defaultPath: string
  params: AdminParamSpec[]
}

export interface CommandActions {
  actions: CommandActionInfo[]
}

/** 一行定时输出。时刻是 ISO 串，展示时按东八区格式化 */
export interface AdminScheduleTask {
  id: number
  /** 0 = 私聊 */
  groupId: number
  userId: number
  nickname: string
  content: string
  repeatRule: string
  /** pending / sending / sent / failed / expired / canceled */
  status: string
  attempts: number
  lastError?: string | null
  fireAt: string
  /** 「什么时候发」的人话：每天 07:00、14:00 或 2026-10-20 21:30 */
  when: string
  /** 时刻组：周期是 HH:mm，一次性是 ISO 串 */
  times: string[]
  createdAt: string
  sentAt?: string | null
}

/** 一条命令的数据策略：能读哪些群、跨群时标多少 */
export interface CommandPolicy {
  id: number
  /** GROUP = 只本群；SOME = 指定群；ALL = 全部群 */
  scope: string
  groups: number[]
  showGroup: boolean
  showUser: boolean
  /** 能不能在私聊里用。默认关 */
  allowPrivate: boolean
}

export interface CommandPolicies {
  policies: CommandPolicy[]
}

/** 名单条目：uid = QQ 号，gid = 群号 */
export interface AclItem {
  type: string
  value: number
}

/** /prompt 的后台视图：群提示或个人提示 */
export interface AdminPrompt {
  id: number
  scope: 'group' | 'user'
  groupId: number
  userId: number
  content: string
}

/** 命令别名：target 是规范命令路径，如 prompt/create、预约开黑/create */
export interface SystemAliasEntry {
  kind: 'command' | 'action'
  target: string
  label: string
  aliases: string[]
}

export interface SystemAliasGroup {
  label: string
  items: SystemAliasEntry[]
}

export interface SystemAliasList {
  groups: SystemAliasGroup[]
}

export interface AdminPromptList {
  available: boolean
  prompts: AdminPrompt[]
}

export interface CommandAcl {
  white: AclItem[]
  black: AclItem[]
}

export interface ScheduleList {
  available: boolean
  /** 各状态条数，键就是状态名 */
  counts: Record<string, number>
  tasks: AdminScheduleTask[]
}

/** 命令目录的一条：板块或普通指令。后台列表与 QQ 的 /list 共用这一份数据 */
export interface CommandCatalogEntry {
  source: 'board' | 'command'
  id: number
  /** 不含开头斜杠；板块就是板块名 */
  path: string
  title: string
  description: string
  /** board / template / agent / action */
  kind: string
  params: AdminParamSpec[]
  /** 完整用法，带参数示例 */
  usage: string
  /** 归一化后的准入档位 all / admin / owner / whitelist / blacklist */
  access: string
  /** 板块的数据范围 GROUP / SOME / ALL；指令为 null */
  scope?: string | null
  /** 板块的投递方式 direct / scheduled；指令为 null */
  delivery?: string | null
  enabled: boolean
  builtin: boolean
  /** 板块支持的四个动作；指令为空 */
  actions: string[]
}

export interface CommandCatalog {
  entries: CommandCatalogEntry[]
}

/** 后台编辑用的一条板块（/admin/api/boards） */
export interface AdminBoard {
  id: number
  path: string
  title: string
  params: AdminParamSpec[]
  /** direct / scheduled */
  delivery: string
  access: string
  /** GROUP / SOME / ALL */
  scope: string
  groups: number[]
  showGroup: boolean
  showUser: boolean
  allowPrivate: boolean
  enabled: boolean
  sortOrder: number
  builtin: boolean
  usage: string
  /** 列表头，支持 {count} {path}；空 = 框架默认 */
  listHeader: string
  /** 每条格式，支持 {id} {when} {text} {nickname} {group} {params.参数名} */
  listItem: string
  /** 空列表文案；空 = 框架默认 */
  listEmpty: string
  /** 最多显示几条；0 = 全部，上限 200 */
  listLimit: number
}

export interface AdminBoardList {
  boards: AdminBoard[]
}

/** 提交给后端的板块草稿：originalPath 只在编辑时给，用来确认改的是哪一条 */
export interface BoardDraft extends AdminBoard {
  originalPath?: string | null
}

/** 一条板块记录（/admin/api/board-records） */
export interface AdminBoardRecord {
  id: number
  board: string
  groupId: number
  userId: number
  nickname: string
  text: string
  params: Record<string, string>
  delivery: string
  /** 人话时间：定时的是「每天 21:30」，立即展示的是「立即展示」 */
  when: string
  /** 与群里 /板块 列表同一行格式 */
  line: string
  /** pending / sending / sent / failed / expired / canceled */
  status: string
  attempts: number
  lastError?: string | null
  fireAt?: string | null
  createdAt?: string | null
  sentAt?: string | null
  deletedAt?: string | null
  deleted: boolean
}

export interface AdminBoardRecordsResponse {
  board: string
  records: AdminBoardRecord[]
  count: number
}

export interface CommandList {
  available: boolean
  commands: BotCommand[]
  /** 已移除：群里是否必须 @ 改读准入设置 app.guard.access.require-mention-in-group */
  rateLimitPerMinute: number
  allowUserIds: boolean
}

export interface CommandVariable {
  name: string
  label: string
  example: string
  advanced: boolean
  /** 能不能写在 #名字-值# 的值里 */
  inParam: boolean
  /** 能不能写在文本与模板里 */
  inText: boolean
}

export interface CommandVariables {
  variables: CommandVariable[]
  /** 示例值 */
  preview: Record<string, string>
  /** 真实值。管理端没有群会话，会话相关的变量仍是占位 */
  current: Record<string, string>
  allowUserIds: boolean
}

export interface CommandUsage {
  trigger: string
  count: number
}

export interface CommandStats {
  topUsed: CommandUsage[]
  /** 全部命中过的命令（不限 20 条），统一命令表按行显示次数 */
  usage: CommandUsage[]
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




/* ==================== 分类（人工维护的平铺清单） ====================
   分类只有一份人工清单：分块符号里带什么标签就归什么分类，认不出就是「未分类」。
   没有"原始标签 → 9 大类"的智能归类。 */

export interface CategoryList {
  items: KbCategoryStat[]
  uncategorized: number
  uncategorizedName: string
  total: number
  available: boolean
}

/** 分类穿透里的一条：一个条目 = 一个分块 */
export interface CategoryEntry {
  id: string
  docId: string
  title: string
  alias: string
  categories: string[]
  source: string
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
