<script setup lang="ts">
/**
 * 「知识库 → 词条」面板。
 *
 * **一个词条 = 一个分块。** 跟从 wiki 清洗下来的语料同一个形状：有标题，只是多一个别名。
 *
 * 列表只读：别名是文本，不就地改。改内容要点「编辑」——那一行会向下展开一张
 * 工作台，别名与正文同时变成可编辑，一次保存。
 *
 * 分类只读分块符号里的标签：标签在分类清单里就归那个分类，认不出（或没标签）
 * 就是「未分类」。不做任何智能分类 —— 要新增/改名分类请去「分类」面板。
 */
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
import Empty from '@shared/ui/Empty.vue'

/** 与后端 CategoryService.UNCATEGORIZED 一致 */
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

/** 筛选 */
const q = ref('')
const view = ref('all')
const category = ref('')

const PAGE = 100
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

/** 改筛选/翻页会把展开的编辑收起，先问一句，别让未保存的改动悄悄没了 */
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

// ==================== 行内编辑 ====================

/**
 * 同一时刻只展开一行。列表里那条词的别名与正文一起进编辑态，
 * 保存时才分别落到两个接口：别名零成本，正文会重算这一块的向量。
 */
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

/** 取这一块的正文。失败只影响正文那一格，别名照常能改 */
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
      await adminApi.post('/kb/terms', { id: e.id, alias: editAlias.value })
    }
    if (saveBody) {
      await adminApi.post('/kb/blocks/update', { id: e.id, text: editBody.value })
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

// ==================== 导出 / 导入（Excel 旁路）====================

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
      ? await adminApi.post<KbEntryImportResponse>('/kb/terms/import', { xlsxBase64: toBase64(await f.arrayBuffer()) })
      : await adminApi.post<KbEntryImportResponse>('/kb/terms/import', { tsv: await f.text() })
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
    const r = await adminApi.post<{ cleared: number }>('/kb/terms/clear-all', { confirm: true })
    ok.value = '已清空 ' + (r.cleared ?? 0) + ' 条别名'
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}

onMounted(load)

defineExpose({
  reload: load,
  isLoading: () => loading.value,
})
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-if="ok" tone="ok">{{ ok }}</Notice>

    <!-- 筛选 + 家底读数：一条筛选栏，下面用发丝线接一行读数 -->
    <Panel title="筛选">
      <div class="filters">
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
        <Input
          :model-value="q"
          class="f-kw"
          placeholder="搜标题 / 别名 …"
          aria-label="搜索词条"
          @update:model-value="(v: string) => (q = v)"
          @keyup.enter="applyFilter"
        />
        <Button size="sm" @click="applyFilter">搜索</Button>
      </div>

      <dl class="ledger">
        <div class="led-lede">
          <dt>全部</dt>
          <dd class="num">{{ num(counts?.all ?? 0) }}</dd>
        </div>
        <div>
          <dt>有别名</dt>
          <dd class="num">{{ num(counts?.aliased ?? 0) }}</dd>
        </div>
        <div>
          <dt>无别名</dt>
          <dd class="num">{{ num(counts?.unaliased ?? 0) }}</dd>
        </div>
        <div>
          <dt>未分类</dt>
          <dd class="num">{{ num(counts?.uncategorized ?? 0) }}</dd>
        </div>
        <div>
          <dt>已下架</dt>
          <dd class="num">{{ num(counts?.retired ?? 0) }}</dd>
        </div>
      </dl>
    </Panel>

    <Panel title="词条" :count="total">
      <div class="toolbar">
        <div class="tb-group">
          <Button size="sm" :disabled="loading" @click="exportTable('xlsx')">导出 xlsx</Button>
          <Button size="sm" :disabled="loading" @click="exportTable('tsv')">导出 tsv</Button>
          <Button size="sm" :disabled="loading" @click="pickImport">导入别名表</Button>
        </div>
        <div class="tb-group tb-right">
          <span class="faint page">{{ pageLabel }}</span>
          <Button size="sm" :disabled="!canPrev || loading" @click="goPage(-1)">上一页</Button>
          <Button size="sm" :disabled="!canNext || loading" @click="goPage(1)">下一页</Button>
          <Button size="sm" variant="danger" :disabled="loading" @click="clearAllAliases">清空全部别名</Button>
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
        label="词条表"
        :rows="items.length"
        empty="没有匹配的词条"
        empty-hint="换个筛选条件，或者先去「文档」面板导入资料"
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
              <td>
                <div class="title">{{ e.title || '（无标题）' }}</div>
                <div class="meta">
                  <span v-if="e.docId" class="doc">{{ e.docId }}</span>
                  <span class="num">{{ num(e.chars) }} 字</span>
                  <Tag v-if="e.retired" tone="warn">已下架</Tag>
                </div>
              </td>

              <td class="c-alias">
                <span v-if="e.alias" class="alias">{{ e.alias }}</span>
                <span v-else class="faint">无别名</span>
              </td>

              <!-- ⚠️ 标签必须包在 div 里，不能给 <th>/<td> 加 display:flex ——
                   那会让单元格不再是 table-cell，表头那根线会断、列也跟标题对不上。 -->
              <td class="c-cat">
                <div class="cats">
                  <Tag v-if="e.uncategorized" tone="neutral">{{ UNCATEGORIZED }}</Tag>
                  <template v-else>
                    <Tag v-for="c in e.categories" :key="c" tone="flame">{{ c }}</Tag>
                  </template>
                </div>
              </td>

              <td class="c-ops">
                <Button
                  size="sm"
                  :aria-expanded="isOpen(e)"
                  :aria-controls="sheetId(e.id)"
                  @click="isOpen(e) ? closeEditor() : openEditor(e)"
                >
                  {{ isOpen(e) ? '收起' : '编辑' }}
                </Button>
              </td>
            </tr>

            <!-- 展开的工作台：别名与正文一起进编辑态 -->
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
                        placeholder="多个用「、」分隔，例如：苗圃补给、苗圃物资"
                        @update:model-value="(v: string) => (editAlias = v)"
                      />
                      <p class="sheet-hint">
                        关键词检索靠这些名字命中；留空则用标题里「（英文）」那一半。
                      </p>
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
                      <p class="sheet-hint">
                        保存正文会重算这一块的向量（只改文本不重算，检索仍按旧正文匹配）。
                        <span class="count num">{{ num(editBody.length) }}</span> 字
                      </p>
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
  </div>
