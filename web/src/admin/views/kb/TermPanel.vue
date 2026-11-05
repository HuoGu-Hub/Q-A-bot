<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type {
  KbEntryListResponse,
  KbEntryItem,
  KbEntryCounts,
  KbCategoryStat,
  KbEntryExportResponse,
  KbEntryImportResponse,
  KbBlockItemResponse,
} from '@shared/api/types'
import { num } from '@shared/utils/format'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Notice from '@shared/ui/Notice.vue'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'
import Modal from '@shared/ui/Modal.vue'
import ActionOption from '@shared/ui/ActionOption.vue'
import Empty from '@shared/ui/Empty.vue'

const UNCATEGORIZED = '未分类'

const loading = ref(false)
const error = ref('')
const ok = ref('')

const items = ref<KbEntryItem[]>([])
const counts = ref<KbEntryCounts | null>(null)
const categories = ref<KbCategoryStat[]>([])
const uncategorizedCount = ref(0)
const total = ref(0)
const available = ref(true)

const q = ref('')
const view = ref('all')
const category = ref('')

const PAGE = 30
const offset = ref(0)

const VIEWS = [
  { value: 'all', label: '全部' },
  { value: 'aliased', label: '有别名' },
  { value: 'unaliased', label: '无别名' },
  { value: 'uncategorized', label: '未分类' },
]

const categoryOptions = computed(() => [
  { value: '', label: '全部分类' },
  ...categories.value.map(c => ({ value: c.name, label: c.label + '（' + c.count + '）' })),
  { value: UNCATEGORIZED, label: UNCATEGORIZED + '（' + uncategorizedCount.value + '）' },
])

const pageLabel = computed(() => {
  if (!total.value) return '0 条'
  return (offset.value + 1) + '–' + Math.min(offset.value + PAGE, total.value) + ' / ' + total.value
})
const canPrev = computed(() => offset.value > 0)
const canNext = computed(() => offset.value + PAGE < total.value)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const p = new URLSearchParams()
    if (q.value.trim()) p.set('q', q.value.trim())
    if (view.value) p.set('view', view.value)
    if (category.value) p.set('category', category.value)
    p.set('limit', String(PAGE))
    p.set('offset', String(offset.value))
    const r = await adminApi.get<KbEntryListResponse>('/kb/terms?' + p.toString())
    items.value = r.items ?? []
    counts.value = r.counts ?? null
    categories.value = r.categories ?? []
    uncategorizedCount.value = r.uncategorizedCount ?? 0
    total.value = r.total ?? 0
    available.value = r.available !== false
    closeEditorSilently()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

function guardDirty(): boolean {
  return !editDirty.value || window.confirm('还有未保存的改动，放弃并继续？')
}

function applyFilter() {
  if (!guardDirty()) return
  offset.value = 0
  load()
}
function goPage(delta: number) {
  if (!guardDirty()) return
  offset.value = Math.max(0, offset.value + delta * PAGE)
  load()
}

const editId = ref<string | null>(null)
const editAlias = ref('')
const editBody = ref('')
const aliasLoaded = ref('')
const bodyLoaded = ref('')
const bodyReady = ref(false)
const editLoading = ref(false)
const editSaving = ref(false)
const editError = ref('')

const isOpen = (e: KbEntryItem) => editId.value === e.id
const aliasChanged = computed(() => editId.value !== null && editAlias.value !== aliasLoaded.value)
const bodyChanged = computed(() => editId.value !== null && bodyReady.value && editBody.value !== bodyLoaded.value)
const editDirty = computed(() => aliasChanged.value || bodyChanged.value)

const aliasId = (id: string) => 'alias-' + id
const bodyId = (id: string) => 'body-' + id
const sheetId = (id: string) => 'sheet-' + id

function closeEditorSilently() {
  editId.value = null
  editError.value = ''
}

async function openEditor(e: KbEntryItem) {
  if (editId.value === e.id) return
  if (editDirty.value && !window.confirm('还有未保存的改动，放弃并编辑另一条？')) return

  editId.value = e.id
  editAlias.value = e.alias
  aliasLoaded.value = e.alias
  editBody.value = ''
  bodyLoaded.value = ''
  bodyReady.value = false
  editError.value = ''
  await loadBody(e)
}

