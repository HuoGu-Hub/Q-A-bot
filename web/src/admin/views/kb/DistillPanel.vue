<script setup lang="ts">
/**
 * 知识库 → 聊天蒸馏。
 *
 * 页面只做两件事：
 *   1. 配自动蒸馏：触发条数、图片数、模型、发布方式。
 *   2. 管理笔记：搜索、整理、发布、撤回、删除。
 *
 * 运行记录与历史导入都是旁路，收进可折叠面板，不占主视觉。
 */
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import { toast } from '@shared/ui/toast'
import Panel from '@shared/ui/Panel.vue'
import Field from '@shared/ui/Field.vue'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Switch from '@shared/ui/Switch.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'
import Empty from '@shared/ui/Empty.vue'
import Notice from '@shared/ui/Notice.vue'

interface CaptureStatus {
  enabled: boolean
  allGroups: boolean
  groups: number[]
  queueSize: number
  recorded: number
  dropped: number
  imageSaved: number
}

interface Stats {
  available: boolean
  capture: CaptureStatus
  messages: number
  images: number
  jobs: number
  batches: number
  notes: number
  drafts: number
  publishedNotes: number
}

interface Settings {
  autoEnabled: boolean
  triggerMessages: number
  triggerImages: number
  batchMessages: number
  batchChars: number
  overlap: number
  maxGapMinutes: number
  autoPublish: boolean
  minConfidence: number
  focus: string
  modelProvider: string
  captureGroups: string
  captureAllGroups: boolean
  lastMessageId: number
  updatedAt: string
}

interface ProviderInfo {
  provider: string
  model: string
  vision: boolean
}

interface Job {
  id: number
  source: string
  groupIds: string
  timeFrom: string
  timeTo: string
  batchMode: string
  batchSize: number
  messageCount: number
  batchCount: number
  noteCount: number
  status: string
  error: string
  createdAt: string
}

interface Batch {
  id: number
  seq: number
  messageCount: number
  charCount: number
  status: string
  attempts: number
  model: string
  error: string
}

interface Note {
  id: number
  title: string
  aliases: string
  body: string
  category: string
  confidence: number
  status: string
  blockId: string
  model: string
  createdAt: string
}

interface Source {
  messageId: number
  ts: string
  senderName: string
  text: string
}

interface NoteDetail {
  note: Note
  sources: Source[]
  imageIds: number[]
}

interface BlockedSender {
  senderHash: string
  senderId: string
  senderName: string
  messageCount: number
  createdAt: string
}

interface SenderOption {
  senderHash: string
  senderId: string
  senderName: string
  messageCount: number
  blocked: boolean
}

const loading = ref(false)
const error = ref('')
const stats = ref<Stats | null>(null)
const groups = ref<number[]>([])
const settings = ref<Settings | null>(null)
const pendingMessages = ref(0)
const providers = ref<ProviderInfo[]>([])
const blockedSenders = ref<BlockedSender[]>([])
const senders = ref<SenderOption[]>([])
const blockPick = ref('')
const blockQqInput = ref('')
const jobs = ref<Job[]>([])
const jobPage = ref(1)
const jobPages = ref(1)
const jobTotal = ref(0)
const JOB_PAGE_SIZE = 20
const notes = ref<Note[]>([])
const selected = ref<NoteDetail | null>(null)
/** 多选删除：只在笔记列表里生效，与右侧编辑器互不影响 */
const selectMode = ref(false)
const picked = ref<number[]>([])
const selectedJob = ref<{ job: Job; batches: Batch[] } | null>(null)
const jobBusy = ref('')
const search = ref('')
const statusFilter = ref('')
const importText = ref('')
const importGroupId = ref('')

const form = ref({
  autoEnabled: false,
  triggerMessages: 100,
  triggerImages: 0,
  batchMessages: 80,
  overlap: 5,
  maxGapMinutes: 30,
  autoPublish: true,
  minConfidence: 0.75,
  focus: '',
  modelProvider: '',
  captureGroups: '',
  captureAllGroups: true,
})

const CAPTURE_MODES = [
  { value: 'all', label: '全部群' },
  { value: 'list', label: '指定群' },
]

const PUBLISH_MODES = [
  { value: 'auto', label: '自动发布到知识库' },
  { value: 'manual', label: '先整理再发布' },
]

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'draft', label: '待处理' },
  { value: 'published', label: '已发布' },
  { value: 'duplicate', label: '重复' },
  { value: 'rejected', label: '已拒绝' },
]

const providerOptions = computed(() => [
  { value: '', label: '跟随默认链' },
  ...providers.value.map(p => ({
    value: p.provider,
    label: p.provider + ' · ' + p.model + (p.vision ? ' · 视觉' : ''),
  })),
])

const captureMode = computed({
  get: () => (form.value.captureAllGroups ? 'all' : 'list'),
  set: (v: string) => { form.value.captureAllGroups = v === 'all' },
})

const groupOptions = computed(() => {
  const ids = new Set<number>(groups.value)
  for (const part of form.value.captureGroups.split(/[,，\s]+/)) {
    const id = Number(part.trim())
    if (Number.isFinite(id) && id > 0) ids.add(id)
  }
  return [...ids].map(g => ({ value: String(g), label: '群 ' + g }))
})

