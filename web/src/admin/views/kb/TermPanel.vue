<script setup lang="ts">
/**
 * 「知识库 → 词条」面板。
 *
 * **一个词条 = 一个分块。** 跟从 wiki 清洗下来的语料同一个形状：有 id、有标题，
 * 只是多一个别名。所以这里就是分块的目录 —— 一份文档有多少块，就多出多少条词条。
 *
 * 这里只写**别名**（零成本、不重算向量）；正文改在「文档」面板。
 * 核对状态与核对模式已经全部删掉。
 *
 * 分类只读分块符号里的标签：标签在分类清单里就归那个分类，认不出（或没标签）
 * 就是「未分类」。不做任何智能分类 —— 要新增/改名分类请去「分类」面板。
 */
import { computed, onMounted, ref } from 'vue'
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

/** 别名草稿：只有改过的 id 才有条目，避免整表复制 */
const aliasDrafts = ref<Record<string, string>>({})
const aliasOf = (e: KbEntryItem) => aliasDrafts.value[e.id] ?? e.alias
const isDirty = (e: KbEntryItem) => e.id in aliasDrafts.value && aliasDrafts.value[e.id] !== e.alias
function onAlias(e: KbEntryItem, v: string) {
  aliasDrafts.value = { ...aliasDrafts.value, [e.id]: v }
}
function revert(e: KbEntryItem) {
  const d = { ...aliasDrafts.value }
  delete d[e.id]
  aliasDrafts.value = d
}
const dirtyCount = computed(() => Object.keys(aliasDrafts.value).length)

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
  const from = offset.value + 1
  const to = Math.min(offset.value + PAGE, total.value)
  return from + '–' + to + ' / ' + total.value
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
    // 换了一批就把没提交的草稿丢掉：留着会让人以为改动还在
    aliasDrafts.value = {}
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

function applyFilter() {
  offset.value = 0
  load()
}
function goPage(delta: number) {
  offset.value = Math.max(0, offset.value + delta * PAGE)
  load()
}

// ==================== 别名 ====================

async function saveOne(e: KbEntryItem) {
  if (!isDirty(e)) return
  error.value = ''
  ok.value = ''
  try {
    await adminApi.post('/kb/terms', { id: e.id, alias: aliasDrafts.value[e.id] ?? '' })
    ok.value = '已保存别名：' + (e.title || e.id)
    await load()
  } catch (err) {
    error.value = describeError(err)
  }
}

async function saveAll() {
  const ids = Object.keys(aliasDrafts.value).filter(id => {
    const e = items.value.find(x => x.id === id)
    return e ? isDirty(e) : false
  })
  if (!ids.length) return
  error.value = ''
  ok.value = ''
  try {
    for (const id of ids) {
      await adminApi.post('/kb/terms', { id, alias: aliasDrafts.value[id] })
    }
    ok.value = '已保存 ' + ids.length + ' 条别名'
    await load()
  } catch (err) {
    error.value = describeError(err)
  }
}

