<script setup lang="ts">
/**
 * 「文档」面板 —— **系统唯一的入库入口**。
 *
 * 为什么单独一个 tab 而不是并进「词条」：两者操作的是**两套存储**。
 *   词条面板：按**行号**操作 chunks.jsonl（旧）
 *   本面板  ：按**块 id** 操作 SQLite（新）
 * 语义完全不同，混在一起最容易点错。切到 app.kb.store=blocks 之后，
 * 词条/资料那两个 tab 里跟"块"有关的部分会删掉，只留"名字"那层。
 *
 * 三件事：
 *   ① 导入 —— 粘贴或选文件 → **先预览**（会新增几条、覆盖几条、库里哪些没被碰）
 *      → 确认再导。规则：文件里的 id 存在就覆盖、不存在就新增、没出现的一个都不动。
 *   ② 看 —— 按文档列出块，能直接改正文（**会重算向量**）、下架、真删除。
 *   ③ 整份下架 / 清空 —— 清空是给"首次导入中文语料前"用的，不可逆，要二次确认。
 *
 * ⚠️ 改正文会重算向量，所以每次保存都有一次 embedding 调用 —— 这是有意的：
 *    只改文本不重算向量，检索会按旧正文匹配，改了等于没改。
 */
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type {
  KbBlockStats, KbDocSummary, KbBlockView, KbImportPreview, KbImportResult,
} from '@shared/api/types'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Button from '@shared/ui/Button.vue'
import Input from '@shared/ui/Input.vue'
import Notice from '@shared/ui/Notice.vue'
import Tag from '@shared/ui/Tag.vue'
import Empty from '@shared/ui/Empty.vue'

const loading = ref(false)
const error = ref('')
const okMsg = ref('')

const stats = ref<KbBlockStats | null>(null)
const docs = ref<KbDocSummary[]>([])

/** 待导入的文档全文 */
const docText = ref('')
const preview = ref<KbImportPreview | null>(null)
const importResult = ref<KbImportResult | null>(null)
const busy = ref('')

/** 当前展开的文档 + 它的块 */
const openDoc = ref('')
const blocks = ref<KbBlockView[]>([])
/** 正在编辑哪一块（null = 没在编辑） */
const editing = ref<{ id: string; text: string } | null>(null)

/**
 * 批量导入（拖拽）。
 *
 * 为什么是"先逐份预览、再一起导入"，而不是拖进来直接导：
 * 一次拖十几份的时候人没法逐份点确认，但又必须能看见"这次会改动多少条" ——
 * 所以预览（只算不改）批量跑一遍，结果摆成一张表，确认后再一起导。
 */
interface BatchRow {
  /** 显示用：目录里的相对路径 */
  name: string
  text: string
  status: 'previewing' | 'ready' | 'importing' | 'done' | 'error'
  preview: KbImportPreview | null
  result: KbImportResult | null
  error: string
  include: boolean
}
const batch = ref<BatchRow[]>([])
const dragging = ref(false)
const batchBusy = ref('')
const batchProgress = ref('')

/** 待导入 = 勾上了、且还没导过/没报错的那些 */
const batchReady = computed(function () {
  return batch.value.filter(function (r) {
    return r.include && r.status !== 'error' && r.status !== 'done'
  })
})

/** 批量预览的汇总（把每份的预计改动加起来） */
const batchStats = computed(function () {
  let added = 0, updated = 0, unchanged = 0, warnings = 0
  for (const r of batchReady.value) {
    if (!r.preview) continue
    added += r.preview.added
    updated += r.preview.updated
    unchanged += r.preview.unchanged
    warnings += r.preview.warnings.length
  }
  return { added: added, updated: updated, unchanged: unchanged, warnings: warnings }
})

/** 只认这几种后缀 —— 拖进来一堆图片/压缩包不该报错，忽略即可 */
const DOC_EXT = /\.(md|markdown|txt)$/i

/** 拖拽放下：把 DataTransfer 里的文件和目录都摊平成文件列表 */
async function onDrop(ev: DragEvent) {
  dragging.value = false
  if (batchBusy.value !== '') return
  const dt = ev.dataTransfer
  if (!dt) return
  const found: { file: File; path: string }[] = []
  const entries: any[] = []
  const items = dt.items ? Array.from(dt.items) : []
  for (const it of items) {
    if (it.webkitGetAsEntry) {
      const e = it.webkitGetAsEntry()
      if (e) entries.push(e)
    }
  }
  if (entries.length) {
    for (const e of entries) await walkEntry(e, '', found)
  } else {
    for (const f of Array.from(dt.files)) found.push({ file: f, path: f.name })
  }
  await addFiles(found)
}

