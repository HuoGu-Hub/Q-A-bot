<script setup lang="ts">
/**
 * 知识库 → 聊天蒸馏。
 *
 * 页面只有两件事：
 *   1. 配自动蒸馏的触发条件：累计多少条消息、多少张图，自动切一批。
 *   2. 管理蒸馏出来的知识：搜、改、发布、撤回、删除。
 *
 * 导入历史文件是补数据的旁路，收进「高级」，不占主流程。
 */
import { computed, onMounted, ref } from 'vue'
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
  lastMessageId: number
  updatedAt: string
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

const loading = ref(false)
const error = ref('')
const ok = ref('')
const stats = ref<Stats | null>(null)
const groups = ref<number[]>([])
const settings = ref<Settings | null>(null)
const jobs = ref<Job[]>([])
const notes = ref<Note[]>([])
const selected = ref<NoteDetail | null>(null)
const selectedJob = ref<{ job: Job; batches: Batch[] } | null>(null)
const search = ref('')
const statusFilter = ref('')
const importText = ref('')
const importGroupId = ref(0)

const form = ref({
  autoEnabled: false,
  triggerMessages: 100,
  triggerImages: 10,
  batchMessages: 80,
  overlap: 5,
  maxGapMinutes: 30,
  autoPublish: true,
  minConfidence: 0.75,
})

const filteredNotes = computed(() => {
  const q = search.value.trim().toLowerCase()
  if (!q) return notes.value
  return notes.value.filter(n =>
    (n.title + ' ' + n.category + ' ' + n.body).toLowerCase().includes(q))
})

const confidencePercent = computed({
  get: () => Math.round(form.value.minConfidence * 100),
  set: (v: number) => { form.value.minConfidence = Math.max(0, Math.min(100, v)) / 100 },
})

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    const [s, cfg] = await Promise.all([
      adminApi.get<Stats>('/distill/stats'),
      adminApi.get<{ settings: Settings; groups: number[] }>('/distill/settings'),
    ])
    stats.value = s
    settings.value = cfg.settings
    groups.value = cfg.groups ?? []
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
      }
      if (!importGroupId.value) importGroupId.value = groups.value[0] ?? 0
    }
    await Promise.all([loadNotes(), loadJobs()])
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
  const r = await adminApi.get<{ rows: Job[] }>('/distill/jobs?limit=30')
  jobs.value = r.rows ?? []
}

async function saveSettings() {
  error.value = ''
  ok.value = ''
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
      }
    }
    ok.value = form.value.autoEnabled ? '已保存，达到条件后会自动开始' : '已保存，自动蒸馏已关闭'
  } catch (e) {
    error.value = describeError(e)
  }
}

async function runNow() {
  error.value = ''
  ok.value = ''
  try {
    const r = await adminApi.post<{ jobId: number }>('/distill/settings/run-now')
    ok.value = '任务 #' + r.jobId + ' 已开始，去「运行记录」看进度'
    await Promise.all([loadJobs(), loadNotes()])
  } catch (e) {
    error.value = describeError(e)
  }
}

async function selectNote(id: number) {
  try {
    selected.value = await adminApi.get<NoteDetail>('/distill/notes/' + id)
  } catch (e) {
    error.value = describeError(e)
  }
}

async function saveNote() {
  if (!selected.value) return
  error.value = ''
  ok.value = ''
  try {
    const n = selected.value.note
    await adminApi.put('/distill/notes/' + n.id, {
      title: n.title,
      aliases: n.aliases,
      category: n.category,
      body: n.body,
      confidence: n.confidence,
    })
    ok.value = '已保存'
    await Promise.all([selectNote(n.id), loadNotes()])
  } catch (e) {
    error.value = describeError(e)
  }
}

async function publishNote() {
  if (!selected.value) return
  try {
    await adminApi.post('/distill/notes/' + selected.value.note.id + '/publish')
    ok.value = '已发布到知识库'
    await Promise.all([selectNote(selected.value.note.id), loadAll()])
  } catch (e) {
    error.value = describeError(e)
  }
}