async function clearAlias(e: KbEntryItem) {
  error.value = ''
  ok.value = ''
  try {
    await adminApi.post('/kb/terms/clear', { id: e.id })
    ok.value = '已清空别名：' + (e.title || e.id)
    await load()
  } catch (err) {
    error.value = describeError(err)
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

// ==================== 正文（块）====================

/**
 * 词条就是分块，所以改正文 = 改这一块的正文。
 *
 * ⚠️ 保存会**重算这一块的向量** —— 只改文本不重算，检索会按旧正文匹配，改了等于没改。
 * 列表刻意不带正文（三千多条全文太大），所以编辑前单独取一次。
 */
const bodyOpen = ref(false)
const bodyLoading = ref(false)
const bodySaving = ref(false)
const bodyError = ref('')
const bodyId = ref('')
const bodyTitle = ref('')
const bodyText = ref('')

async function openBody(e: KbEntryItem) {
  bodyId.value = e.id
  bodyTitle.value = e.title || e.id
  bodyText.value = ''
  bodyError.value = ''
  bodyOpen.value = true
  bodyLoading.value = true
  try {
    const r = await adminApi.get<KbBlockItemResponse>('/kb/blocks/item?id=' + encodeURIComponent(e.id))
    bodyText.value = r.block?.text ?? ''
  } catch (err) {
    bodyError.value = describeError(err)
  } finally {
    bodyLoading.value = false
  }
}

function closeBody() {
  bodyOpen.value = false
}

async function saveBody() {
  if (!bodyText.value.trim()) {
    bodyError.value = '正文不能为空；要下架这一块请用「文档」面板'
    return
  }
  bodySaving.value = true
  bodyError.value = ''
  try {
    await adminApi.post('/kb/blocks/update', { id: bodyId.value, text: bodyText.value })
    ok.value = '已保存正文：' + bodyTitle.value + '（向量已重算）'
    bodyOpen.value = false
    await load()
  } catch (err) {
    bodyError.value = describeError(err)
  } finally {
    bodySaving.value = false
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
          placeholder="搜 id / 标题 / 别名 …"
          aria-label="搜索词条"
          @update:model-value="(v: string) => (q = v)"
          @keyup.enter="applyFilter"
        />
        <Button size="sm" @click="applyFilter">搜索</Button>
      </div>
      <div class="stats faint">
        <span>共 <b class="num">{{ num(counts?.all ?? 0) }}</b> 条词条</span>
        <span>有别名 <b class="num">{{ num(counts?.aliased ?? 0) }}</b></span>
        <span>无别名 <b class="num">{{ num(counts?.unaliased ?? 0) }}</b></span>
        <span>未分类 <b class="num">{{ num(counts?.uncategorized ?? 0) }}</b></span>
      </div>
    </Panel>

    <Panel title="词条" :count="total">
      <div class="tb-actions">
        <Button size="sm" :disabled="loading" @click="exportTable('xlsx')">导出 xlsx</Button>
        <Button size="sm" :disabled="loading" @click="exportTable('tsv')">导出 tsv</Button>
        <Button size="sm" :disabled="loading" @click="pickImport">导入别名表</Button>
        <Button v-if="dirtyCount" size="sm" variant="primary" @click="saveAll">
          保存改动（{{ dirtyCount }}）
        </Button>
        <span class="spacer" />
        <span class="faint">{{ pageLabel }}</span>
        <Button size="sm" :disabled="!canPrev || loading" @click="goPage(-1)">上一页</Button>
        <Button size="sm" :disabled="!canNext || loading" @click="goPage(1)">下一页</Button>
        <Button size="sm" variant="danger" :disabled="loading" @click="clearAllAliases">清空全部别名</Button>
        <input ref="fileInput" type="file" accept=".xlsx,.tsv,.csv,.txt" class="hidden-file" @change="onFile" >
      </div>

      <Empty
        v-if="!available"
        text="词条存储不可用"
        hint="问答库没就绪，先检查后端日志"
      />
      <DataTable
        v-else
        :rows="items.length"
        empty="没有匹配的词条"
        empty-hint="换个筛选条件，或者先去「文档」面板导入资料"
      >
        <thead>
          <tr>
            <th class="c-id">id</th>
            <th>标题</th>
            <th class="c-alias">别名</th>
            <th class="c-cat">分类</th>
            <th class="c-ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in items" :key="e.id">
            <td class="c-id"><code class="mono">{{ e.id }}</code></td>
            <td>
              <div class="title">{{ e.title || '（无标题）' }}</div>
              <div class="faint meta">
                <span v-if="e.docId">文档 {{ e.docId }}</span>
                <span>{{ num(e.chars) }} 字</span>
                <Tag v-if="e.retired" tone="warn">已下架</Tag>
              </div>
            </td>
            <td class="c-alias">
              <Input
                :model-value="aliasOf(e)"
                placeholder="别名（可多个，用「、」分隔）"
                :aria-label="'「' + (e.title || e.id) + '」的别名'"
                @update:model-value="(v: string) => onAlias(e, v)"
              />
            </td>
            <td class="c-cat">
              <Tag v-if="e.uncategorized" tone="neutral">{{ UNCATEGORIZED }}</Tag>
              <template v-else>
                <Tag v-for="c in e.categories" :key="c" tone="flame">{{ c }}</Tag>
              </template>
            </td>
            <td class="c-ops">
              <Button size="sm" @click="openBody(e)">正文</Button>
              <template v-if="isDirty(e)">
                <Button size="sm" variant="primary" @click="saveOne(e)">保存别名</Button>
                <Button size="sm" @click="revert(e)">撤销</Button>
              </template>
              <Button v-else-if="e.alias" size="sm" @click="clearAlias(e)">清空别名</Button>
            </td>
          </tr>
        </tbody>
      </DataTable>

      <p class="faint tip">
        词条就是分块：正文点「正文」直接改（保存会重算这一块的向量）；分类只读分块符号里的标签，
        认不出就是「未分类」。
      </p>
    </Panel>

    <Modal :open="bodyOpen" :title="'正文：' + bodyTitle" width="760px" @close="closeBody">
      <div v-if="bodyLoading" class="faint body-loading">载入中…</div>
      <template v-else>
        <Notice v-if="bodyError" tone="error">{{ bodyError }}</Notice>
        <Input
          v-model="bodyText"
          multiline
          :rows="16"
          mono
          aria-label="块正文"
          placeholder="这一块的正文"
        />
        <p class="faint body-hint">
          保存后会重算这一块的向量 —— 只改文本不重算，检索仍会按旧正文匹配。
        </p>
        <div class="body-actions">
          <Button size="sm" :disabled="bodySaving" @click="closeBody">取消</Button>
          <Button size="sm" variant="primary" :disabled="bodySaving" @click="saveBody">
            {{ bodySaving ? '保存中…' : '保存正文' }}
          </Button>
        </div>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

.filters { display: flex; gap: var(--sp-2); align-items: center; flex-wrap: wrap; }
.f-view { flex: none; width: 150px; }
.f-cat { flex: none; width: 200px; }
.f-kw { flex: 1; min-width: 200px; }
.stats { display: flex; gap: var(--sp-4); margin-top: var(--sp-3); font-size: var(--fs-meta); flex-wrap: wrap; }

.tb-actions { display: flex; gap: var(--sp-2); align-items: center; flex-wrap: wrap; margin-bottom: var(--sp-3); }
.tb-actions .spacer { flex: 1; }
.hidden-file { display: none; }

.title { color: var(--ink); }
.meta { font-size: var(--fs-meta); display: flex; gap: var(--sp-2); align-items: center; margin-top: 2px; }

.panel-wrap :deep(th.c-id), .panel-wrap :deep(td.c-id) { width: 220px; }
.panel-wrap :deep(th.c-alias), .panel-wrap :deep(td.c-alias) { width: 260px; }
.panel-wrap :deep(th.c-cat), .panel-wrap :deep(td.c-cat) { width: 180px; }
.panel-wrap :deep(th.c-ops), .panel-wrap :deep(td.c-ops) { width: 230px; text-align: right; white-space: nowrap; }

.body-loading { padding: var(--sp-4); text-align: center; }
.body-hint { font-size: var(--fs-meta); margin-top: var(--sp-2); }
.body-actions { display: flex; justify-content: flex-end; gap: var(--sp-2); margin-top: var(--sp-3); }
.c-id code { font-size: var(--fs-xs); color: var(--ink-dim); overflow-wrap: anywhere; }
.c-cat { display: flex; gap: 4px; flex-wrap: wrap; }
.tip { font-size: var(--fs-meta); margin-top: var(--sp-3); }

@media (max-width: 900px) {
  .f-view, .f-cat, .f-kw { flex: 1 1 100%; width: auto; }
}
</style>