async function loadBody(e: KbEntryItem) {
  editLoading.value = true
  editError.value = ''
  bodyReady.value = false
  await nextTick()
  document.getElementById(aliasId(e.id))?.focus()
  try {
    const r = await adminApi.get<KbBlockItemResponse>('/kb/blocks/item?id=' + encodeURIComponent(e.id))
    editBody.value = r.block?.text ?? ''
    bodyLoaded.value = editBody.value
    bodyReady.value = true
  } catch (err) {
    editError.value = '正文载入失败：' + describeError(err)
  } finally {
    editLoading.value = false
  }
}

function closeEditor() {
  if (editDirty.value && !window.confirm('放弃未保存的改动？')) return
  closeEditorSilently()
}

async function toggleRetire(e: KbEntryItem) {
  const retire = !e.retired
  error.value = ''
  ok.value = ''
  try {
    await adminApi.patch('/kb/blocks/retire', { id: e.id, retired: retire })
    if (editId.value === e.id) closeEditorSilently()
    ok.value = (retire ? '已下架：' : '已恢复：') + (e.title || '词条')
    await load()
  } catch (err) {
    error.value = describeError(err)
  }
}

async function saveEditor(e: KbEntryItem) {
  if (bodyReady.value && !editBody.value.trim()) {
    editError.value = '正文不能为空；要下架这一块请去「文档」面板'
    return
  }
  const saveBody = bodyChanged.value
  editSaving.value = true
  editError.value = ''
  try {
    if (editAlias.value !== e.alias) {
      await adminApi.patch('/kb/terms/aliases', { id: e.id, alias: editAlias.value })
    }
    if (saveBody) {
      await adminApi.patch('/kb/blocks', { id: e.id, text: editBody.value })
    }
    ok.value = '已保存：' + (e.title || '词条') + (saveBody ? '（正文向量已重算）' : '')
    closeEditorSilently()
    await load()
  } catch (err) {
    editError.value = describeError(err)
  } finally {
    editSaving.value = false
  }
}

function queryString() {
  const p = new URLSearchParams()
  if (q.value.trim()) p.set('q', q.value.trim())
  if (view.value) p.set('view', view.value)
  if (category.value) p.set('category', category.value)
  return p.toString()
}

function download(name: string, blob: Blob) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
}

async function exportTable(format: 'xlsx' | 'tsv') {
  error.value = ''
  ok.value = ''
  try {
    const qs = queryString()
    const r = await adminApi.get<KbEntryExportResponse>(
      '/kb/terms/export?' + (qs ? qs + '&' : '') + 'format=' + format)
    if (format === 'xlsx') {
      const bin = atob(r.xlsxBase64 ?? '')
      const bytes = new Uint8Array(bin.length)
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
      download('词条表.xlsx', new Blob([bytes], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }))
    } else {
      download('词条表.tsv', new Blob([r.tsv ?? r.text ?? ''], { type: 'text/tab-separated-values;charset=utf-8' }))
    }
    ok.value = '已导出 ' + (r.count ?? 0) + ' 条'
  } catch (e) {
    error.value = describeError(e)
  }
}

const fileInput = ref<HTMLInputElement | null>(null)
function pickImport() {
  fileInput.value?.click()
}

function toBase64(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf)
  let s = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) {
    s += String.fromCharCode(...Array.from(bytes.subarray(i, i + chunk)))
  }
  return btoa(s)
}

async function onFile(ev: Event) {
  const input = ev.target as HTMLInputElement
  const f = input.files?.[0]
  if (!f) return
  error.value = ''
  ok.value = ''
  try {
    const r = /\.xlsx$/i.test(f.name)
      ? await adminApi.post<KbEntryImportResponse>('/kb/terms/imports', { xlsxBase64: toBase64(await f.arrayBuffer()) })
      : await adminApi.post<KbEntryImportResponse>('/kb/terms/imports', { tsv: await f.text() })
    ok.value = '导入完成：更新 ' + (r.updated ?? 0) + ' 条，跳过 ' + (r.skipped ?? 0) + ' 条'
    await load()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    input.value = ''
  }
}