/**
 * 递归展开目录。
 *
 * ⚠️ Chrome 的 readEntries **一次最多给 100 个**，给完不会自动继续 ——
 * 必须反复读到返回空数组为止，否则超过 100 个文件的目录会被悄悄截断。
 */
function walkEntry(entry: any, prefix: string, out: { file: File; path: string }[]): Promise<void> {
  return new Promise(function (resolve) {
    if (entry.isFile) {
      entry.file(
        function (f: File) { out.push({ file: f, path: prefix + f.name }); resolve() },
        function () { resolve() },
      )
      return
    }
    if (!entry.isDirectory) { resolve(); return }
    const reader = entry.createReader()
    const kids: any[] = []
    const readMore = function () {
      reader.readEntries(
        function (part: any[]) {
          if (!part.length) {
            const next = function (i: number): Promise<void> {
              if (i >= kids.length) return Promise.resolve()
              return walkEntry(kids[i], prefix + entry.name + '/', out).then(function () { return next(i + 1) })
            }
            next(0).then(function () { resolve() })
            return
          }
          for (const k of part) kids.push(k)
          readMore()
        },
        function () { resolve() },
      )
    }
    readMore()
  })
}

/** 「选文件」也走同一条路（支持多选） */
async function pickFiles(ev: Event) {
  const input = ev.target as HTMLInputElement
  const out: { file: File; path: string }[] = []
  for (const f of Array.from(input.files || [])) out.push({ file: f, path: f.name })
  input.value = ''
  await addFiles(out)
}

/** 加进批次，并逐份预览（**只算不改**） */
async function addFiles(found: { file: File; path: string }[]) {
  const files = found.filter(function (f) {
    return DOC_EXT.test(f.file.name) && f.file.name.charAt(0) !== '.'
  })
  if (!files.length) {
    error.value = '没找到 .md / .txt 文件（拖进来的其他文件会被忽略）'
    return
  }
  batchBusy.value = 'preview'
  error.value = ''
  okMsg.value = ''
  for (let i = 0; i < files.length; i++) {
    batchProgress.value = '正在预览 ' + (i + 1) + '/' + files.length + '：' + files[i].path
    const row: BatchRow = {
      name: files[i].path, text: '', status: 'previewing',
      preview: null, result: null, error: '', include: true,
    }
    batch.value.push(row)
    try {
      row.text = await files[i].file.text()
      row.preview = await adminApi.post<KbImportPreview>('/kb/blocks/preview', { text: row.text })
      row.status = 'ready'
    } catch (e) {
      row.status = 'error'
      row.error = describeError(e)
      row.include = false
    }
    // 读文件 + 请求是一个个来的，久了会看着像卡住；替换数组让界面跟着走
    batch.value = batch.value.slice()
  }
  batchProgress.value = ''
  batchBusy.value = ''
}

/** **顺序**导入勾选的份 —— 顺序有意义：后导的会覆盖同 id 的块 */
async function importBatch() {
  const rows = batchReady.value
  if (!rows.length) return
  batchBusy.value = 'import'
  error.value = ''
  let ok = 0
  for (let i = 0; i < rows.length; i++) {
    const row = rows[i]
    batchProgress.value = '正在导入 ' + (i + 1) + '/' + rows.length + '：' + row.name
    row.status = 'importing'
    batch.value = batch.value.slice()
    try {
      row.result = await adminApi.post<KbImportResult>('/kb/blocks/import', { text: row.text })
      row.status = 'done'
      ok++
    } catch (e) {
      row.status = 'error'
      row.error = describeError(e)
    }
  }
  batch.value = batch.value.slice()
  batchProgress.value = ''
  batchBusy.value = ''
  okMsg.value = '批量导入完成：' + ok + '/' + rows.length + ' 份成功'
  await load()
}

function clearBatch() {
  batch.value = []
  batchProgress.value = ''
}

function toggleRow(r: BatchRow) {
  r.include = !r.include
}

const canPreview = computed(() => docText.value.trim().length > 0 && busy.value === '')

