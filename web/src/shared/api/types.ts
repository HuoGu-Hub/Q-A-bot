import type { Schemas } from './generated'

type RequiredFields<T, K extends keyof T> = Required<Pick<T, K>> & Omit<T, K>

type DeepRequired<T> = T extends (infer U)[]
  ? DeepRequired<U>[]
  : T extends object
    ? { [K in keyof T]-?: DeepRequired<T[K]> }
    : T

export type KbEntry =
  RequiredFields<Schemas['KbEntry'], 'title' | 'url' | 'text'>
  & Pick<Schemas['KbEntry'], 'cats'>

export type KbSearchResult = {
  query: string
  count: number
  mode?: 'semantic' | 'keyword'
  entries: KbEntry[]
}

export type PublicStats = Required<Schemas['PublicStats']>

export type Overview = DeepRequired<Schemas['Overview']>

export type DayCount = DeepRequired<Schemas['DayCount']>

export type KeywordStat = DeepRequired<Schemas['KeywordStat']>

export type MissStat = DeepRequired<Schemas['MissStat']>

export type UnmatchedMiss = DeepRequired<Schemas['UnmatchedMiss']>

export interface MissResponse {
  withKeyword: MissStat[]
  unmatched: UnmatchedMiss[]
}

export type Bucket = DeepRequired<Schemas['Bucket']>

export type SourceStat = DeepRequired<Schemas['SourceStat']>

export type VerdictStat = DeepRequired<Schemas['VerdictStat']>

export interface SourcesResponse {
  sources: SourceStat[]
  verdicts: VerdictStat[]
}

export type RecordRow = DeepRequired<Schemas['RecordRow']>

export type VisitDay = DeepRequired<Schemas['VisitDay']>

export type VisitSummary = DeepRequired<Schemas['VisitSummary']>

export interface LogEntry {
  seq: number
  ts: string
  level: 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | string
  message: string
  throwable?: string
}

export type LogHistory = DeepRequired<Schemas['LogHistory']>

export type LogStatus = DeepRequired<Schemas['LogStatus']>

export interface KbEntryItem {
  id: string
  docId: string
  title: string
  alias: string
  categories: string[]
  source: string
  chars: number
  url: string
  retired: boolean
  uncategorized: boolean
}

export interface KbEntryCounts {
  all: number
  aliased: number
  unaliased: number
  uncategorized: number
  retired: number
}

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

export interface KbEntryExportResponse {
  tsv?: string
  text?: string
  xlsxBase64?: string
  format?: 'xlsx' | 'tsv'
  count: number
}

export interface KbEntryImportResponse {
  updated: number
  skipped: number
}

export interface KbBlockItemResponse {
  block: KbBlockView
}

export interface AdminParamSpec {
  name: string
  type: string
  required: boolean
  default?: string | null
  maxChars?: number
  description?: string | null
  example?: string | null
  options?: string[]
  multiple?: boolean
}

export interface AccessMode {
  code: string
  label: string
  usesAcl: boolean
}

export interface BotCommand {
  id: number
  trigger: string
  reply: string
  description: string
  scope: string
  groupIds: number[]
  access: string
  enabled: boolean
  sortOrder: number
  builtin: boolean
  kind: string
  mode: string
  params: AdminParamSpec[]
  help?: string | null
  listReply?: string | null
  defaultList: string
  defaultHelp: string
  action?: string | null
  usage: string
}

export interface CommandActionInfo {
  id: string
  label: string
  description: string
  defaultPath: string
  params: AdminParamSpec[]
}

export interface CommandActions {
  actions: CommandActionInfo[]
}

export interface AdminScheduleTask {
  id: number
  groupId: number
  userId: number
  nickname: string
  content: string
  repeatRule: string
  status: string
  attempts: number
  lastError?: string | null
  fireAt: string
  when: string
  times: string[]
  createdAt: string
  sentAt?: string | null
}

export interface CommandPolicy {
  id: number
  scope: string
  groups: number[]
  showGroup: boolean
  showUser: boolean
  allowPrivate: boolean
}

export type CommandPolicies = DeepRequired<Schemas['CommandPolicies']>

export interface AclItem {
  type: string
  value: number
}

export interface AdminPrompt {
  id: number
  scope: 'group' | 'user'
  groupId: number
  userId: number
  content: string
}

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

export type SystemReplyItem = DeepRequired<Schemas['SystemReplyItem']>

export type SystemReplyList = DeepRequired<Schemas['SystemReplyList']>