async function clearAllAliases() {
  if (!window.confirm('清空全部词条的别名？此操作不可撤销。')) return
  error.value = ''
  ok.value = ''
  try {
    const r = await adminApi.del<{ cleared: number }>('/kb/terms/aliases/all?confirm=true')
    ok.value = '已清空 ' + (r.cleared ?? 0) + ' 条别名'
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}

const exportOpen = ref(false)

const ledgerText = computed(() => {
  const c = counts.value
  if (!c) return ''
  return '有别名 ' + num(c.aliased) + ' · 无别名 ' + num(c.unaliased)
    + ' · 未分类 ' + num(c.uncategorized) + ' · 已下架 ' + num(c.retired)
})

function openExport() {
  exportOpen.value = true
}
function closeExport() {
  exportOpen.value = false
}
async function chooseExport(format: 'xlsx' | 'tsv') {
  exportOpen.value = false
  await exportTable(format)
}

const createOpen = ref(false)
const createSaving = ref(false)
const createError = ref('')
const createTitle = ref('')
const createAlias = ref('')
const createCategory = ref('')
const createBody = ref('')

const createCategoryOptions = computed(() => [
  { value: '', label: '不分类' },
  ...categories.value.map(c => ({ value: c.name, label: c.label })),
])

function openCreate() {
  createTitle.value = ''
  createAlias.value = ''
  createCategory.value = ''
  createBody.value = ''
  createError.value = ''
  createOpen.value = true
}
function closeCreate() {
  createOpen.value = false
}

async function saveCreate() {
  if (!createTitle.value.trim()) {
    createError.value = '标题不能为空'
    return
  }
  if (!createBody.value.trim()) {
    createError.value = '正文不能为空'
    return
  }
  createSaving.value = true
  createError.value = ''
  try {
    const tags = createCategory.value ? [createCategory.value] : []
    await adminApi.post('/kb/blocks/manual', {
      title: createTitle.value.trim(),
      text: createBody.value,
      alias: createAlias.value.trim(),
      tags,
    })
    createOpen.value = false
    ok.value = '已新增词条：' + createTitle.value.trim()
    q.value = createTitle.value.trim()
    offset.value = 0
    await load()
  } catch (e) {
    createError.value = describeError(e)
  } finally {
    createSaving.value = false
  }
}

onMounted(load)

defineExpose({
  reload: load,
  isLoading: () => loading.value,
  openCreate,
  openExport,
  pickImport,
  clearAllAliases,
})
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-if="ok" tone="ok">{{ ok }}</Notice>

    <Panel>
      <div class="toolbar">
        <div class="tb-filters">
          <div class="f-view">
            <Select
              :model-value="view"
              :options="VIEWS"
              aria-label="视图预设"
              @update:model-value="(v: string) => { view = v; applyFilter() }"
            />
          </div>
          <div class="f-cat">
            <Select
              :model-value="category"
              :options="categoryOptions"
              aria-label="按分类筛选"
              @update:model-value="(v: string) => { category = v; applyFilter() }"
            />
          </div>
          <div class="search">
            <Input
              :model-value="q"
              class="f-kw"
              placeholder="搜标题 / 别名 …"
              aria-label="搜索词条"
              @update:model-value="(v: string) => (q = v)"
              @keyup.enter="applyFilter"
            />
            <Button size="sm" class="search-go" @click="applyFilter">搜索</Button>
          </div>
        </div>
        <div class="tb-group tb-right">
          <span class="faint page">{{ pageLabel }}</span>
          <span class="faint ledger">{{ ledgerText }}</span>
          <Button size="sm" :disabled="!canPrev || loading" @click="goPage(-1)">上一页</Button>
          <Button size="sm" :disabled="!canNext || loading" @click="goPage(1)">下一页</Button>
        </div>
        <input ref="fileInput" type="file" accept=".xlsx,.tsv,.csv,.txt" class="hidden-file" @change="onFile" >
      </div>

      <Empty
        v-if="!available"
        text="词条存储不可用"
        hint="问答库没就绪，先检查后端日志"
      />
      <DataTable
        v-else
        cards
        label="词条表"
        :rows="items.length"
        empty="没有匹配的词条"
      >
        <thead>
          <tr>
            <th>标题</th>
            <th class="c-alias">别名</th>
            <th class="c-cat">分类</th>
            <th class="c-ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="e in items" :key="e.id">
            <tr :class="{ 'row-open': isOpen(e) }">
              <td data-label="标题">
                <div class="title">{{ e.title || '（无标题）' }}</div>
                <div class="meta">
                  <span v-if="e.docId" class="doc">{{ e.docId }}</span>
                  <span class="num">{{ num(e.chars) }} 字</span>
                  <Tag v-if="e.retired" tone="warn">已下架</Tag>
                </div>
              </td>

              <td class="c-alias" data-label="别名">
                <span v-if="e.alias" class="alias">{{ e.alias }}</span>
                <span v-else class="faint">无别名</span>
              </td>

              <td class="c-cat" data-label="分类">
                <div class="cats">
                  <Tag v-if="e.uncategorized" tone="neutral">{{ UNCATEGORIZED }}</Tag>
                  <template v-else>
                    <Tag v-for="c in e.categories" :key="c" tone="flame">{{ c }}</Tag>
                  </template>
                </div>
              </td>

              <td class="c-ops" data-label="操作">
                <div class="ops">
                  <Button
                    size="sm"
                    :aria-expanded="isOpen(e)"
                    :aria-controls="sheetId(e.id)"
                    @click="isOpen(e) ? closeEditor() : openEditor(e)"
                  >
                    {{ isOpen(e) ? '收起' : '编辑' }}
                  </Button>
                  <Button size="sm" :disabled="loading" @click="toggleRetire(e)">
                    {{ e.retired ? '恢复' : '下架' }}
                  </Button>
                </div>
              </td>
            </tr>

            <tr v-if="isOpen(e)" :id="sheetId(e.id)" class="edit-row">
              <td class="sheet-cell" colspan="4">
                <div class="sheet">
                  <Notice v-if="editError" tone="error">{{ editError }}</Notice>

                  <div class="sheet-field">
                    <label class="sheet-label" :for="aliasId(e.id)">别名</label>
                    <div class="sheet-ctrl">
                      <Input
                        :id="aliasId(e.id)"
                        :model-value="editAlias"
                        placeholder="多个用「、」分隔"
                        @update:model-value="(v: string) => (editAlias = v)"
                      />
                    </div>
                  </div>

                  <div class="sheet-field">
                    <label class="sheet-label" :for="bodyId(e.id)">正文</label>
                    <div class="sheet-ctrl">
                      <div v-if="editLoading" class="faint loading">载入正文…</div>
                      <div v-else-if="!bodyReady" class="load-failed">
                        <span class="faint">正文没能载入，现在只能改别名。</span>
                        <Button size="sm" @click="loadBody(e)">重新载入正文</Button>
                      </div>
                      <Input
                        v-else
                        :id="bodyId(e.id)"
                        :model-value="editBody"
                        multiline
                        :rows="12"
                        mono
                        placeholder="这一块的正文"
                        @update:model-value="(v: string) => (editBody = v)"
                      />
                    </div>
                  </div>

                  <div class="sheet-actions">
                    <span v-if="editDirty" class="dirty" role="status">未保存</span>
                    <Button size="sm" :disabled="editSaving" @click="closeEditor">取消</Button>
                    <Button
                      size="sm"
                      variant="primary"
                      :disabled="editSaving || editLoading"
                      @click="saveEditor(e)"
                    >
                      {{ editSaving ? '保存中…' : '保存' }}
                    </Button>
                  </div>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </DataTable>
    </Panel>

    <Modal :open="exportOpen" title="导出词条表" width="480px" @close="closeExport">
      <p class="export-lede">
        按当前筛选导出 <b class="num">{{ num(total) }}</b> 条词条。选一种格式：
      </p>
      <div class="export-opts">
        <ActionOption
          title="Excel 表格（.xlsx）"
          @select="chooseExport('xlsx')"
        />
        <ActionOption
          title="纯文本（.tsv）"
          @select="chooseExport('tsv')"
        />
      </div>
    </Modal>

    <Modal :open="createOpen" title="新增词条" width="640px" @close="closeCreate">
      <Notice v-if="createError" tone="error">{{ createError }}</Notice>

      <div class="sheet-field">
        <label class="sheet-label" for="new-title">标题</label>
        <div class="sheet-ctrl">
          <Input id="new-title" v-model="createTitle" placeholder="例如：苗圃补给" />
        </div>
      </div>

      <div class="sheet-field">
        <label class="sheet-label" for="new-alias">别名</label>
        <div class="sheet-ctrl">
          <Input id="new-alias" v-model="createAlias" placeholder="多个用「、」分隔（可留空）" />
        </div>
      </div>

      <div class="sheet-field">
        <label class="sheet-label" for="new-cat">分类</label>
        <div class="sheet-ctrl">
          <Select
            id="new-cat"
            :model-value="createCategory"
            :options="createCategoryOptions"
            aria-label="新词条的分类"
            @update:model-value="(v: string) => (createCategory = v)"
          />
        </div>
      </div>

      <div class="sheet-field">
        <label class="sheet-label" for="new-body">正文</label>
        <div class="sheet-ctrl">
          <Input id="new-body" v-model="createBody" multiline :rows="10" mono placeholder="这一条词条的正文" />
        </div>
      </div>

      <div class="sheet-actions">
        <Button size="sm" :disabled="createSaving" @click="closeCreate">取消</Button>
        <Button size="sm" variant="primary" :disabled="createSaving" @click="saveCreate">
          {{ createSaving ? '新增中…' : '新增' }}
        </Button>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

.toolbar {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  flex-wrap: wrap;
  margin-bottom: var(--sp-3);
}
.tb-filters { display: flex; align-items: center; gap: var(--sp-2); flex: 1; min-width: 0; flex-wrap: wrap; }
.f-view { flex: none; width: 140px; }
.f-cat { flex: none; width: 176px; }
.search { position: relative; flex: 0 1 300px; min-width: 190px; }
.f-kw { width: 100%; }
.search :deep(.inp) { padding-right: 66px; }
.search-go {
  position: absolute;
  right: 4px;
  top: 50%;
  margin-top: -15px;
}
.tb-group { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.tb-right { margin-left: auto; }
.page, .ledger { font-size: var(--fs-meta); }
@media (max-width: 760px) {
  .search-go { min-height: 30px; padding: 5px 10px; font-size: var(--fs-sm); margin-top: -15px; }
}
.hidden-file { display: none; }

.export-lede { margin: 0 0 var(--sp-3); font-size: var(--fs-body); color: var(--ink-2); }
.export-lede b { color: var(--ink); }
.export-opts { display: flex; flex-direction: column; gap: var(--sp-2); }

.title { color: var(--ink); }
.meta {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
  flex-wrap: wrap;
  margin-top: 3px;
  font-size: var(--fs-meta);
  color: var(--ink-3);
}
.doc { overflow-wrap: anywhere; }
.alias { color: var(--ink-2); overflow-wrap: anywhere; }
.cats { display: flex; gap: 4px; flex-wrap: wrap; }

.panel-wrap :deep(th.c-alias), .panel-wrap :deep(td.c-alias) { width: 280px; }
.panel-wrap :deep(th.c-cat), .panel-wrap :deep(td.c-cat) { width: 190px; }
.panel-wrap :deep(th.c-ops), .panel-wrap :deep(td.c-ops) { width: 168px; text-align: right; white-space: nowrap; }
.ops { display: flex; gap: var(--sp-2); justify-content: flex-end; }

.panel-wrap :deep(tr.row-open td) { background: var(--surface-hover); }
.panel-wrap :deep(tr.row-open td:first-child) { box-shadow: inset 2px 0 0 var(--ember); }

.panel-wrap :deep(td.sheet-cell) {
  padding: 0;
  background: var(--surface-inset);
}
.panel-wrap :deep(tbody tr.edit-row:hover) { background: transparent; }

.sheet {
  padding: var(--sp-4) var(--sp-4) var(--sp-3);
  animation: sheet-in var(--dur-fast) var(--ease);
}
@keyframes sheet-in {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}

.sheet-field {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr);
  gap: var(--sp-3);
  align-items: start;
  padding: var(--sp-3) 0;
}
.sheet-field + .sheet-field { border-top: 1px solid var(--hairline); }
.sheet-field:first-of-type { padding-top: 0; }
.sheet-label {
  padding-top: 9px;
  font-size: var(--fs-meta);
  color: var(--ink-3);
  letter-spacing: var(--tracking-label);
}
.sheet-ctrl { min-width: 0; }

.loading { padding: 9px 0; font-size: var(--fs-base); }
.load-failed { display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap; }

.sheet-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--sp-2);
  padding-top: var(--sp-3);
  border-top: 1px solid var(--hairline);
}
.dirty {
  margin-right: auto;
  font-size: var(--fs-meta);
  color: var(--ember-bright);
}
.dirty::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 6px;
  border-radius: 50%;
  background: var(--ember);
  vertical-align: 1px;
}

@media (prefers-reduced-motion: reduce) {
  .sheet { animation: none; }
}

@media (max-width: 900px) {
  .f-view, .f-cat, .search { flex: 1 1 100%; width: auto; }
  .tb-right { margin-left: 0; }
  .panel-wrap :deep(th.c-alias), .panel-wrap :deep(td.c-alias),
  .panel-wrap :deep(th.c-cat), .panel-wrap :deep(td.c-cat),
  .panel-wrap :deep(th.c-ops), .panel-wrap :deep(td.c-ops) { width: auto; }
  .sheet-field { grid-template-columns: minmax(0, 1fr); gap: var(--sp-2); }
  .sheet-label { padding-top: 0; }
}
</style>