function fail(e: unknown) {
  error.value = describeError(e)
  okMsg.value = ''
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    stats.value = await adminApi.get<KbBlockStats>('/kb/blocks/stats')
    const r = await adminApi.get<{ documents: KbDocSummary[] }>('/kb/blocks/docs')
    docs.value = r.documents ?? []
    if (openDoc.value) {
      await loadBlocks(openDoc.value)
    }
  } catch (e) {
    fail(e)
  } finally {
    loading.value = false
  }
}

async function loadBlocks(doc: string) {
  try {
    const r = await adminApi.get<{ blocks: KbBlockView[] }>(
      '/kb/blocks?doc=' + encodeURIComponent(doc))
    blocks.value = r.blocks ?? []
  } catch (e) {
    fail(e)
  }
}

/** 展开 / 收起某份文档 */
async function toggleDoc(doc: string) {
  if (openDoc.value === doc) {
    openDoc.value = ''
    blocks.value = []
    editing.value = null
    return
  }
  openDoc.value = doc
  editing.value = null
  await loadBlocks(doc)
}

/* ==================== 导入 ==================== */


async function doPreview() {
  busy.value = 'preview'
  error.value = ''
  okMsg.value = ''
  importResult.value = null
  try {
    preview.value = await adminApi.post<KbImportPreview>('/kb/blocks/preview', { text: docText.value })
  } catch (e) {
    preview.value = null
    fail(e)
  } finally {
    busy.value = ''
  }
}