export type SystemAliasList = {
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

export interface CommandCatalogEntry {
  source: 'board' | 'command'
  id: number
  path: string
  title: string
  description: string
  kind: string
  params: AdminParamSpec[]
  usage: string
  access: string
  scope?: string | null
  delivery?: string | null
  enabled: boolean
  builtin: boolean
  actions: string[]
}

export interface AdminBoard {
  id: number
  path: string
  title: string
  description: string
  params: AdminParamSpec[]
  delivery: string
  access: string
  scope: string
  groups: number[]
  showGroup: boolean
  showUser: boolean
  allowPrivate: boolean
  enabled: boolean
  sortOrder: number
  builtin: boolean
  usage: string
  listHeader: string
  listItem: string
  listEmpty: string
  listLimit: number
  listReply: string
  helpReply: string
  defaultList: string
  defaultHelp: string
}

export interface AdminBoardList {
  boards: AdminBoard[]
}

export interface BoardDraft extends AdminBoard {
  originalPath?: string | null
}

export interface AdminBoardRecord {
  id: number
  board: string
  groupId: number
  userId: number
  nickname: string
  text: string
  params: Record<string, string>
  delivery: string
  when: string
  line: string
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

export type CommandList = DeepRequired<Schemas['CommandList']>

export interface CommandVariable {
  name: string
  label: string
  example: string
  advanced: boolean
  inParam: boolean
  inText: boolean
}

export interface CommandVariables {
  variables: CommandVariable[]
  preview: Record<string, string>
  current: Record<string, string>
  allowUserIds: boolean
}

export type CommandStats = DeepRequired<Schemas['CommandStats']>

export interface KbBlockStats {
  available: boolean
  blocks: number
  retired: number
  documents: number
  vectors: number
  titleVectors: number
}

export interface KbDocSummary {
  docId: string
  blocks: number
  retired: number
}

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
  alias: string
}

export interface KbImportPreview {
  added: number
  updated: number
  unchanged: number
  untouchedExisting: number
  addedIds: string[]
  updatedIds: string[]
  warnings: string[]
}

export interface KbImportResult {
  added: number
  updated: number
  unchanged: number
  untouched: number
  warnings: string[]
}

export interface SettingItem {
  key: string
  label: string
  group: string
  type: string
  hint: string
  value: unknown
}

export type SettingsView = {
  groups: Record<string, SettingItem[]>
  applyCount: number
  note: string
}

export interface ModelProvider {
  name: string
  configured: boolean
  ready: boolean
  modelName: string
}

export type ModelList = DeepRequired<Schemas['ModelList']>

export type ProbeResult = Schemas['ProbeResult']

export type AvailableModels = DeepRequired<Schemas['AvailableModels']>

export type CategoryList = DeepRequired<Schemas['CategoryList']>

export interface CategoryEntry {
  id: string
  docId: string
  title: string
  alias: string
  categories: string[]
  source: string
  snippet: string
}

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

export type BrowseEntry = Required<Pick<
  Schemas['BrowseEntry'], 'title' | 'url' | 'text' | 'tags'
>>

export type KbBrowseResult = {
  entries: BrowseEntry[]
  total: number
  page: number
  pageSize: number
}

export interface CarouselItem {
  id: number
  imageUrl: string
  link: string
  caption: string
  width: number
  height: number
  enabled: boolean
  sort: number
  createdAt: string
}

export interface CarouselListResponse {
  items: CarouselItem[]
  maxCount: number
  maxSizeKb: number
  uploadCeilingKb: number
  intervalMs: number
  enabled: boolean
}

export type CarouselPublicItem = Required<Pick<
  Schemas['CarouselItemView'],
  'id' | 'imageUrl' | 'link' | 'caption' | 'width' | 'height'
>>

export type CarouselPublicResponse = {
  items: CarouselPublicItem[]
  intervalMs?: number
}

export interface CarouselItemResponse {
  item: CarouselItem
}

export interface CarouselItemsResponse {
  items: CarouselItem[]
}

export type TrafficPoint = DeepRequired<Schemas['TrafficPoint']>

export type TrafficSummary = DeepRequired<Schemas['TrafficSummary']>

export type TrafficResponse = DeepRequired<Schemas['TrafficResponse']>

export type LlmPoint = DeepRequired<Schemas['LlmPoint']>

export type LlmSummary = DeepRequired<Schemas['LlmSummary']>

export type LlmResponse = DeepRequired<Schemas['LlmResponse']>

export interface EvalDataset {
  id: number
  slug: string
  name: string
  description: string
  status: string
  createdAt: string
  caseCount: number
  versionCount: number
}

export interface EvalVersion {
  id: number
  version: number
  caseCount: number
  checksum: string
  frozenAt: string
}

export interface EvalCase {
  id: number
  caseKey: string
  category: string
  question: string
  expected: string
  note: string
  enabled: boolean
}

export interface EvalDatasetDetail {
  dataset: EvalDataset
  versions: EvalVersion[]
  cases: EvalCase[]
  totalCases: number
}

export interface EvalImportResult {
  total: number
  accepted: number
  rejected: number
  dryRun: boolean
  errors: string[]
}

export interface EvalBatch {
  id: number
  name: string
  datasetId: number
  datasetVersionId: number
  status: string
  gateStatus: string
  gateReason: string | null
  mode: string
  itemTotal: number
  repeatTimes: number
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface EvalDimStat {
  dimension: string
  pass: number
  fail: number
  skip: number
}

export interface EvalTagStat {
  tag: string
  count: number
}

export interface EvalSummary {
  runs: number
  judged: number
  pass: number
  fail: number
  skip: number
  passRate: number
  p50Ms: number
  p95Ms: number
  tokensIn: number
  tokensOut: number
  costMicro: number
  pricedRuns: number
  dimensions: EvalDimStat[]
  tags: EvalTagStat[]
}

export interface EvalItem {
  id: number
  caseKey: string
  category: string
  question: string
  status: string
  runId: number | null
  verdict: string | null
  tag: string | null
  latencyMs: number | null
  attempts: number
}

export interface EvalBatchDetail {
  batch: EvalBatch
  summary: EvalSummary
  items: EvalItem[]
}

export interface EvalSpan {
  seq: number
  parentSeq: number
  kind: string
  name: string
  status: string
  startOffsetMs: number
  durationMs: number
  attrs: Record<string, unknown>
  errorCode: string | null
}

export interface EvalRetrieval {
  query: string
  terms: Array<Record<string, unknown>>
  vectorCandidates: number
  keywordCandidates: number
  bestCosine: number
  bestCosineRaw: number
  hits: Array<Record<string, unknown>>
  sources: string
  emptyReason: string | null
  latencyMs: number
}

export interface EvalJudge {
  dimension: string
  judgeType: string
  verdict: string
  score: number
  reason: string
  tag: string | null
}

export interface EvalToolCall {
  callIndex: number
  name: string
  args: string
  status: string
  latencyMs: number | null
  resultChars: number | null
  error: string | null
}

export interface EvalRunDetail {
  id: number
  batchId: number
  itemId: number
  attempt: number
  status: string
  answer: string
  latencyMs: number
  hitCount: number
  kbEnabled: boolean
  createdAt: string
  tokensIn: number
  tokensOut: number
  costMicro: number
  toolCalls: EvalToolCall[]
  citations: EvalCitation[]
  spans: EvalSpan[]
  retrieval: EvalRetrieval | null
  judges: EvalJudge[]
  caseKey: string
  category: string
  question: string
}

export interface EvalCitation {
  sentenceIndex: number
  sentence: string
  blockIds: string
  chars: number
}

export interface EvalReview {
  id: number
  runId: number
  batchId: number
  caseKey: string
  question: string
  reason: string
  priority: string
  status: string
  assignee: string | null
  createdAt: string
  dueAt: string | null
}

export interface EvalFailureCase {
  runId: number
  caseKey: string
  question: string
  dimension: string
  tag: string
  reason: string
}

export interface EvalPrice {
  id: number
  provider: string
  model: string
  inputPerMillion: number
  outputPerMillion: number
  currency: string
  updatedAt: string
}

export interface EvalTrendPoint {
  batchId: number
  name: string
  datasetVersionId: number
  createdAt: string
  gateStatus: string
  gateReason: string | null
  passRate: number
  judged: number
  costMicro: number
  pricedRuns: number
  p95Ms: number
}

export interface EvalCompareSide {
  batchId: number
  name: string
  datasetVersionId: number
  passRate: number
  judged: number
  costMicro: number
  p95Ms: number
}

export interface EvalCompareCase {
  caseKey: string
  question: string
  left: string
  right: string
  tag: string | null
  leftRunId: number | null
  rightRunId: number | null
}

export interface EvalCompareResult {
  left: EvalCompareSide
  right: EvalCompareSide
  sameDataset: boolean
  sameVersion: boolean
  improved: number
  regressed: number
  same: number
  changes: EvalCompareCase[]
}

export interface EvalAlertRule {
  id: number
  name: string
  metric: string
  comparator: string
  threshold: number
  severity: string
  enabled: boolean
  createdAt: string
}

export interface EvalAlertEvent {
  id: number
  ruleId: number
  ruleName: string
  batchId: number
  metricValue: number
  threshold: number
  severity: string
  status: string
  createdAt: string
  ackedAt: string | null
  note: string | null
}

export interface EvalCase {
  id: number
  caseKey: string
  category: string
  question: string
  expected: string
  note: string
  enabled: boolean
  dimension: string
}

export interface EvalDatasetDetail {
  dataset: EvalDataset
  versions: EvalVersion[]
  cases: EvalCase[]
  totalCases: number
}

export interface EvalCalibrationRow {
  question: string
  expected: string
  actual: string
  score: number
  agreed: boolean
  reason: string
}

export interface EvalCalibrationReport {
  grader: string
  total: number
  agreed: number
  agreement: number
  passMean: number
  failMean: number
  separation: number
  rows: EvalCalibrationRow[]
}

export interface EvalCalibrationHistory {
  id: number
  grader: string
  graderVersion: number
  total: number
  agreed: number
  agreement: number
  passMean: number
  failMean: number
  separation: number
  provider: string | null
  createdAt: string
}