const publishMode = computed({
  get: () => (form.value.autoPublish ? 'auto' : 'manual'),
  set: (v: string) => { form.value.autoPublish = v === 'auto' },
})

const senderOptions = computed(() => senders.value
  .filter(s => !s.blocked)
  .map(s => ({
    value: s.senderHash,
    label: (s.senderName || '未命名') + ' · ' + s.messageCount + ' 条',
  })))

const filteredNotes = computed(() => {
  const q = search.value.trim().toLowerCase()
  if (!q) return notes.value
  return notes.value.filter(n =>
    (n.title + ' ' + n.category + ' ' + n.body).toLowerCase().includes(q))
})


async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    const [s, cfg] = await Promise.all([
      adminApi.get<Stats>('/distill/stats'),
      adminApi.get<{ settings: Settings; groups: number[]; providers: ProviderInfo[]; pendingMessages: number }>('/distill/settings'),
    ])
    stats.value = s
    settings.value = cfg.settings
    groups.value = cfg.groups ?? []
    providers.value = cfg.providers ?? []
    pendingMessages.value = cfg.pendingMessages ?? 0
    if (cfg.settings) {
      form.value = {
        autoEnabled: cfg.settings.autoEnabled,
        triggerMessages: cfg.settings.triggerMessages,
        triggerImages: cfg.settings.triggerImages,
        batchMessages: cfg.settings.batchMessages,
        overlap: cfg.settings.overlap,
        maxGapMinutes: cfg.settings.maxGapMinutes,
        autoPublish: cfg.settings.autoPublish,
        minConfidence: cfg.settings.minConfidence,
        focus: cfg.settings.focus ?? '',
        modelProvider: cfg.settings.modelProvider ?? '',
        captureGroups: cfg.settings.captureGroups ?? '',
        captureAllGroups: cfg.settings.captureAllGroups ?? true,
      }
      if (!importGroupId.value) importGroupId.value = String(groups.value[0] ?? '')
    }
    await Promise.all([loadNotes(), loadJobs(), loadBlocklist()])
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function loadNotes() {
  const q = statusFilter.value ? '&status=' + encodeURIComponent(statusFilter.value) : ''
  const r = await adminApi.get<{ rows: Note[] }>('/distill/notes?limit=300' + q)
  notes.value = r.rows ?? []
  if (selected.value && !notes.value.some(n => n.id === selected.value!.note.id)) {
    selected.value = null
  }
}

async function loadJobs() {
  const r = await adminApi.get<{ rows: Job[]; total: number; page: number; pages: number }>(
    '/distill/jobs?page=' + jobPage.value + '&size=' + JOB_PAGE_SIZE)
  if (r.page > r.pages) {
    jobPage.value = r.pages
    return loadJobs()
  }
  jobs.value = r.rows ?? []
  jobTotal.value = r.total ?? 0
  jobPage.value = r.page ?? 1
  jobPages.value = r.pages ?? 1
  if (selectedJob.value && !jobs.value.some(j => j.id === selectedJob.value!.job.id)) {
    selectedJob.value = null
  }
}

async function goJobPage(page: number) {
  const next = Math.min(Math.max(1, page), jobPages.value)
  if (next === jobPage.value) return
  jobPage.value = next
  await loadJobs()
}

async function loadBlocklist() {
  const r = await adminApi.get<{ blocked: BlockedSender[]; senders: SenderOption[] }>('/distill/blocklist')
  blockedSenders.value = r.blocked ?? []
  senders.value = r.senders ?? []
  if (!blockPick.value || !senderOptions.value.some(o => o.value === blockPick.value)) {
    blockPick.value = senderOptions.value[0]?.value ?? ''
  }
}