async function unpublishNote() {
  if (!selected.value) return
  try {
    await adminApi.post('/distill/notes/' + selected.value.note.id + '/unpublish')
    ok.value = '已撤回；块还在，只是不参与检索'
    await Promise.all([selectNote(selected.value.note.id), loadAll()])
  } catch (e) {
    error.value = describeError(e)
  }
}

async function deleteNote() {
  if (!selected.value) return
  if (!window.confirm('彻底删除这条笔记？知识库块会一起删除，不能恢复。')) return
  try {
    await adminApi.del('/distill/notes/' + selected.value.note.id)
    selected.value = null
    ok.value = '已彻底删除'
    await loadAll()
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
    await Promise.all([loadJobs(), openJob(id)])
  } catch (e) {
    error.value = describeError(e)
  }
}

async function pauseJob(id: number) {
  try {
    await adminApi.post('/distill/jobs/' + id + '/pause')
    await loadJobs()
  } catch (e) {
    error.value = describeError(e)
  }
}

async function retryJob(id: number) {
  try {
    await adminApi.post('/distill/jobs/' + id + '/retry')
    await Promise.all([loadJobs(), openJob(id)])
  } catch (e) {
    error.value = describeError(e)
  }
}

async function importHistory() {
  error.value = ''
  ok.value = ''
  const group = importGroupId.value || groups.value[0] || 0
  if (!group) {
    error.value = '没有可用的采集群'
    return
  }
  try {
    const r = await adminApi.postText<{ importId: number; inserted: number; duplicates: number }>(
      '/distill/import?filename=' + encodeURIComponent('history-' + Date.now() + '.txt')
        + '&groupId=' + group,
      importText.value)
    ok.value = '历史导入 #' + r.importId + '：写入 ' + r.inserted + ' 条，重复 ' + r.duplicates + ' 条'
    importText.value = ''
    await loadAll()
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

onMounted(loadAll)
</script>

<template>
  <div class="distill-page">
    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-else-if="ok" tone="ok">{{ ok }}</Notice>

    <header class="page-head">
      <div>
        <div class="eyebrow">知识库 / 聊天蒸馏</div>
        <h2>把群聊变成能检索的经验</h2>
        <p>自动累计群消息，按条件切批整理。你只需要管触发条件和最终知识。</p>
      </div>
      <div class="head-stats">
        <div><b>{{ stats?.messages ?? 0 }}</b><span>条消息</span></div>
        <div><b>{{ stats?.publishedNotes ?? 0 }}</b><span>条已入库</span></div>
        <div><b>{{ stats?.drafts ?? 0 }}</b><span>条待处理</span></div>
      </div>
    </header>

    <section class="settings-card">
      <div class="settings-title">
        <div>
          <h3>自动蒸馏</h3>
          <p v-if="form.autoEnabled">达到任一触发条件就自动开始，不用手动建任务。</p>
          <p v-else>当前关闭，不会自动调用模型。</p>
        </div>
        <label class="switch" title="自动蒸馏开关">
          <input v-model="form.autoEnabled" type="checkbox" aria-label="自动蒸馏" />
          <span />
        </label>
      </div>

      <div class="trigger-row">
        <span class="label">每累计</span>
        <input v-model.number="form.triggerMessages" class="num" type="number" min="0"
               aria-label="触发消息数" />
        <span>条消息</span>
        <span class="or">或</span>
        <input v-model.number="form.triggerImages" class="num" type="number" min="0"
               aria-label="触发图片数" />
        <span>张图片</span>
        <span class="label">触发一次蒸馏</span>
      </div>
      <p class="hint">任一条件先满足就触发；只处理尚未蒸馏的新消息，不会重复。填 0 表示不按这个条件。</p>

      <div class="option-grid">
        <label>每批最多
          <input v-model.number="form.batchMessages" class="num" type="number" min="1" max="500" />
          <span>条</span>
        </label>
        <label>相邻批次重叠
          <input v-model.number="form.overlap" class="num" type="number" min="0" />
          <span>条</span>
        </label>
        <label>间隔超过
          <input v-model.number="form.maxGapMinutes" class="num" type="number" min="0" />
          <span>分钟就断开</span>
        </label>
        <label class="check">
          <input v-model="form.autoPublish" type="checkbox" />
          达标的笔记自动发布
        </label>
        <label>最低置信度
          <input v-model.number="confidencePercent" class="num" type="number" min="0" max="100" />
          <span>%</span>
        </label>
      </div>

      <div class="settings-foot">
        <div class="groups">
          <span class="label">采集群</span>
          <span v-for="g in groups" :key="g" class="chip">{{ g }}</span>
          <span v-if="!groups.length" class="hint">未配置采集群</span>
        </div>
        <div class="foot-actions">
          <Button size="sm" :disabled="loading" @click="saveSettings">保存设置</Button>
          <Button size="sm" variant="primary" @click="runNow">立即蒸馏一次</Button>
        </div>
      </div>
    </section>

    <section class="notes-section">
      <div class="section-head">
        <div>
          <h3>知识笔记</h3>
          <p>模型整理出的经验，确认后发布；撤回的块保留可恢复，彻底删除不可恢复。</p>
        </div>
        <div class="filters">
          <input v-model="search" class="search" placeholder="搜标题、分类或正文"
                 aria-label="搜索笔记" />
          <select v-model="statusFilter" aria-label="按状态筛选" @change="loadNotes">
            <option value="">全部状态</option>
            <option value="draft">待处理</option>
            <option value="published">已发布</option>
            <option value="duplicate">重复</option>
            <option value="rejected">已拒绝</option>
          </select>
          <Button size="sm" :disabled="loading" @click="loadAll">刷新</Button>
        </div>
      </div>

      <div class="notes-layout">
        <div class="note-list">
          <Empty v-if="!filteredNotes.length" text="还没有匹配的笔记"
                 hint="调整筛选，或等自动蒸馏产出" />
          <button v-for="n in filteredNotes" :key="n.id" type="button"
                  class="note-row" :class="{ on: selected?.note.id === n.id }"
                  @click="selectNote(n.id)">
            <span class="note-main">
              <span class="note-title">{{ n.title }}</span>
              <span class="note-meta">
                <span class="chip small">{{ n.category || '未分类' }}</span>
                <span>{{ conf(n.confidence) }}</span>
                <span>·</span>
                <span>{{ fmt(n.createdAt) }}</span>
              </span>
            </span>
            <span class="dot" :class="n.status" :title="statusText(n.status)" />
          </button>
        </div>

        <div class="editor">
          <div v-if="!selected" class="editor-empty">
            <p>从左边选一条笔记开始整理。</p>
            <p class="hint">新笔记会先进入「待处理」，确认后再发布。</p>
          </div>
          <template v-else>
            <div class="editor-head">
              <span class="status-pill" :class="selected.note.status">{{ statusText(selected.note.status) }}</span>
              <span class="hint">#{{ selected.note.id }} · {{ selected.note.model || '未知模型' }}
                · {{ fmt(selected.note.createdAt) }}</span>
            </div>

            <input v-model="selected.note.title" class="title-input" placeholder="标题" />
            <div class="editor-row">
              <label>分类<input v-model="selected.note.category" /></label>
              <label>别名<input v-model="selected.note.aliases" placeholder="多个用、分隔" /></label>
              <label>置信度<input v-model.number="selected.note.confidence" class="num"
                                 type="number" min="0" max="1" step="0.05" /></label>
            </div>
            <textarea v-model="selected.note.body" class="body-input" rows="14" placeholder="正文" />

            <div class="editor-actions">
              <Button size="sm" variant="primary" @click="saveNote">保存</Button>
              <Button v-if="selected.note.status !== 'published'" size="sm" @click="publishNote">发布</Button>
              <Button v-else size="sm" @click="unpublishNote">撤回</Button>
              <Button size="sm" variant="danger" @click="deleteNote">彻底删除</Button>
            </div>

            <details class="source-box">
              <summary>来源消息 {{ selected.sources.length }} 条</summary>
              <div v-for="s in selected.sources" :key="s.messageId" class="source">
                <div class="source-meta">{{ fmt(s.ts) }} · {{ s.senderName }}</div>
                <p>{{ s.text }}</p>
              </div>
            </details>

            <div v-if="selected.imageIds.length" class="image-box">
              <div class="label">关联图片</div>
              <div class="images">
                <img v-for="id in selected.imageIds" :key="id"
                     :src="'/admin/api/distill/images/' + id" alt="聊天图片" />
              </div>
            </div>
          </template>
        </div>
      </div>
    </section>

    <details class="runs">
      <summary>运行记录 <span class="hint">{{ jobs.length }} 个任务</span></summary>
      <div class="runs-body">
        <Empty v-if="!jobs.length" text="还没有任务" hint="达到触发条件后会自动创建" />
        <table v-else class="tbl">
          <thead><tr><th>#</th><th>消息</th><th>批次</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="j in jobs" :key="j.id" :class="{ on: selectedJob?.job.id === j.id }">
              <td>{{ j.id }}</td>
              <td>{{ j.messageCount }}</td>
              <td>{{ j.batchCount }}</td>
              <td><span class="status-pill" :class="j.status">{{ statusText(j.status) }}</span></td>
              <td>{{ fmt(j.createdAt) }}</td>
              <td class="ops">
                <button type="button" @click="openJob(j.id)">批次</button>
                <button type="button" :disabled="j.status === 'running'" @click="startJob(j.id)">开始</button>
                <button type="button" @click="pauseJob(j.id)">暂停</button>
                <button type="button" @click="retryJob(j.id)">重试</button>
              </td>
            </tr>
          </tbody>
        </table>

        <table v-if="selectedJob" class="tbl batches">
          <thead><tr><th>批次</th><th>消息数</th><th>字数</th><th>状态</th><th>模型</th><th>错误</th></tr></thead>
          <tbody>
            <tr v-for="b in selectedJob.batches" :key="b.id">
              <td>{{ b.seq }}</td>
              <td>{{ b.messageCount }}</td>
              <td>{{ b.charCount }}</td>
              <td>{{ statusText(b.status) }}</td>
              <td>{{ b.model || '—' }}</td>
              <td class="err">{{ b.error }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>

    <details class="advanced">
      <summary>高级：导入历史聊天</summary>
      <div class="advanced-body">
        <p class="hint">只有补历史数据时才用；实时群消息会自动采集，不需要这里。</p>
        <div class="editor-row">
          <label>归属群
            <select v-model.number="importGroupId">
              <option v-for="g in groups" :key="g" :value="g">{{ g }}</option>
            </select>
          </label>
        </div>
        <textarea v-model="importText" rows="6"
                  placeholder="粘贴 TXT 或 JSONL。TXT 例：2026-10-20 21:03:11 甲: 灵火祭坛在东边" />
        <div class="editor-actions">
          <Button size="sm" @click="importHistory">导入</Button>
        </div>
      </div>
    </details>
  </div>
</template>

<style scoped>
.distill-page { display: flex; flex-direction: column; gap: var(--sp-4); color: var(--ink); }
.page-head { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--sp-4);
  border-bottom: 1px solid var(--edge-soft); padding-bottom: var(--sp-3); }
.eyebrow { color: var(--copper); font-size: 11px; letter-spacing: .08em; text-transform: uppercase; }
.page-head h2 { margin: 4px 0; font-size: 1.55rem; letter-spacing: -.02em; }
.page-head p { margin: 0; color: var(--ink-dim); font-size: var(--fs-meta); max-width: 56ch; }
.head-stats { display: flex; gap: var(--sp-4); }
.head-stats div { display: flex; flex-direction: column; align-items: flex-end; }
.head-stats b { font-family: var(--font-mono); font-size: 1.35rem; font-variant-numeric: tabular-nums; }
.head-stats span { color: var(--ink-dim); font-size: 11px; }

.settings-card { border: 1px solid var(--edge-soft); border-radius: var(--r-sm);
  background: var(--surface-raised); padding: var(--sp-4); display: flex; flex-direction: column;
  gap: var(--sp-3); }
.settings-title { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--sp-3); }
.settings-title h3 { margin: 0 0 3px; font-size: 1rem; }
.settings-title p { margin: 0; color: var(--ink-dim); font-size: var(--fs-meta); }
.switch { position: relative; width: 42px; height: 24px; flex: none; cursor: pointer; }
.switch input { position: absolute; opacity: 0; }
.switch span { position: absolute; inset: 0; border-radius: 999px; background: var(--surface-inset);
  border: 1px solid var(--edge-soft); transition: background .15s; }
.switch span::after { content: ''; position: absolute; width: 16px; height: 16px; border-radius: 50%;
  top: 3px; left: 3px; background: var(--ink-dim); transition: transform .15s, background .15s; }
.switch input:checked + span { background: color-mix(in oklab, var(--copper) 35%, transparent); }
.switch input:checked + span::after { transform: translateX(18px); background: var(--copper); }

.trigger-row { display: flex; align-items: center; gap: 7px; flex-wrap: wrap;
  padding: var(--sp-3); background: var(--surface-inset); border-radius: var(--r-sm); }
.trigger-row .label { color: var(--ink-dim); }
.trigger-row .or { color: var(--ink-faint); margin: 0 4px; }
.num { width: 68px; font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
input, select, textarea { background: var(--surface-inset); border: 1px solid var(--edge-soft);
  border-radius: var(--r-xs); color: var(--ink); padding: 5px 7px; font: inherit; }
input:focus, select:focus, textarea:focus { outline: 1px solid var(--copper-dim); outline-offset: 0; }
.hint { color: var(--ink-dim); font-size: 11px; margin: 0; }
.option-grid { display: flex; gap: var(--sp-4); flex-wrap: wrap; }
.option-grid label { display: flex; align-items: center; gap: 5px; color: var(--ink-dim);
  font-size: var(--fs-meta); }
.option-grid label.check { gap: 7px; color: var(--ink); }
.settings-foot { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3);
  flex-wrap: wrap; border-top: 1px solid var(--edge-soft); padding-top: var(--sp-3); }
.groups { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.label { color: var(--ink-dim); font-size: 11px; }
.chip { display: inline-block; padding: 2px 8px; border-radius: 999px;
  border: 1px solid var(--edge-soft); color: var(--ink-dim); font-family: var(--font-mono);
  font-size: 11px; }
.chip.small { padding: 1px 6px; font-family: inherit; }
.foot-actions { display: flex; gap: var(--sp-2); }

.notes-section { display: flex; flex-direction: column; gap: var(--sp-3); }
.section-head { display: flex; align-items: flex-end; justify-content: space-between;
  gap: var(--sp-3); flex-wrap: wrap; }
.section-head h3 { margin: 0 0 3px; font-size: 1rem; }
.section-head p { margin: 0; color: var(--ink-dim); font-size: var(--fs-meta); }
.filters { display: flex; gap: var(--sp-2); align-items: center; }
.search { width: 210px; }
.notes-layout { display: grid; grid-template-columns: minmax(260px, 360px) minmax(0, 1fr);
  gap: var(--sp-3); align-items: start; }
.note-list { border: 1px solid var(--edge-soft); border-radius: var(--r-sm);
  background: var(--surface-raised); overflow: hidden; max-height: 640px; overflow-y: auto; }
.note-row { display: flex; align-items: center; gap: var(--sp-2); width: 100%; text-align: left;
  padding: 10px 12px; background: transparent; border: 0; border-bottom: 1px solid var(--edge-soft);
  color: var(--ink); cursor: pointer; font: inherit; }
.note-row:last-child { border-bottom: 0; }
.note-row:hover { background: color-mix(in oklab, var(--surface-inset) 70%, transparent); }
.note-row.on { background: color-mix(in oklab, var(--copper) 12%, transparent);
  box-shadow: inset 2px 0 0 var(--copper); }
.note-main { display: flex; flex-direction: column; gap: 4px; min-width: 0; flex: 1; }
.note-title { font-size: var(--fs-meta); font-weight: var(--fw-medium); white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis; }
.note-meta { display: flex; align-items: center; gap: 5px; color: var(--ink-dim); font-size: 11px;
  white-space: nowrap; overflow: hidden; }
.dot { width: 7px; height: 7px; border-radius: 50%; flex: none; background: var(--ink-faint); }
.dot.published { background: var(--moss); }
.dot.draft { background: var(--brass); }
.dot.duplicate, .dot.rejected { background: var(--blight); }

.editor { border: 1px solid var(--edge-soft); border-radius: var(--r-sm);
  background: var(--surface-raised); padding: var(--sp-4); min-height: 360px; }
.editor-empty { display: flex; flex-direction: column; justify-content: center; min-height: 320px;
  align-items: center; color: var(--ink-dim); gap: 4px; }
.editor-head { display: flex; align-items: center; gap: var(--sp-2); margin-bottom: var(--sp-2);
  flex-wrap: wrap; }
.status-pill { font-size: 11px; padding: 2px 8px; border-radius: 999px;
  border: 1px solid var(--edge-soft); color: var(--ink-dim); }
.status-pill.published { color: var(--moss); border-color: color-mix(in oklab, var(--moss) 45%, transparent); }
.status-pill.running { color: var(--copper); border-color: var(--copper-dim); }
.status-pill.failed, .status-pill.rejected { color: var(--blight); }
.title-input { width: 100%; font-size: 1.15rem; font-weight: var(--fw-medium); padding: 7px 0;
  background: transparent; border: 0; border-bottom: 1px solid var(--edge-soft); border-radius: 0; }
.editor-row { display: flex; gap: var(--sp-3); flex-wrap: wrap; margin: var(--sp-3) 0; }
.editor-row label { display: flex; flex-direction: column; gap: 4px; color: var(--ink-dim);
  font-size: 11px; }
.body-input { width: 100%; resize: vertical; line-height: 1.7; font-size: var(--fs-meta); }
.editor-actions { display: flex; gap: var(--sp-2); flex-wrap: wrap; margin-top: var(--sp-2); }
.source-box { margin-top: var(--sp-4); border-top: 1px solid var(--edge-soft); padding-top: var(--sp-2); }
.source-box summary { cursor: pointer; color: var(--ink-dim); font-size: var(--fs-meta); }
.source { border-left: 2px solid var(--copper-dim); padding-left: var(--sp-3); margin-top: var(--sp-2); }
.source-meta { color: var(--ink-dim); font-size: 11px; }
.source p { margin: 3px 0 0; white-space: pre-wrap; font-size: var(--fs-meta); }
.image-box { margin-top: var(--sp-3); }
.images { display: flex; gap: var(--sp-2); flex-wrap: wrap; margin-top: var(--sp-2); }
.images img { max-width: 180px; max-height: 140px; border: 1px solid var(--edge-soft); border-radius: var(--r-xs); }

.runs, .advanced { border: 1px solid var(--edge-soft); border-radius: var(--r-sm);
  background: var(--surface-raised); }
.runs summary, .advanced summary { cursor: pointer; padding: 10px 12px; font-size: var(--fs-meta); }
.runs-body, .advanced-body { padding: 0 12px 12px; display: flex; flex-direction: column;
  gap: var(--sp-2); }
.tbl { width: 100%; border-collapse: collapse; font-size: var(--fs-meta); }
.tbl th, .tbl td { text-align: left; padding: 7px 8px; border-bottom: 1px solid var(--edge-soft); }
.tbl th { color: var(--ink-dim); font-weight: var(--fw-medium); }
.tbl tr.on { background: color-mix(in oklab, var(--copper) 9%, transparent); }
.tbl.batches { margin-top: var(--sp-2); }
.ops { display: flex; gap: 7px; flex-wrap: wrap; }
.ops button { border: 0; background: transparent; color: var(--copper); cursor: pointer;
  padding: 0; font: inherit; }
.ops button:disabled { color: var(--ink-faint); cursor: default; }
.err { color: var(--blight); max-width: 260px; word-break: break-word; }
.advanced-body textarea { width: 100%; }
@media (max-width: 900px) {
  .notes-layout { grid-template-columns: 1fr; }
  .note-list { max-height: 320px; }
  .page-head { align-items: flex-start; flex-direction: column; }
  .head-stats { align-self: stretch; justify-content: space-between; }
}
</style>
