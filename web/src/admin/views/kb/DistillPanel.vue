<script setup lang="ts">
/**
 * 知识库 → 聊天蒸馏。
 *
 * 这一版先把"采集 / 导入 / 分批 / 笔记管理"串起来；模型执行在后台 worker，
 * 页面只负责建任务、看进度、改笔记、发布与撤回。
 */
import { onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import Button from '@shared/ui/Button.vue'
import Notice from '@shared/ui/Notice.vue'
import Empty from '@shared/ui/Empty.vue'

interface CaptureStatus {
  enabled: boolean
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
  imports: number
  jobs: number
  batches: number
  notes: number
  drafts: number
  publishedNotes: number
}

interface Job {
  id: number
  source: string
  groupIds: string
  timeFrom: string
  timeTo: string
  importId: number
  batchMode: string
  batchSize: number
  overlap: number
  maxGapMinutes: number
  status: string
  messageCount: number
  batchCount: number
  error: string
  createdAt: string
}

interface Batch {
  id: number
  seq: number
  messageFrom: number
  messageTo: number
  messageCount: number
  charCount: number
  status: string
  attempts: number
  error: string
  model: string
  tokensIn: number
  tokensOut: number
}

interface Note {
  id: number
  jobId: number
  batchId: number
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

interface ImportPreview {
  filename: string
  format: string
  lines: number
  parsed: number
  errors: number
  samples: string[]
  errorSamples: string[]
}

interface ImportResult {
  importId: number
  format: string
  parsed: number
  inserted: number
  duplicates: number
  errors: number
}

interface JobPreview {
  messageCount: number
  charCount: number
  batchCount: number
  estimatedTokens: number
  batches: { seq: number; messageFrom: number; messageTo: number; messageCount: number; charCount: number }[]
}

const loading = ref(false)
const error = ref('')
const ok = ref('')
const tab = ref<'jobs' | 'notes'>('jobs')
const stats = ref<Stats | null>(null)
const jobs = ref<Job[]>([])
const notes = ref<Note[]>([])
const noteStatus = ref('')
const selectedJob = ref<{ job: Job; batches: Batch[] } | null>(null)
const selectedNote = ref<NoteDetail | null>(null)

const filename = ref('chat.jsonl')
const importText = ref('')
const groupId = ref(0)
const importPreview = ref<ImportPreview | null>(null)
const importResult = ref<ImportResult | null>(null)

const source = ref<'file' | 'live'>('file')
const importId = ref(0)
const batchMode = ref<'count' | 'chars' | 'day'>('count')
const batchSize = ref(80)
const overlap = ref(5)
const maxGap = ref(30)
const timeFrom = ref('')
const timeTo = ref('')
const jobPreview = ref<JobPreview | null>(null)

const editTitle = ref('')
const editAliases = ref('')
const editCategory = ref('')
const editBody = ref('')
const editConfidence = ref(0)

async function load() {
  loading.value = true
  error.value = ''
  try {
    stats.value = await adminApi.get<Stats>('/distill/stats')
    const j = await adminApi.get<{ rows: Job[] }>('/distill/jobs?limit=50')
    jobs.value = j.rows ?? []
    await loadNotes()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function loadNotes() {
  const q = noteStatus.value ? '&status=' + encodeURIComponent(noteStatus.value) : ''
  const r = await adminApi.get<{ rows: Note[] }>('/distill/notes?limit=200' + q)
  notes.value = r.rows ?? []
}

async function previewImport() {
  error.value = ''
  importPreview.value = null
  try {
    importPreview.value = await adminApi.postText<ImportPreview>(
      '/distill/import/preview?filename=' + encodeURIComponent(filename.value || 'chat.txt'),
      importText.value)
  } catch (e) {
    error.value = describeError(e)
  }
}

async function doImport() {
  error.value = ''
  ok.value = ''
  importResult.value = null
  try {
    importResult.value = await adminApi.postText<ImportResult>(
      '/distill/import?filename=' + encodeURIComponent(filename.value || 'chat.txt')
        + '&groupId=' + (groupId.value || 0),
      importText.value)
    ok.value = '导入完成：写入 ' + importResult.value.inserted + ' 条，重复 '
      + importResult.value.duplicates + ' 条'
    // 导入后直接把 id 填进建任务表单，少一步手抄
    importId.value = importResult.value.importId
    source.value = 'file'
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}

function jobRequest() {
  return {
    source: source.value,
    groupIds: [] as number[],
    timeFrom: timeFrom.value,
    timeTo: timeTo.value,
    importId: importId.value,
    batchMode: batchMode.value,
    batchSize: batchSize.value,
    overlap: overlap.value,
    maxGapMinutes: maxGap.value,
  }
}

async function previewJob() {
  error.value = ''
  jobPreview.value = null
  try {
    jobPreview.value = await adminApi.post<JobPreview>('/distill/jobs/preview', jobRequest())
  } catch (e) {
    error.value = describeError(e)
  }
}

async function createJob() {
  error.value = ''
  ok.value = ''
  try {
    const r = await adminApi.post<{ id: number }>('/distill/jobs', jobRequest())
    ok.value = '任务 #' + r.id + ' 已创建'
    await load()
    await openJob(r.id)
  } catch (e) {
    error.value = describeError(e)
  }
}

async function openJob(id: number) {
  try {
    selectedJob.value = await adminApi.get<{ job: Job; batches: Batch[] }>('/distill/jobs/' + id)
  } catch (e) {
    error.value = describeError(e)
  }
}

async function startJob(id: number) {
  try {
    await adminApi.post('/distill/jobs/' + id + '/start')
    await load()
    await openJob(id)
  } catch (e) {
    error.value = describeError(e)
  }
}

async function pauseJob(id: number) {
  try {
    await adminApi.post('/distill/jobs/' + id + '/pause')
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}

async function retryJob(id: number) {
  try {
    await adminApi.post('/distill/jobs/' + id + '/retry')
    await load()
    await openJob(id)
  } catch (e) {
    error.value = describeError(e)
  }
}

async function openNote(id: number) {
  try {
    selectedNote.value = await adminApi.get<NoteDetail>('/distill/notes/' + id)
    const n = selectedNote.value.note
    editTitle.value = n.title
    editAliases.value = n.aliases
    editCategory.value = n.category
    editBody.value = n.body
    editConfidence.value = n.confidence
  } catch (e) {
    error.value = describeError(e)
  }
}

async function saveNote() {
  if (!selectedNote.value) return
  try {
    await adminApi.put('/distill/notes/' + selectedNote.value.note.id, {
      title: editTitle.value,
      aliases: editAliases.value,
      category: editCategory.value,
      body: editBody.value,
      confidence: editConfidence.value,
    })
    ok.value = '笔记已保存'
    await openNote(selectedNote.value.note.id)
    await loadNotes()
  } catch (e) {
    error.value = describeError(e)
  }
}

async function publishNote(id: number) {
  try {
    await adminApi.post('/distill/notes/' + id + '/publish')
    ok.value = '已发布到知识库'
    await load()
    await openNote(id)
  } catch (e) {
    error.value = describeError(e)
  }
}

async function unpublishNote(id: number) {
  try {
    await adminApi.post('/distill/notes/' + id + '/unpublish')
    ok.value = '已撤回，可在回收站恢复'
    await load()
    await openNote(id)
  } catch (e) {
    error.value = describeError(e)
  }
}

async function deleteNote(id: number) {
  if (!window.confirm('彻底删除这条笔记？知识库块会一起删除，不能恢复。')) return
  try {
    await adminApi.del('/distill/notes/' + id)
    ok.value = '已彻底删除'
    selectedNote.value = null
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}

function fmt(s: string) {
  if (!s) return ''
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}

function statusText(s: string) {
  return ({ ready: '待开始', running: '运行中', paused: '已暂停', done: '已完成', failed: '失败',
    pending: '待处理', draft: '草稿', published: '已发布', duplicate: '重复', rejected: '已拒绝' } as Record<string, string>)[s] || s
}

function conf(v: number) {
  return Math.round((v || 0) * 100) + '%'
}

onMounted(load)
</script>

<template>
  <div class="distill">
    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-else-if="ok" tone="ok">{{ ok }}</Notice>

    <div class="stats">
      <div class="stat"><b>{{ stats?.messages ?? 0 }}</b><span>原始消息</span></div>
      <div class="stat"><b>{{ stats?.images ?? 0 }}</b><span>聊天图片</span></div>
      <div class="stat"><b>{{ stats?.jobs ?? 0 }}</b><span>蒸馏任务</span></div>
      <div class="stat"><b>{{ stats?.batches ?? 0 }}</b><span>批次</span></div>
      <div class="stat"><b>{{ stats?.drafts ?? 0 }}</b><span>待处理笔记</span></div>
      <div class="stat"><b>{{ stats?.publishedNotes ?? 0 }}</b><span>已发布</span></div>
    </div>

    <div class="subbar">
      <div class="tabs2">
        <button type="button" :class="{ on: tab === 'jobs' }" @click="tab = 'jobs'">任务</button>
        <button type="button" :class="{ on: tab === 'notes' }" @click="tab = 'notes'; loadNotes()">笔记</button>
      </div>
      <span v-if="stats" class="faint">
        采集{{ stats.capture.enabled ? '已开启' : '未开启' }} · 队列 {{ stats.capture.queueSize }}
      </span>
      <Button size="sm" :disabled="loading" @click="load">{{ loading ? '读取中…' : '刷新' }}</Button>
    </div>

    <template v-if="tab === 'jobs'">
      <section class="card">
        <h3>导入聊天文件</h3>
        <p class="faint">支持 JSONL 与「时间 昵称: 内容」的 TXT。重复导入不会重复写。</p>
        <div class="row">
          <label>文件名<input v-model="filename" /></label>
          <label>群号<input v-model.number="groupId" type="number" /></label>
        </div>
        <textarea v-model="importText" rows="7" placeholder="把聊天文件原文贴在这里" />
        <div class="actions">
          <Button size="sm" @click="previewImport">预览</Button>
          <Button size="sm" variant="primary" @click="doImport">导入</Button>
        </div>
        <p v-if="importPreview" class="result">
          {{ importPreview.format }} · {{ importPreview.parsed }} 条可导入 · {{ importPreview.errors }} 行错误
          <span v-for="s in importPreview.samples" :key="s" class="sample">{{ s }}</span>
        </p>
        <p v-if="importResult" class="result">
          导入 #{{ importResult.importId }}：写入 {{ importResult.inserted }}，重复 {{ importResult.duplicates }}
        </p>
      </section>

      <section class="card">
        <h3>新建蒸馏任务</h3>
        <div class="row">
          <label>来源
            <select v-model="source">
              <option value="file">导入文件</option>
              <option value="live">已采集消息</option>
            </select>
          </label>
          <label v-if="source === 'file'">导入 id<input v-model.number="importId" type="number" /></label>
          <label>分批
            <select v-model="batchMode">
              <option value="count">按条数</option>
              <option value="chars">按字数</option>
              <option value="day">按天</option>
            </select>
          </label>
          <label>每批上限<input v-model.number="batchSize" type="number" /></label>
          <label>重叠<input v-model.number="overlap" type="number" /></label>
          <label>间隙分钟<input v-model.number="maxGap" type="number" /></label>
        </div>
        <div v-if="source === 'live'" class="row">
          <label>开始<input v-model="timeFrom" placeholder="2026-10-20" /></label>
          <label>结束<input v-model="timeTo" placeholder="2026-10-21" /></label>
        </div>
        <div class="actions">
          <Button size="sm" @click="previewJob">预览批次</Button>
          <Button size="sm" variant="primary" @click="createJob">创建任务</Button>
        </div>
        <p v-if="jobPreview" class="result">
          {{ jobPreview.messageCount }} 条消息 · {{ jobPreview.batchCount }} 批 ·
          约 {{ jobPreview.estimatedTokens }} tokens
        </p>
      </section>

      <section class="card">
        <h3>任务</h3>
        <Empty v-if="!jobs.length" text="还没有任务" hint="先导入聊天文件，或打开采集" />
        <table v-else class="tbl">
          <thead><tr><th>#</th><th>来源</th><th>消息</th><th>批次</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="j in jobs" :key="j.id">
              <td>{{ j.id }}</td>
              <td>{{ j.source }}<span v-if="j.importId"> / #{{ j.importId }}</span></td>
              <td>{{ j.messageCount }}</td>
              <td>{{ j.batchCount }}</td>
              <td><span class="pill">{{ statusText(j.status) }}</span></td>
              <td class="ops">
                <button type="button" @click="openJob(j.id)">详情</button>
                <button type="button" :disabled="j.status === 'running'" @click="startJob(j.id)">开始</button>
                <button type="button" @click="pauseJob(j.id)">暂停</button>
                <button type="button" @click="retryJob(j.id)">重试</button>
              </td>
            </tr>
          </tbody>
        </table>
      </section>

      <section v-if="selectedJob" class="card">
        <h3>任务 #{{ selectedJob.job.id }} 的批次</h3>
        <table class="tbl">
          <thead><tr><th>#</th><th>消息范围</th><th>条数</th><th>字数</th><th>状态</th><th>模型</th><th>错误</th></tr></thead>
          <tbody>
            <tr v-for="b in selectedJob.batches" :key="b.id">
              <td>{{ b.seq }}</td>
              <td>{{ b.messageFrom }}–{{ b.messageTo }}</td>
              <td>{{ b.messageCount }}</td>
              <td>{{ b.charCount }}</td>
              <td>{{ statusText(b.status) }}</td>
              <td>{{ b.model || '—' }}</td>
              <td class="err">{{ b.error }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>

    <template v-else>
      <section class="card">
        <div class="subbar">
          <h3>蒸馏笔记</h3>
          <select v-model="noteStatus" @change="loadNotes">
            <option value="">全部</option>
            <option value="draft">草稿</option>
            <option value="published">已发布</option>
            <option value="duplicate">重复</option>
            <option value="rejected">已拒绝</option>
          </select>
        </div>
        <Empty v-if="!notes.length" text="还没有笔记" hint="跑一个任务后，这里会出现模型产出的经验" />
        <table v-else class="tbl">
          <thead><tr><th>标题</th><th>分类</th><th>置信度</th><th>状态</th><th>时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="n in notes" :key="n.id">
              <td>{{ n.title }}</td>
              <td>{{ n.category }}</td>
              <td>{{ conf(n.confidence) }}</td>
              <td><span class="pill">{{ statusText(n.status) }}</span></td>
              <td>{{ fmt(n.createdAt) }}</td>
              <td class="ops">
                <button type="button" @click="openNote(n.id)">查看</button>
                <button v-if="n.status !== 'published'" type="button" @click="publishNote(n.id)">发布</button>
                <button v-else type="button" @click="unpublishNote(n.id)">撤回</button>
                <button type="button" class="danger" @click="deleteNote(n.id)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </section>

      <section v-if="selectedNote" class="card">
        <h3>编辑笔记 #{{ selectedNote.note.id }}</h3>
        <div class="row">
          <label>标题<input v-model="editTitle" /></label>
          <label>分类<input v-model="editCategory" /></label>
          <label>置信度<input v-model.number="editConfidence" type="number" step="0.05" min="0" max="1" /></label>
        </div>
        <label class="block">别名<input v-model="editAliases" /></label>
        <label class="block">正文<textarea v-model="editBody" rows="10" /></label>
        <div class="actions">
          <Button size="sm" variant="primary" @click="saveNote">保存</Button>
          <Button v-if="selectedNote.note.status !== 'published'" size="sm" @click="publishNote(selectedNote.note.id)">发布</Button>
          <Button v-else size="sm" @click="unpublishNote(selectedNote.note.id)">撤回</Button>
          <Button size="sm" variant="danger" @click="deleteNote(selectedNote.note.id)">彻底删除</Button>
        </div>

        <h4>来源消息</h4>
        <div v-for="s in selectedNote.sources" :key="s.messageId" class="source">
          <span class="faint">{{ fmt(s.ts) }} · {{ s.senderName }}</span>
          <p>{{ s.text }}</p>
        </div>

        <template v-if="selectedNote.imageIds.length">
          <h4>关联图片</h4>
          <div class="imgs">
            <img v-for="id in selectedNote.imageIds" :key="id"
                 :src="'/admin/api/distill/images/' + id" alt="聊天图片" />
          </div>
        </template>
      </section>
    </template>
  </div>
</template>

<style scoped>
.distill { display: flex; flex-direction: column; gap: var(--sp-4); }
.stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: var(--sp-2); }
.stat { border: 1px solid var(--edge-soft); border-radius: var(--r-sm); padding: var(--sp-3);
  background: var(--surface-raised); display: flex; flex-direction: column; gap: 2px; }
.stat b { font-family: var(--font-mono); font-size: 1.4em; font-variant-numeric: tabular-nums; }
.stat span { color: var(--ink-dim); font-size: var(--fs-meta); }
.subbar { display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap; }
.subbar h3 { margin: 0; flex: 1; }
.tabs2 { display: flex; gap: 4px; }
.tabs2 button { border: 1px solid var(--edge-soft); background: transparent; color: var(--ink-dim);
  padding: 5px 12px; border-radius: var(--r-sm); cursor: pointer; }
.tabs2 button.on { background: var(--surface-raised); color: var(--ink); border-color: var(--copper-dim); }
.card { border: 1px solid var(--edge-soft); border-radius: var(--r-sm); padding: var(--sp-4);
  background: var(--surface-raised); }
.card h3 { margin: 0 0 var(--sp-2); font-size: var(--fs-section); }
.card h4 { margin: var(--sp-4) 0 var(--sp-2); font-size: var(--fs-body); }
.row { display: flex; flex-wrap: wrap; gap: var(--sp-3); margin-bottom: var(--sp-2); }
.row label, .block { display: flex; flex-direction: column; gap: 4px; color: var(--ink-dim);
  font-size: var(--fs-meta); }
.block { margin: var(--sp-2) 0; }
input, select, textarea { background: var(--surface-inset); border: 1px solid var(--edge-soft);
  border-radius: var(--r-xs); color: var(--ink); padding: 6px 8px; font: inherit; }
textarea { width: 100%; resize: vertical; line-height: 1.6; }
.actions { display: flex; gap: var(--sp-2); flex-wrap: wrap; margin-top: var(--sp-2); }
.result { margin: var(--sp-2) 0 0; color: var(--ink-dim); font-size: var(--fs-meta); }
.sample { display: block; margin-top: 2px; font-family: var(--font-mono); font-size: 11px; }
.tbl { width: 100%; border-collapse: collapse; font-size: var(--fs-meta); }
.tbl th, .tbl td { text-align: left; padding: 7px 8px; border-bottom: 1px solid var(--edge-soft);
  vertical-align: top; }
.tbl th { color: var(--ink-dim); font-weight: var(--fw-medium); }
.ops { display: flex; gap: 6px; flex-wrap: wrap; }
.ops button { border: 0; background: transparent; color: var(--copper); cursor: pointer; padding: 0;
  font: inherit; }
.ops button.danger { color: var(--blight); }
.ops button:disabled { color: var(--ink-faint); cursor: default; }
.pill { display: inline-block; padding: 1px 7px; border-radius: 999px; border: 1px solid var(--edge-soft);
  color: var(--ink-dim); font-size: 11px; }
.err { color: var(--blight); max-width: 240px; word-break: break-word; }
.source { border-left: 2px solid var(--copper-dim); padding-left: var(--sp-3); margin-bottom: var(--sp-2); }
.source p { margin: 2px 0 0; white-space: pre-wrap; }
.imgs { display: flex; gap: var(--sp-2); flex-wrap: wrap; }
.imgs img { max-width: 180px; max-height: 140px; border-radius: var(--r-xs); border: 1px solid var(--edge-soft); }
.faint { color: var(--ink-dim); font-size: var(--fs-meta); }
</style>