async function doImport() {
  busy.value = 'import'
  error.value = ''
  okMsg.value = ''
  try {
    const r = await adminApi.post<KbImportResult>('/kb/blocks/import', { text: docText.value })
    importResult.value = r
    preview.value = null
    okMsg.value = '导入完成：新增 ' + r.added + ' 条、覆盖 ' + r.updated
      + ' 条、无变化 ' + r.unchanged + ' 条；库里未被触及 ' + r.untouched + ' 条'
      + (r.terms ? '；新建词条 ' + r.terms + ' 条' : '')
    await load()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}

/** 清空全部块（首次导入中文语料前用）—— 不可逆，二次确认 */
async function clearAll() {
  if (!window.confirm('确定清空知识库里的**全部块与向量**？\n\n这是给「首次导入中文语料前」用的，不可逆。')) return
  busy.value = 'clear'
  try {
    await adminApi.post('/kb/blocks/clear')
    okMsg.value = '已清空全部块与向量'
    openDoc.value = ''
    blocks.value = []
    await load()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}

/**
 * 补建 / 刷新**标题向量**（C 路）。
 *
 * 先 dryRun 问一句"要补多少条"，再让人确认 —— 这一步会调向量模型（虽然很便宜），
 * 不该在点一下按钮时就悄悄发生。
 */
async function backfillTitleVectors() {
  busy.value = 'title-vec'
  error.value = ''
  okMsg.value = ''
  try {
    const pre = await adminApi.post<{ pending: number; missing: number; stale: number }>(
      '/kb/blocks/title-vectors/backfill', { dryRun: true })
    if (pre.pending === 0) {
      okMsg.value = '标题向量已经是最新的，不用补'
      return
    }
    if (!window.confirm('有 ' + pre.pending + ' 个块需要补标题向量'
      + '（从没补过 ' + pre.missing + ' 个，标题改过导致过期 ' + pre.stale + ' 个）。\n\n'
      + '会调一次向量模型，现在补吗？')) return
    const r = await adminApi.post<{ message: string; embedded: number }>(
      '/kb/blocks/title-vectors/backfill', {})
    okMsg.value = r.message
    await load()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}

/* ==================== 块操作 ==================== */

function startEdit(b: KbBlockView) {
  editing.value = { id: b.id, text: b.text }
  error.value = ''
  okMsg.value = ''
}

async function saveEdit() {
  if (!editing.value) return
  busy.value = 'save'
  try {
    await adminApi.post('/kb/blocks/update', { id: editing.value.id, text: editing.value.text })
    okMsg.value = '已保存「' + editing.value.id + '」（向量已重算）'
    editing.value = null
    await load()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}

async function retireBlock(b: KbBlockView) {
  try {
    await adminApi.post('/kb/blocks/retire', { id: b.id, retired: !b.retired })
    okMsg.value = (b.retired ? '已恢复 ' : '已下架 ') + b.id
    await load()
  } catch (e) {
    fail(e)
  }
}

async function deleteBlock(b: KbBlockView) {
  if (!window.confirm('真删除块「' + b.id + '」？\n\n这是物理删除，不能恢复（想保留请用「下架」）。')) return
  try {
    await adminApi.post('/kb/blocks/delete', { id: b.id })
    okMsg.value = '已删除 ' + b.id
    await load()
  } catch (e) {
    fail(e)
  }
}

async function retireDoc(doc: string, retired: boolean) {
  try {
    const r = await adminApi.post<{ affected: number }>('/kb/blocks/retire-doc', { doc, retired })
    okMsg.value = (retired ? '已下架 ' : '已恢复 ') + (r?.affected ?? 0) + ' 块'
    await load()
  } catch (e) {
    fail(e)
  }
}

/** 页面栏的「刷新」调它（见 KbView 的 defineExpose 约定） */
defineExpose({ reload: load, isLoading: () => loading.value })

onMounted(load)
</script>

<template>
  <div class="doc-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-else-if="okMsg" tone="ok">{{ okMsg }}</Notice>

    <!-- 概览 -->
    <Panel title="块存储" hint="文档导入之后的新模型：一切按块 id">
      <div v-if="stats" class="stats">
        <span class="st"><b class="num">{{ stats.blocks }}</b> 块</span>
        <span class="st"><b class="num">{{ stats.documents }}</b> 份文档</span>
        <span class="st"><b class="num">{{ stats.retired }}</b> 已下架</span>
        <span class="st" :class="{ warn: stats.vectors < stats.blocks }">
          <b class="num">{{ stats.vectors }}</b> 有条向量
        </span>
        <span class="st" :class="{ warn: stats.titleVectors < stats.blocks }">
          <b class="num">{{ stats.titleVectors }}</b> 条标题向量
        </span>
        <Button size="sm" :disabled="busy !== '' || !stats.available" @click="backfillTitleVectors">
          {{ busy === 'title-vec' ? '补建中…' : '补标题向量' }}
        </Button>
        <Tag v-if="!stats.available" tone="bad">存储不可用</Tag>
      </div>
      <p class="note faint">
        块是检索的最小单位。<b>改正文会重算向量</b> —— 只改文本不重算，检索会按旧正文匹配。
        「下架」保留数据可恢复，「删除」是物理删除。
      </p>
      <p class="note faint">
        <b>标题向量</b>是"用物品名提问"那一路：正文是简介、几乎不重复标题里的名字，
        所以只有正文向量时，问「酸蚀之咬」会把「永恒酸蚀咬击」排在前面。
        它比块数少不影响使用，点「补标题向量」补齐即可 —— 只补缺的和标题改过的，不会重复花钱。
      </p>
    </Panel>

    <!-- 导入 -->
    <Panel title="导入文档" hint="先预览，再决定导不导">
      <!-- 拖拽批量导入：拖文件、拖文件夹都行 -->
      <div
        class="drop"
        :class="{ on: dragging, busy: batchBusy !== '' }"
        @dragover.prevent="dragging = true"
        @dragleave.prevent="dragging = false"
        @drop.prevent="onDrop"
      >
        <p class="drop-main">把 <b>.md</b> 文件 —— 或者<b>整个文件夹</b> —— 拖到这里</p>
        <p class="drop-sub">一次拖多少份都行。会先逐份预览「会改多少条」，确认后再一起导入。</p>
        <label class="file">
          <input type="file" multiple accept=".md,.markdown,.txt,text/plain,text/markdown" @change="pickFiles" />
          <span class="fbtn">或者点这里选文件…</span>
        </label>
      </div>

      <!-- 批量预览：一份文件一行 -->
      <div v-if="batch.length" class="batch">
        <div class="batch-head">
          <span><b class="num">{{ batchReady.length }}</b> / {{ batch.length }} 份待导入</span>
          <span v-if="batchStats.added || batchStats.updated" class="faint">
            预计新增 <b class="num">{{ batchStats.added }}</b> ·
            覆盖 <b class="num">{{ batchStats.updated }}</b> ·
            无变化 <b class="num">{{ batchStats.unchanged }}</b>
          </span>
          <Tag v-if="batchStats.warnings" tone="warn">{{ batchStats.warnings }} 条提醒</Tag>
          <span class="batch-spacer" />
          <Button size="sm" :disabled="batchBusy !== ''" @click="clearBatch">清空列表</Button>
          <Button size="sm" variant="primary"
                  :disabled="batchBusy !== '' || !batchReady.length" @click="importBatch">
            {{ batchBusy === 'import' ? '导入中…' : '导入选中的 ' + batchReady.length + ' 份' }}
          </Button>
        </div>
        <p v-if="batchProgress" class="faint batch-progress">{{ batchProgress }}</p>
        <table class="bt">
          <thead>
            <tr>
              <th></th><th>文件</th>
              <th class="num">新增</th><th class="num">覆盖</th><th class="num">无变化</th>
              <th>结果</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in batch" :key="i" :class="{ off: !r.include }">
              <td>
                <input type="checkbox" :checked="r.include"
                       :disabled="r.status === 'error' || r.status === 'done'"
                       @change="toggleRow(r)" />
              </td>
              <td class="fname">{{ r.name }}</td>
              <td class="num">{{ r.preview ? r.preview.added : '—' }}</td>
              <td class="num">{{ r.preview ? r.preview.updated : '—' }}</td>
              <td class="num">{{ r.preview ? r.preview.unchanged : '—' }}</td>
              <td>
                <span v-if="r.status === 'previewing'" class="faint">预览中…</span>
                <span v-else-if="r.status === 'importing'" class="faint">导入中…</span>
                <Tag v-else-if="r.status === 'error'" tone="bad">{{ r.error }}</Tag>
                <span v-else-if="r.status === 'done'">
                  已导入：新增 {{ r.result ? r.result.added : 0 }} ·
                  覆盖 {{ r.result ? r.result.updated : 0 }} ·
                  库里未触及 {{ r.result ? r.result.untouched : 0 }} ·
                  词条 +{{ r.result ? r.result.terms : 0 }}
                </span>
                <Tag v-else-if="r.preview && r.preview.warnings.length" tone="warn">
                  {{ r.preview.warnings[0] }}
                </Tag>
                <span v-else class="faint">就绪</span>
              </td>
            </tr>
          </tbody>
        </table>
        <p class="faint batch-note">
          每份文件的预览是**各自按当前库**算的。一起导入时若两份含同 id 的块，以后导的那份为准 ——
          「结果」列显示的是**实际发生的事**。
        </p>
      </div>

      <p class="imp-sep faint">— 或者只处理一份：粘贴到下面 —</p>

      <Input
        :model-value="docText"
        multiline
        :rows="10"
        mono
        aria-label="文档内容"
        placeholder="<!-- doc&#10;name: flame-altar&#10;url: https://...&#10;-->&#10;&#10;=== 灵火祭坛 === <!-- id: flame-altar-0 -->&#10;祭坛是复活点。"
        @update:model-value="(v: string) => { docText = v; preview = null; importResult = null }"
      />

      <div class="imp-actions">
        <Button size="sm" :disabled="!canPreview" @click="doPreview">
          {{ busy === 'preview' ? '预览中…' : '预览会改什么' }}
        </Button>
        <Button size="sm" variant="primary" :disabled="!canPreview" @click="doImport">
          {{ busy === 'import' ? '导入中…' : '确认导入' }}
        </Button>
        <Button size="sm" variant="danger" :disabled="busy !== ''" @click="clearAll">清空全部块</Button>
      </div>

      <!-- 预览结果：只算不改 -->
      <div v-if="preview" class="preview">
        <div class="pv-line">
          <Tag tone="good">新增 {{ preview.added }}</Tag>
          <Tag tone="warn">覆盖 {{ preview.updated }}</Tag>
          <Tag tone="neutral">无变化 {{ preview.unchanged }}</Tag>
          <span class="faint">库里未被触及 <b class="num">{{ preview.untouchedExisting }}</b> 块</span>
        </div>
        <div v-if="preview.addedIds.length" class="pv-ids faint">
          <span class="mono">新增：</span>{{ preview.addedIds.join('、') }}
          <template v-if="preview.added > preview.addedIds.length">…</template>
        </div>
        <div v-if="preview.updatedIds.length" class="pv-ids faint">
          <span class="mono">覆盖：</span>{{ preview.updatedIds.join('、') }}
          <template v-if="preview.updated > preview.updatedIds.length">…</template>
        </div>
        <p v-for="(w, i) in preview.warnings" :key="i" class="pv-warn">{{ w }}</p>
      </div>

      <div v-if="importResult" class="preview">
        <div class="pv-line">
          <Tag tone="good">新增 {{ importResult.added }}</Tag>
          <Tag tone="warn">覆盖 {{ importResult.updated }}</Tag>
          <Tag tone="neutral">无变化 {{ importResult.unchanged }}</Tag>
          <span class="faint">库里未被触及 <b class="num">{{ importResult.untouched }}</b> 块</span>
          <span v-if="importResult.terms" class="faint">
            新建词条 <b class="num">{{ importResult.terms }}</b> 条
          </span>
        </div>
        <p v-for="(w, i) in importResult.warnings" :key="i" class="pv-warn">{{ w }}</p>
      </div>
    </Panel>

    <!-- 文档列表 -->
    <Panel title="文档" :count="docs.length">
      <div v-if="!docs.length"><Empty text="还没有任何文档" hint="用上面的「确认导入」加第一份" /></div>
      <DataTable v-else :rows="docs.length">
        <thead>
          <tr><th>文档</th><th class="num">块数</th><th class="num">已下架</th><th class="ops">操作</th></tr>
        </thead>
        <tbody>
          <template v-for="d in docs" :key="d.docId">
            <tr class="row">
              <td><button class="docname" type="button" @click="toggleDoc(d.docId)">{{ d.docId }}</button></td>
              <td class="num">{{ d.blocks }}</td>
              <td class="num">{{ d.retired }}</td>
              <td class="ops">
                <div class="ops-btns">
                  <Button size="sm" @click="toggleDoc(d.docId)">{{ openDoc === d.docId ? '收起' : '查看块' }}</Button>
                  <Button size="sm" @click="retireDoc(d.docId, true)">整份下架</Button>
                  <Button size="sm" @click="retireDoc(d.docId, false)">恢复</Button>
                </div>
              </td>
            </tr>

            <!-- 展开：这份文档的块 -->
            <tr v-if="openDoc === d.docId" class="expand">
              <td colspan="4">
                <table class="blk">
                  <thead>
                    <tr><th>id</th><th>标题</th><th>正文</th><th class="num">状态</th><th class="ops">操作</th></tr>
                  </thead>
                  <tbody>
                    <template v-for="b in blocks" :key="b.id">
                      <tr :class="{ retired: b.retired }">
                        <td class="mono id">{{ b.id }}</td>
                        <td>{{ b.title }}</td>
                        <td class="txt">{{ b.text }}</td>
                        <td class="num">
                          <Tag :tone="b.retired ? 'neutral' : 'good'">{{ b.retired ? '已下架' : '在库' }}</Tag>
                        </td>
                        <td class="ops">
                          <div class="ops-btns">
                            <Button size="sm" @click="startEdit(b)">编辑</Button>
                            <Button size="sm" @click="retireBlock(b)">{{ b.retired ? '恢复' : '下架' }}</Button>
                            <Button size="sm" variant="danger" @click="deleteBlock(b)">删除</Button>
                          </div>
                        </td>
                      </tr>
                      <tr v-if="editing && editing.id === b.id">
                        <td colspan="5" class="edit-cell">
                          <Input
                            :model-value="editing.text"
                            multiline
                            :rows="6"
                            mono
                            aria-label="块正文"
                            @update:model-value="(v: string) => { if (editing) editing.text = v }"
                          />
                          <div class="edit-actions">
                            <span class="faint note">保存会**重算这一块的向量**（一次 embedding 调用）</span>
                            <Button size="sm" @click="editing = null">取消</Button>
                            <Button size="sm" variant="primary" :disabled="busy === 'save'" @click="saveEdit">
                              {{ busy === 'save' ? '保存中…' : '保存' }}
                            </Button>
                          </div>
                        </td>
                      </tr>
                    </template>
                  </tbody>
                </table>
                <div v-if="!blocks.length" class="faint note">这份文档没有块</div>
              </td>
            </tr>
          </template>
        </tbody>
      </DataTable>
    </Panel>
  </div>
</template>

<style scoped>
.doc-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

.stats { display: flex; align-items: center; flex-wrap: wrap; gap: var(--sp-4); margin-bottom: var(--sp-3); }
.st { font-size: var(--fs-sm); color: var(--ink-dim); }
.st b { color: var(--flame-bright); font-size: var(--fs-lg); }
.st.warn b { color: var(--amber); }

.note { font-size: var(--fs-meta); margin: 0; line-height: 1.8; }
.imp-head { display: flex; align-items: center; gap: var(--sp-3); margin-bottom: var(--sp-2); }
.file { position: relative; overflow: hidden; display: inline-block; }
.file input { position: absolute; inset: 0; opacity: 0; cursor: pointer; }
.fbtn {
  display: inline-block; padding: 5px 12px; border-radius: var(--r-sm);
  border: 1px solid var(--edge); background: var(--bg-stone); color: var(--ink-dim);
  font-size: var(--fs-xs); cursor: pointer;
}
.file:hover .fbtn { background: var(--bg-raised); border-color: var(--edge-hover); color: var(--ink); }

.imp-actions { display: flex; gap: var(--sp-2); margin-top: var(--sp-3); }

.preview { margin-top: var(--sp-3); padding-top: var(--sp-3); border-top: 1px dashed var(--line-strong); }
.pv-line { display: flex; align-items: center; flex-wrap: wrap; gap: var(--sp-2); font-size: var(--fs-sm); }
.pv-ids { font-size: var(--fs-meta); margin-top: var(--sp-2); line-height: 1.7; word-break: break-all; }
.pv-warn { font-size: var(--fs-meta); color: var(--amber); margin: var(--sp-2) 0 0; line-height: 1.7; }

.mono { font-family: var(--font-mono); }
.docname {
  background: none; border: 0; padding: 0; cursor: pointer;
  color: var(--flame); font-size: inherit; text-align: left;
}
.docname:hover { color: var(--flame-bright); text-decoration: underline; }

/* 展开行不参与"隔行变色"的视觉，用左侧一条灵火线标出层级 */
.expand > td { padding: 0 !important; background: var(--surface-inset); }
.blk { width: 100%; border-collapse: collapse; }
.blk th {
  font-size: var(--fs-meta); color: var(--ink-faint); text-align: left;
  padding: var(--sp-2) var(--sp-3); border-bottom: 1px solid var(--hairline);
}
.blk td { padding: var(--sp-2) var(--sp-3); font-size: var(--fs-sm); vertical-align: top; }
.blk tr.retired td { opacity: .55; }
.id { color: var(--flame); white-space: nowrap; }
.txt { max-width: 460px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.edit-cell { padding: var(--sp-3) !important; background: var(--bg-stone); }
.edit-actions { display: flex; align-items: center; gap: var(--sp-2); margin-top: var(--sp-2); }
.edit-actions .note { margin-right: auto; }
.ops-btns { display: flex; gap: var(--sp-1); justify-content: flex-end; }
.doc-wrap :deep(th.ops), .doc-wrap :deep(td.ops) { text-align: right; white-space: nowrap; }

/* ==================== 拖拽批量导入 ==================== */
.drop {
  display: flex; flex-direction: column; align-items: center; gap: var(--sp-2);
  padding: var(--sp-5) var(--sp-4); margin-bottom: var(--sp-3);
  border: 2px dashed var(--edge); border-radius: var(--r-md);
  background: var(--surface-inset); text-align: center;
}
.drop.on { border-color: var(--flame); background: var(--flame-veil); }
.drop.busy { opacity: .55; pointer-events: none; }
.drop-main { margin: 0; font-size: var(--fs-sm); color: var(--ink); }
.drop-sub { margin: 0; font-size: var(--fs-meta); color: var(--ink-faint); }
.imp-sep { margin: var(--sp-4) 0 var(--sp-2); font-size: var(--fs-meta); text-align: center; }

.batch { margin-bottom: var(--sp-3); }
.batch-head {
  display: flex; align-items: center; flex-wrap: wrap; gap: var(--sp-3);
  font-size: var(--fs-sm); margin-bottom: var(--sp-2);
}
.batch-spacer { flex: 1 1 auto; }
.batch-progress { margin: 0 0 var(--sp-2); font-size: var(--fs-meta); }
.bt { width: 100%; border-collapse: collapse; }
.bt th {
  font-size: var(--fs-meta); color: var(--ink-faint); text-align: left;
  padding: var(--sp-1) var(--sp-2); border-bottom: 1px solid var(--hairline);
}
.bt td {
  padding: var(--sp-1) var(--sp-2); font-size: var(--fs-meta);
  vertical-align: top; border-bottom: 1px solid var(--hairline);
}
.bt tr.off td { opacity: .5; }
.bt .num { text-align: right; white-space: nowrap; }
.fname { font-family: var(--font-mono); word-break: break-all; }
.batch-note { margin: var(--sp-2) 0 0; font-size: var(--fs-meta); line-height: 1.7; }

</style>