</template>

<style scoped>
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

/* ==================== 筛选 ==================== */
.filters { display: flex; gap: var(--sp-2); align-items: center; flex-wrap: wrap; }
.f-view { flex: none; width: 150px; }
.f-cat { flex: none; width: 220px; }
.f-kw { flex: 1; min-width: 200px; }

/* 家底读数：一条横向账目，数字与标签成对，不铺成四张卡 */
.ledger {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-5);
  margin: var(--sp-4) 0 0;
  padding-top: var(--sp-3);
  border-top: 1px solid var(--hairline);
}
.ledger > div { display: flex; align-items: baseline; gap: var(--sp-2); }
.ledger dt { font-size: var(--fs-meta); color: var(--ink-3); }
.ledger dd { margin: 0; font-size: var(--fs-body); color: var(--ink-2); }
.led-lede dt { color: var(--ink-2); }
.led-lede dd { font-size: var(--fs-lg); color: var(--ink); }

/* ==================== 工具栏 ==================== */
.toolbar {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  flex-wrap: wrap;
  margin-bottom: var(--sp-3);
}
.tb-group { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.tb-right { margin-left: auto; }
.page { font-size: var(--fs-meta); }
.hidden-file { display: none; }

/* ==================== 表格 ==================== */
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
/* 分类是 flex 的容器放在单元格**里面**，绝不落到 th/td 上 —— 见模板注释 */
.cats { display: flex; gap: 4px; flex-wrap: wrap; }

.panel-wrap :deep(th.c-alias), .panel-wrap :deep(td.c-alias) { width: 280px; }
.panel-wrap :deep(th.c-cat), .panel-wrap :deep(td.c-cat) { width: 190px; }
.panel-wrap :deep(th.c-ops), .panel-wrap :deep(td.c-ops) { width: 90px; text-align: right; white-space: nowrap; }

/* 展开的那一行：给主行一条火焰左缘，让"正在改的是哪一条"一眼可见 */
.panel-wrap :deep(tr.row-open td) { background: var(--surface-hover); }
.panel-wrap :deep(tr.row-open td:first-child) { box-shadow: inset 2px 0 0 var(--ember); }

/* ==================== 展开的工作台 ==================== */
/* 深底 + 内衬刻痕 = "刻进去的操作台"，与全站"物件槽"语言一致 */
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
.sheet-hint { margin: var(--sp-2) 0 0; font-size: var(--fs-xs); color: var(--ink-3); line-height: 1.7; }
.sheet-hint .count { color: var(--ink-2); }
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
  .f-view, .f-cat, .f-kw { flex: 1 1 100%; width: auto; }
  .tb-right { margin-left: 0; }
  .panel-wrap :deep(th.c-alias), .panel-wrap :deep(td.c-alias),
  .panel-wrap :deep(th.c-cat), .panel-wrap :deep(td.c-cat),
  .panel-wrap :deep(th.c-ops), .panel-wrap :deep(td.c-ops) { width: auto; }
  .sheet-field { grid-template-columns: minmax(0, 1fr); gap: var(--sp-2); }
  .sheet-label { padding-top: 0; }
}
</style>