async function blockSender() {
  if (!blockPick.value) return
  const sender = senders.value.find(s => s.senderHash === blockPick.value)
  try {
    await adminApi.post('/distill/blocklist', {
      senderHash: blockPick.value,
      senderName: sender?.senderName ?? '',
    })
    toast('已屏蔽 ' + (sender?.senderName || '该成员'))
    await loadBlocklist()
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

async function blockByQq() {
  const qq = blockQqInput.value.trim()
  if (!/^\d{5,12}$/.test(qq)) {
    toast('请输入 5–12 位 QQ 号', 'warn')
    return
  }
  try {
    const r = await adminApi.post<{ updated: number }>('/distill/blocklist/qq', { qq })
    toast('已屏蔽 QQ ' + qq + (r.updated ? '，并回填 ' + r.updated + ' 条历史消息' : ''))
    blockQqInput.value = ''
    await loadBlocklist()
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

async function unblockEntry(b: BlockedSender) {
  try {
    if (b.senderId) {
      await adminApi.del('/distill/blocklist/qq/' + encodeURIComponent(b.senderId))
    } else {
      await adminApi.del('/distill/blocklist/' + encodeURIComponent(b.senderHash))
    }
    toast('已移出禁学名单')
    await loadBlocklist()
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

async function saveSettings() {
  error.value = ''
  try {
    const r = await adminApi.put<{ settings: Settings }>('/distill/settings', {
      ...form.value,
      batchChars: settings.value?.batchChars ?? 10000,
    })
    settings.value = r.settings
    if (r.settings) {
      form.value = {
        autoEnabled: r.settings.autoEnabled,
        triggerMessages: r.settings.triggerMessages,
        triggerImages: r.settings.triggerImages,
        batchMessages: r.settings.batchMessages,
        overlap: r.settings.overlap,
        maxGapMinutes: r.settings.maxGapMinutes,
        autoPublish: r.settings.autoPublish,
        minConfidence: r.settings.minConfidence,
        focus: r.settings.focus ?? '',
        modelProvider: r.settings.modelProvider ?? '',
        captureGroups: r.settings.captureGroups ?? '',
        captureAllGroups: r.settings.captureAllGroups ?? true,
      }
    }
    toast(form.value.autoEnabled ? '已保存，达到条件后会自动开始' : '已保存，自动蒸馏已关闭')
    await loadAll()
  } catch (e) {
    error.value = describeError(e)
  }
}

async function runNow() {
  error.value = ''
  try {
    const r = await adminApi.post<{ jobId: number }>('/distill/settings/run-now')
    toast('任务 #' + r.jobId + ' 已开始，去「运行记录」看进度')
    await loadAll()
  } catch (e) {
    error.value = describeError(e)
  }
}

async function selectNote(id: number) {
  try {
    selected.value = await adminApi.get<NoteDetail>('/distill/notes/' + id)
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

async function toggleNote(id: number) {
  if (selected.value?.note.id === id) {
    selected.value = null
    return
  }
  await selectNote(id)
}

/** 多选模式下点击笔记：既勾选，也加载右侧只读预览 */
async function onNoteClick(id: number) {
  if (selectMode.value) {
    togglePick(id)
    if (selected.value?.note.id !== id) {
      await selectNote(id)
    }
    return
  }
  await toggleNote(id)
}


async function saveNote(): Promise<boolean> {
  if (!selected.value) return false
  error.value = ''
  try {
    const n = selected.value.note
    await adminApi.put('/distill/notes/' + n.id, {
      title: n.title,
      aliases: n.aliases,
      category: n.category,
      body: n.body,
      confidence: n.confidence,
    })
    toast('已保存')
    await Promise.all([selectNote(n.id), loadNotes()])
    return true
  } catch (e) {
    toast(describeError(e), 'error')
    return false
  }
}

async function publishNote() {
  if (!selected.value) return
  // 先把当前编辑内容存下来，否则发布的是库里的旧正文
  if (!(await saveNote())) return
  try {
    await adminApi.post('/distill/notes/' + selected.value.note.id + '/publish')
    toast('已发布到知识库')
    await Promise.all([selectNote(selected.value.note.id), loadAll()])
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

async function unpublishNote() {
  if (!selected.value) return
  try {
    await adminApi.post('/distill/notes/' + selected.value.note.id + '/unpublish')
    toast('已撤回；块还在，只是不参与检索')
    await Promise.all([selectNote(selected.value.note.id), loadAll()])
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

async function deleteNote() {
  if (!selected.value) return
  if (!window.confirm('彻底删除这条笔记？知识库块会一起删除，不能恢复。')) return
  try {
    await adminApi.del('/distill/notes/' + selected.value.note.id)
    selected.value = null
    toast('已彻底删除')
    await loadAll()
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

function toggleSelectMode() {
  selectMode.value = !selectMode.value
  picked.value = []
}

function isPicked(id: number) {
  return picked.value.includes(id)
}

function togglePick(id: number) {
  const i = picked.value.indexOf(id)
  if (i >= 0) {
    picked.value.splice(i, 1)
  } else {
    picked.value.push(id)
  }
}

function selectAllFiltered() {
  picked.value = filteredNotes.value.map(n => n.id)
}

function clearPicked() {
  picked.value = []
}

/**
 * 批量彻底删除。
 *
 * <p>逐条删除在服务端完成，这里只发一次请求 —— 否则 30 条笔记就是 30 个请求，
 * 中途失败还会留下"删了一半"的状态，很难说清楚。
 */
async function batchDelete() {
  if (picked.value.length === 0) return
  const count = picked.value.length
  if (!window.confirm('彻底删除选中的 ' + count + ' 条笔记？知识库块会一起删除，不能恢复。')) return
  try {
    const r = await adminApi.post<{ deleted: number; missing: number }>(
      '/distill/notes/batch-delete', { ids: picked.value })
    if (selected.value && picked.value.includes(selected.value.note.id)) {
      selected.value = null
    }
    picked.value = []
    toast('已删除 ' + r.deleted + ' 条' + (r.missing ? '，' + r.missing + ' 条已不存在' : ''))
    await loadAll()
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

async function openJob(id: number) {
  try {
    selectedJob.value = await adminApi.get<{ job: Job; batches: Batch[] }>('/distill/jobs/' + id)
  } catch (e) {
    toast(describeError(e), 'error')
  }
}

async function toggleJob(id: number) {
  if (selectedJob.value?.job.id === id) {
    selectedJob.value = null
    return
  }
  await openJob(id)
}

async function resumeJob(id: number) {
  jobBusy.value = id + ':resume'
  try {
    const r = await adminApi.post<{ started: boolean }>('/distill/jobs/' + id + '/start')
    toast(r.started ? '任务 #' + id + ' 已继续' : '任务 #' + id + ' 已在运行')
    await Promise.all([loadJobs(), openJob(id)])
  } catch (e) {
    toast(describeError(e), 'error')
  } finally {
    jobBusy.value = ''
  }
}

async function pauseJob(id: number) {
  jobBusy.value = id + ':pause'
  try {
    await adminApi.post('/distill/jobs/' + id + '/pause')
    toast('任务 #' + id + ' 已请求暂停，当前批次结束后停止')
    await loadJobs()
  } catch (e) {
    toast(describeError(e), 'error')
  } finally {
    jobBusy.value = ''
  }
}

async function retryJob(id: number) {
  jobBusy.value = id + ':retry'
  try {
    const r = await adminApi.post<{ reset: number; started: boolean }>('/distill/jobs/' + id + '/retry')
    if (r.reset > 0) {
      toast('任务 #' + id + ' 已重置 ' + r.reset + ' 个批次并开始重试')
    } else if (r.started) {
      toast('任务 #' + id + ' 已重新开始')
    } else {
      toast('任务 #' + id + ' 没有可重试的批次', 'warn')
    }
    await Promise.all([loadJobs(), openJob(id)])
  } catch (e) {
    toast(describeError(e), 'error')
  } finally {
    jobBusy.value = ''
  }
}

async function importHistory() {
  error.value = ''
  const group = Number(importGroupId.value) || groups.value[0] || 0
  if (!group) {
    error.value = '没有可用的采集群'
    return
  }
  try {
    const r = await adminApi.postText<{ importId: number; inserted: number; duplicates: number }>(
      '/distill/import?filename=' + encodeURIComponent('history-' + Date.now() + '.txt')
        + '&groupId=' + group,
      importText.value)
    toast('历史导入 #' + r.importId + '：写入 ' + r.inserted + ' 条，重复 ' + r.duplicates + ' 条')
    importText.value = ''
    await loadAll()
  } catch (e) {
    error.value = describeError(e)
  }
}

function toInt(v: string, fallback: number, min: number, max: number) {
  const n = Number(v)
  if (!Number.isFinite(n)) return fallback
  return Math.max(min, Math.min(max, Math.round(n)))
}

function toFloat(v: string, fallback: number, min: number, max: number) {
  const n = Number(v)
  if (!Number.isFinite(n)) return fallback
  return Math.max(min, Math.min(max, n))
}

function fmt(s: string) {
  if (!s) return ''
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}

function statusText(s: string) {
  return ({ ready: '待开始', running: '运行中', paused: '已暂停', done: '已完成', failed: '失败',
    pending: '待处理', draft: '待处理', published: '已发布', duplicate: '重复', rejected: '已拒绝' } as Record<string, string>)[s] || s
}

function statusTone(s: string) {
  if (s === 'published' || s === 'done') return 'good' as const
  if (s === 'running' || s === 'ready') return 'flame' as const
  if (s === 'failed' || s === 'rejected') return 'bad' as const
  if (s === 'paused' || s === 'draft' || s === 'pending') return 'warn' as const
  return 'neutral' as const
}

function conf(v: number) {
  return Math.round((v || 0) * 100) + '%'
}

onMounted(loadAll)

defineExpose({
  reload: loadAll,
  isLoading: () => loading.value,
})
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <Panel title="自动蒸馏" class="settings-panel">
      <Field label="自动蒸馏" control-width="70px">
        <Switch v-model="form.autoEnabled" aria-label="自动蒸馏" />
      </Field>

      <Field label="触发条件" hint="0 = 只按消息数">
        <div class="inline">
          <span class="faint">每累计</span>
          <span class="num-box">
            <Input
              :model-value="String(form.triggerMessages)"
              type="number"
              aria-label="触发消息数"
              @update:model-value="(v: string) => form.triggerMessages = toInt(v, 1, 1, 500)"
            />
          </span>
          <span class="faint">条消息，或</span>
          <span class="num-box">
            <Input
              :model-value="String(form.triggerImages)"
              type="number"
              aria-label="触发图片数"
              @update:model-value="(v: string) => form.triggerImages = toInt(v, 0, 0, 6)"
            />
          </span>
          <span class="faint">张图片</span>
        </div>
      </Field>

      <Field label="相邻批次重叠">
        <div class="inline">
          <span class="num-box">
            <Input
              :model-value="String(form.overlap)"
              type="number"
              aria-label="重叠条数"
              @update:model-value="(v: string) => form.overlap = toInt(v, 0, 0, 500)"
            />
          </span>
          <span class="faint">条</span>
        </div>
      </Field>

      <Field label="对话间隔">
        <div class="inline">
          <span class="num-box">
            <Input
              :model-value="String(form.maxGapMinutes)"
              type="number"
              aria-label="对话断开分钟数"
              @update:model-value="(v: string) => form.maxGapMinutes = toInt(v, 30, 0, 1440)"
            />
          </span>
          <span class="faint">分钟</span>
        </div>
      </Field>

      <Field label="采集范围">
        <Select
          v-model="captureMode"
          :options="CAPTURE_MODES"
          aria-label="采集范围"
        />
      </Field>

      <Field v-if="!form.captureAllGroups" label="指定群" hint="逗号分隔">
        <Input
          v-model="form.captureGroups"
          placeholder="例如 209127588, 866095282"
          aria-label="指定采集群"
        />
      </Field>

      <Field label="蒸馏模型">
        <Select
          v-model="form.modelProvider"
          :options="providerOptions"
          aria-label="蒸馏模型"
        />
      </Field>

      <Field label="蒸馏结果">
        <Select
          v-model="publishMode"
          :options="PUBLISH_MODES"
          aria-label="蒸馏结果发布方式"
        />
      </Field>

      <Field label="蒸馏关注点">
        <Input
          v-model="form.focus"
          multiline
          :rows="2"
          placeholder="例如：只整理建筑、配方、材料、坐标和 BOSS 打法；不要收录剧情闲聊。"
          aria-label="蒸馏关注点"
        />
      </Field>

      <div class="form-foot">
        <span class="faint">
          游标 #{{ settings?.lastMessageId ?? 0 }} · 待处理 {{ pendingMessages }} 条
        </span>
        <div class="actions">
          <Button size="sm" variant="primary" @click="saveSettings">保存设置</Button>
          <Button size="sm" @click="runNow">立即蒸馏一次</Button>
        </div>
      </div>
    </Panel>

    <Panel
      title="禁学名单"
      :count="blockedSenders.length"
      collapsible
      :default-open="false"
      mobile-collapsed
    >
      <div class="blocklist-tools">
        <Input
          v-model="blockQqInput"
          placeholder="输入 QQ 号"
          aria-label="QQ号"
        />
        <Button size="sm" :disabled="!blockQqInput.trim()" @click="blockByQq">按 QQ 屏蔽</Button>
      </div>

      <div class="blocklist-tools blocklist-tools--secondary">
        <Select
          v-model="blockPick"
          :options="senderOptions"
          aria-label="选择禁学成员"
        />
        <Button size="sm" :disabled="!blockPick" @click="blockSender">按成员屏蔽</Button>
      </div>

      <div v-if="blockedSenders.length" class="blocked-list">
        <div v-for="b in blockedSenders" :key="b.senderHash" class="blocked-item">
          <span class="blocked-name">{{ b.senderId ? 'QQ ' + b.senderId : (b.senderName || '未命名') }}</span>
          <span class="faint">
            {{ b.senderId ? (b.senderName || '未知昵称') + ' · ' : '' }}{{ b.messageCount }} 条
          </span>
          <Button size="sm" @click="unblockEntry(b)">移除</Button>
        </div>
      </div>
      <Empty v-else text="没有禁学成员" hint="输入 QQ 号，或从最近成员选择" />

      <p class="faint blocklist-hint">按 QQ 屏蔽会同时回填已知群里的历史消息；被屏蔽消息不进入蒸馏，也不计入触发。</p>
    </Panel>

    <Panel title="知识笔记" :count="filteredNotes.length">
      <div class="notes-workbench">
        <aside class="notes-list">
          <div class="notes-tools">
            <Input
              v-model="search"
              placeholder="搜索笔记"
              aria-label="搜索笔记"
            />
            <Select
              :model-value="statusFilter"
              :options="STATUS_OPTIONS"
              aria-label="按状态筛选"
              @update:model-value="(v: string) => { statusFilter = v; loadNotes() }"
            />
            <div class="notes-bulk-row">
              <Button size="sm" @click="toggleSelectMode">
                {{ selectMode ? '退出多选' : '多选' }}
              </Button>
              <template v-if="selectMode">
                <Button size="sm" @click="selectAllFiltered">全选</Button>
                <Button size="sm" :disabled="picked.length === 0" @click="clearPicked">清空</Button>
              </template>
            </div>
          </div>
          <div class="notes-summary faint">
            {{ filteredNotes.length }} 条
            <template v-if="stats">· {{ stats.drafts }} 待处理 · {{ stats.publishedNotes }} 已发布</template>
          </div>
          <div v-if="selectMode" class="bulk-bar">
            <span class="faint">已选 {{ picked.length }} 条</span>
            <Button size="sm" variant="danger" :disabled="picked.length === 0" @click="batchDelete">
              删除选中
            </Button>
          </div>

          <div class="notes-scroll">
            <Empty
              v-if="!filteredNotes.length"
              text="没有匹配的笔记"
              hint="调整筛选条件"
            />
            <button
              v-for="n in filteredNotes"
              :key="n.id"
              type="button"
              class="note-item"
              :class="{ on: selected?.note.id === n.id, picked: selectMode && isPicked(n.id) }"
              @click="onNoteClick(n.id)"
            >
              <span class="note-line">
                <span class="note-headline">
                  <span v-if="selectMode" class="pick" :class="{ on: isPicked(n.id) }" aria-hidden="true"></span>
                  <span class="note-title">{{ n.title }}</span>
                </span>
                <Tag :tone="statusTone(n.status)">{{ statusText(n.status) }}</Tag>
              </span>
              <span class="note-meta">
                <Tag tone="flame">{{ n.category || '未分类' }}</Tag>
                <span>{{ conf(n.confidence) }}</span>
                <span>·</span>
                <span>{{ fmt(n.createdAt) }}</span>
              </span>
            </button>
          </div>
        </aside>

        <section class="notes-editor">
          <template v-if="selectMode && selected">
            <div class="editor-head">
              <span class="faint">#{{ selected.note.id }} · {{ selected.note.model || '未知模型' }} · {{ fmt(selected.note.createdAt) }}</span>
              <Tag tone="neutral">只读预览</Tag>
            </div>

            <h3 class="preview-title">{{ selected.note.title }}</h3>
            <div class="preview-meta">
              <Tag tone="flame">{{ selected.note.category || '未分类' }}</Tag>
              <span class="faint">置信度 {{ conf(selected.note.confidence) }}</span>
              <span v-if="selected.note.aliases" class="faint">别名 {{ selected.note.aliases }}</span>
            </div>
            <p class="preview-body">{{ selected.note.body }}</p>

            <details class="source-box">
              <summary>来源消息 {{ selected.sources.length }} 条</summary>
              <div v-for="s in selected.sources" :key="s.messageId" class="source">
                <div class="faint">{{ fmt(s.ts) }} · {{ s.senderName }}</div>
                <p>{{ s.text }}</p>
              </div>
            </details>

            <div v-if="selected.imageIds.length" class="image-box">
              <div class="source-label">关联图片</div>
              <div class="images">
                <img v-for="id in selected.imageIds" :key="id"
                     :src="'/admin/api/distill/images/' + id" alt="聊天图片" />
              </div>
            </div>

            <p class="faint preview-hint">退出多选后可编辑</p>
          </template>

          <Empty
            v-else-if="!selected"
            text="选择一条笔记"
            hint="左侧选择，右侧整理"
          />
          <template v-else>
            <div class="editor-head">
              <span class="faint">#{{ selected.note.id }} · {{ selected.note.model || '未知模型' }} · {{ fmt(selected.note.createdAt) }}</span>
              <Tag :tone="statusTone(selected.note.status)">{{ statusText(selected.note.status) }}</Tag>
            </div>

            <Field label="标题" control-width="1fr">
              <Input
                :model-value="selected.note.title"
                aria-label="笔记标题"
                @update:model-value="(v: string) => { if (selected) selected.note.title = v }"
              />
            </Field>
            <Field label="分类">
              <Input
                :model-value="selected.note.category"
                aria-label="笔记分类"
                @update:model-value="(v: string) => { if (selected) selected.note.category = v }"
              />
            </Field>
            <Field label="别名">
              <Input
                :model-value="selected.note.aliases"
                aria-label="笔记别名"
                @update:model-value="(v: string) => { if (selected) selected.note.aliases = v }"
              />
            </Field>
            <Field label="置信度">
              <div class="num-box">
                <Input
                  :model-value="String(selected.note.confidence)"
                  type="number"
                  aria-label="笔记置信度"
                  @update:model-value="(v: string) => { if (selected) selected.note.confidence = toFloat(v, 0, 0, 1) }"
                />
              </div>
            </Field>
            <Field label="正文" control-width="1fr">
              <Input
                :model-value="selected.note.body"
                multiline
                :rows="10"
                aria-label="笔记正文"
                @update:model-value="(v: string) => { if (selected) selected.note.body = v }"
              />
            </Field>

            <details class="source-box">
              <summary>来源消息 {{ selected.sources.length }} 条</summary>
              <div v-for="s in selected.sources" :key="s.messageId" class="source">
                <div class="faint">{{ fmt(s.ts) }} · {{ s.senderName }}</div>
                <p>{{ s.text }}</p>
              </div>
            </details>

            <div v-if="selected.imageIds.length" class="image-box">
              <div class="source-label">关联图片</div>
              <p class="faint">发布后入库；撤回保留。</p>
              <div class="images">
                <img v-for="id in selected.imageIds" :key="id"
                     :src="'/admin/api/distill/images/' + id" alt="聊天图片" />
              </div>
            </div>

            <div class="editor-actions">
              <Button size="sm" variant="danger" @click="deleteNote">彻底删除</Button>
              <span class="spacer" />
              <Button v-if="selected.note.status !== 'published'" size="sm" @click="publishNote">发布</Button>
              <Button v-else size="sm" @click="unpublishNote">撤回</Button>
              <Button size="sm" variant="primary" @click="saveNote">保存</Button>
            </div>
          </template>
        </section>
      </div>
    </Panel>

    <Panel
      title="运行记录"
      :count="jobTotal"
      hint="任务与批次"
      collapsible
      :default-open="false"
      mobile-collapsed
    >
      <DataTable
        cards
        label="蒸馏任务表"
        :rows="jobs.length"
        empty="还没有任务"
        empty-hint="达到触发条件后会自动创建"
      >
        <thead>
          <tr>
            <th class="num">#</th>
            <th class="num">消息</th>
            <th class="num">批次</th>
            <th class="num">笔记</th>
            <th>状态</th>
            <th>创建时间</th>
            <th class="c-ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="j in jobs" :key="j.id">
            <tr>
              <td class="num" data-label="#">{{ j.id }}</td>
              <td class="num" data-label="消息">{{ j.messageCount }}</td>
              <td class="num" data-label="批次">{{ j.batchCount }}</td>
              <td class="num" data-label="笔记">
                <Tag v-if="j.noteCount > 0" tone="good">{{ j.noteCount }} 条</Tag>
                <Tag v-else-if="j.status === 'done'" tone="neutral">无产出</Tag>
                <span v-else class="faint">—</span>
              </td>
              <td data-label="状态">
                <Tag :tone="statusTone(j.status)">{{ statusText(j.status) }}</Tag>
                <div v-if="j.error" class="faint err">{{ j.error }}</div>
              </td>
              <td class="faint" data-label="创建时间">{{ fmt(j.createdAt) }}</td>
              <td class="c-ops" data-label="操作">
                <div class="row-actions">
                  <Button size="sm" @click="toggleJob(j.id)">
                    {{ selectedJob?.job.id === j.id ? '收起' : '详情' }}
                  </Button>
                  <Button v-if="j.status === 'running'" size="sm"
                          :disabled="jobBusy === j.id + ':pause'" @click="pauseJob(j.id)">
                    {{ jobBusy === j.id + ':pause' ? '暂停中…' : '暂停' }}
                  </Button>
                  <Button v-else-if="j.status === 'paused'" size="sm"
                          :disabled="jobBusy === j.id + ':resume'" @click="resumeJob(j.id)">
                    {{ jobBusy === j.id + ':resume' ? '继续中…' : '继续' }}
                  </Button>
                  <Button v-else-if="j.status === 'ready' || j.status === 'failed'
                          || (j.status === 'done' && (j.error || j.noteCount === 0))"
                          size="sm" :disabled="jobBusy === j.id + ':retry'" @click="retryJob(j.id)">
                    {{ jobBusy === j.id + ':retry' ? '重试中…' : '重试' }}
                  </Button>
                </div>
              </td>
            </tr>

            <tr v-if="selectedJob?.job.id === j.id" class="detail-row">
              <td colspan="7">
                <div class="job-detail">
                  <div class="job-detail-head">
                    <Tag :tone="statusTone(selectedJob.job.status)">{{ statusText(selectedJob.job.status) }}</Tag>
                    <span class="faint">
                      {{ selectedJob.job.messageCount }} 条消息 · {{ selectedJob.job.batchCount }} 批 ·
                      {{ selectedJob.job.noteCount }} 条笔记
                    </span>
                  </div>
                  <p v-if="selectedJob.job.error" class="faint err">{{ selectedJob.job.error }}</p>
                  <DataTable
                    label="批次表"
                    :rows="selectedJob.batches.length"
                    empty="没有批次"
                    :cards="selectedJob.batches.length <= 10"
                  >
                    <thead>
                      <tr>
                        <th class="num">批次</th>
                        <th class="num">消息</th>
                        <th class="num">字数</th>
                        <th>状态</th>
                        <th>模型</th>
                        <th>错误</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="b in selectedJob.batches" :key="b.id">
                        <td class="num" data-label="批次">{{ b.seq }}</td>
                        <td class="num" data-label="消息">{{ b.messageCount }}</td>
                        <td class="num" data-label="字数">{{ b.charCount }}</td>
                        <td data-label="状态">
                          <Tag :tone="statusTone(b.status)">{{ statusText(b.status) }}</Tag>
                        </td>
                        <td class="faint" data-label="模型">{{ b.model || '—' }}</td>
                        <td class="faint err" data-label="错误">{{ b.error || '—' }}</td>
                      </tr>
                    </tbody>
                  </DataTable>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </DataTable>

      <div class="pager">
        <span class="faint">共 {{ jobTotal }} 条</span>
        <Button size="sm" :disabled="jobPage <= 1" @click="goJobPage(jobPage - 1)">上一页</Button>
        <span class="faint">{{ jobPage }} / {{ jobPages }}</span>
        <Button size="sm" :disabled="jobPage >= jobPages" @click="goJobPage(jobPage + 1)">下一页</Button>
      </div>
    </Panel>

    <Panel
      title="高级：导入历史聊天"
      hint="补历史用"
      collapsible
      :default-open="false"
      mobile-collapsed
    >
      <Field label="归属群">
        <Select
          v-model="importGroupId"
          :options="groupOptions"
          aria-label="历史聊天归属群"
        />
      </Field>
      <Field label="聊天原文" hint="TXT / JSONL" control-width="1fr">
        <Input
          v-model="importText"
          multiline
          :rows="6"
          placeholder="2026-10-20 21:03:11 甲: 灵火祭坛在东边&#10;2026-10-20 21:03:40 乙: 还要三个树脂"
          aria-label="历史聊天原文"
        />
      </Field>
      <div class="actions">
        <Button size="sm" @click="importHistory">导入</Button>
      </div>
    </Panel>

  </div>
</template>

<style scoped>
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

.inline { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: nowrap; }
.settings-panel :deep(.field) { padding: 6px 0; gap: var(--sp-3); align-items: center; }
.settings-panel :deep(.field + .field) { border-top: 1px solid var(--hairline); }
.settings-panel :deep(.field:first-child) { padding-top: 0; }
.settings-panel :deep(.field:last-child) { padding-bottom: 0; }
.settings-panel :deep(.field .hint) { margin-top: 0; line-height: 1.4; }
.chips { display: flex; align-items: center; gap: var(--sp-1); flex-wrap: wrap; }
.num-box { display: block; width: 72px; }
.num-box :deep(.inp) { text-align: right; font-variant-numeric: tabular-nums; }
.faint { color: var(--ink-3); font-size: var(--fs-meta); }

.form-foot {
  display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3);
  flex-wrap: wrap; border-top: 1px solid var(--hairline); padding-top: var(--sp-3);
  margin-top: var(--sp-2);
}
.actions { display: flex; gap: var(--sp-2); flex-wrap: wrap; }

.spacer { flex: 1; }

.blocklist-tools { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.blocklist-tools > :first-child { flex: 1; min-width: 220px; }
.blocklist-tools--secondary { margin-top: var(--sp-2); }
.blocked-list { display: flex; flex-direction: column; margin-top: var(--sp-3); }
.blocked-item { display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-2) 0;
  border-bottom: 1px solid var(--hairline); }
.blocked-name { flex: 1; min-width: 0; font-size: var(--fs-sm); overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap; }
.blocklist-hint { margin-top: var(--sp-3); }

.notes-workbench { display: grid; grid-template-columns: minmax(250px, 340px) minmax(0, 1fr);
  gap: var(--sp-4); align-items: start; }
.notes-list { min-width: 0; border-right: 1px solid var(--hairline); padding-right: var(--sp-3); }
.notes-tools { display: flex; flex-direction: column; gap: var(--sp-2); }
.notes-summary { margin: var(--sp-2) 0; }
.notes-scroll { max-height: 560px; overflow-y: auto; }
.notes-bulk-row { display: flex; gap: var(--sp-2); flex-wrap: wrap; }
.bulk-bar { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-2);
  padding: var(--sp-2) 0; border-bottom: 1px solid var(--hairline); }
.note-headline { display: flex; align-items: center; gap: var(--sp-2); min-width: 0; }
.pick { width: 14px; height: 14px; flex: none; border: 1px solid var(--edge); border-radius: 3px; }
.pick.on { background: var(--ember); border-color: var(--ember); box-shadow: inset 0 0 0 2px var(--paper); }
.note-item.picked { background: color-mix(in srgb, var(--ember) 10%, transparent); }
.note-item { display: flex; flex-direction: column; gap: 4px; width: 100%; text-align: left;
  background: transparent; border: 0; border-bottom: 1px solid var(--hairline);
  padding: var(--sp-2) var(--sp-2); color: var(--ink); cursor: pointer; font: inherit; }
.note-item:hover { background: var(--surface-hover); }
.note-item.on { background: var(--surface-hover); box-shadow: inset 2px 0 0 var(--ember); }
.note-line { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-2); }
.note-title { font-size: var(--fs-sm); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.note-meta { display: flex; align-items: center; gap: var(--sp-1); flex-wrap: wrap;
  color: var(--ink-3); font-size: var(--fs-xs); }
.notes-editor { min-width: 0; }
.editor-head { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-2);
  padding-bottom: var(--sp-2); margin-bottom: var(--sp-2); border-bottom: 1px solid var(--hairline); }
.preview-title { margin: 0 0 var(--sp-2); font-size: var(--fs-lg); font-weight: var(--fw-medium); line-height: 1.4; }
.preview-meta { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.preview-body { margin: var(--sp-3) 0; white-space: pre-wrap; line-height: 1.75; }
.preview-hint { margin-top: var(--sp-4); }

.pager { display: flex; align-items: center; justify-content: flex-end; gap: var(--sp-2);
  padding-top: var(--sp-3); flex-wrap: wrap; }
.row-actions { display: flex; gap: var(--sp-1); flex-wrap: wrap; }
.err { margin-top: 2px; line-height: 1.5; overflow-wrap: anywhere; }

.sub-title { font-size: var(--fs-sm); font-weight: var(--fw-medium); margin: var(--sp-4) 0 var(--sp-2); }

.detail-row td { background: var(--surface-inset); padding: var(--sp-3) var(--sp-4); }
.job-detail, .note-editor { display: flex; flex-direction: column; gap: var(--sp-2); }
.job-detail-head { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.note-head { display: flex; align-items: center; gap: var(--sp-2); margin-bottom: var(--sp-2); flex-wrap: wrap; }
.source-box { margin-top: var(--sp-3); border-top: 1px solid var(--hairline); padding-top: var(--sp-2); }
.source-box summary { cursor: pointer; color: var(--ink-2); font-size: var(--fs-sm); }
.source { border-left: 2px solid var(--edge); padding-left: var(--sp-3); margin-top: var(--sp-2); }
.source p { margin: 2px 0 0; white-space: pre-wrap; font-size: var(--fs-meta); }
.source-label { font-size: var(--fs-sm); color: var(--ink-2); }
.image-box { margin-top: var(--sp-3); }
.images { display: flex; gap: var(--sp-2); flex-wrap: wrap; margin-top: var(--sp-2); }
.images img { max-width: 180px; max-height: 140px; border: 1px solid var(--edge); border-radius: var(--r-sm); }
.editor-actions { display: flex; align-items: center; gap: var(--sp-2); margin-top: var(--sp-2);
  border-top: 1px solid var(--hairline); padding-top: var(--sp-3); flex-wrap: wrap; }

@media (max-width: 900px) {
  .notes-workbench { grid-template-columns: 1fr; }
  .notes-list { border-right: 0; border-bottom: 1px solid var(--hairline); padding-right: 0;
    padding-bottom: var(--sp-3); }
  .notes-scroll { max-height: 280px; }
}

@media (max-width: 760px) {
  .form-foot { align-items: flex-start; }
  .inline { flex-wrap: wrap; }
}
</style>
