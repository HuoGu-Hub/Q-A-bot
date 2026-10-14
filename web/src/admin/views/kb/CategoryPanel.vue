<script setup lang="ts">
/**
 * 「知识库 → 分类」面板。
 *
 * **分类是一份人工维护的平铺清单**：可以新增、改显示名、删除。
 * 词条的分块符号里带什么标签，它就归到什么分类；标签不在清单里（或压根没带标签）
 * 就归「未分类」。**不做任何智能分类** —— 这是重构掉整套自动归类规则之后的模型。
 *
 * wiki 清洗语料是例外：导入时来源名与页面分类会自动登记进清单，所以它们天然有分类。
 */
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { CategoryList, KbCategoryStat, CategoryEntry, CategoryEntryPage } from '@shared/api/types'
import { num } from '@shared/utils/format'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Input from '@shared/ui/Input.vue'
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

const items = ref<KbCategoryStat[]>([])
const uncategorized = ref(0)
const available = ref(true)

/** 新增分类 */
const newName = ref('')
const newLabel = ref('')

/** 显示名草稿：只有改过的 name 才有条目 */
const labelDrafts = ref<Record<string, string>>({})
const labelOf = (c: KbCategoryStat) => labelDrafts.value[c.name] ?? c.label
const isDirty = (c: KbCategoryStat) => c.name in labelDrafts.value && labelDrafts.value[c.name] !== c.label
function onLabel(c: KbCategoryStat, v: string) {
  labelDrafts.value = { ...labelDrafts.value, [c.name]: v }
}
function revert(c: KbCategoryStat) {
  const d = { ...labelDrafts.value }
  delete d[c.name]
  labelDrafts.value = d
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const r = await adminApi.get<CategoryList>('/kb/categories')
    items.value = r.items ?? []
    uncategorized.value = r.uncategorized ?? 0
    available.value = r.available !== false
    labelDrafts.value = {}
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function addCategory() {
  const name = newName.value.trim()
  if (!name) {
    error.value = '分类名不能为空'
    return
  }
  error.value = ''
  ok.value = ''
  try {
    await adminApi.post('/kb/categories/add', { name, label: newLabel.value.trim() })
    ok.value = '已新增分类：' + name
    newName.value = ''
    newLabel.value = ''
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}

async function saveLabel(c: KbCategoryStat) {
  if (!isDirty(c)) return
  error.value = ''
  ok.value = ''
  try {
    await adminApi.post('/kb/categories/update', { name: c.name, label: labelDrafts.value[c.name] ?? '' })
    ok.value = '已保存显示名：' + c.name
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}

async function removeCategory(c: KbCategoryStat) {
  if (!window.confirm('删除分类「' + c.name + '」？\n\n词条不会消失，它们会回到「未分类」。')) return
  error.value = ''
  ok.value = ''
  try {
    await adminApi.post('/kb/categories/delete', { name: c.name })
    ok.value = '已删除分类：' + c.name
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}

// ==================== 分类穿透 ====================

const entryOpen = ref(false)
const entryCategory = ref('')
const entryQ = ref('')
const entryTotal = ref(0)
const entryItems = ref<CategoryEntry[]>([])
const entryLoading = ref(false)
const entryError = ref('')
const ENTRY_LIMIT = 300

function openEntries(cat: string) {
  entryCategory.value = cat
  entryQ.value = ''
  entryOpen.value = true
  loadEntries()
}
function closeEntries() {
  entryOpen.value = false
}

async function loadEntries() {
  entryLoading.value = true
  entryError.value = ''
  try {
    const p = new URLSearchParams()
    p.set('category', entryCategory.value)
    if (entryQ.value.trim()) p.set('q', entryQ.value.trim())
    p.set('limit', String(ENTRY_LIMIT))
    const r = await adminApi.get<CategoryEntryPage>('/kb/categories/entries?' + p.toString())
    entryItems.value = r.items ?? []
    entryTotal.value = r.total ?? 0
  } catch (e) {
    entryError.value = describeError(e)
    entryItems.value = []
    entryTotal.value = 0
  } finally {
    entryLoading.value = false
  }
}

const entryTitle = computed(() => '「' + entryCategory.value + '」下的词条')

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

    <Panel title="新增分类">
      <div class="add-row">
        <Input
          v-model="newName"
          class="add-name"
          placeholder="分类名（= 分块符号里要写的标签值）"
          aria-label="新分类名"
          @keyup.enter="addCategory"
        />
        <Input
          v-model="newLabel"
          class="add-label"
          placeholder="显示名（选填，留空就用分类名）"
          aria-label="新分类显示名"
          @keyup.enter="addCategory"
        />
        <Button size="sm" variant="primary" @click="addCategory">新增分类</Button>
      </div>
      <p class="faint tip">
        分类只读分块符号里的标签：文档头写 <code>tags: 武器</code>，词条就归到「武器」；
        标签没登记过就归「未分类」。wiki 抓取的来源名与页面分类会自动登记。
      </p>
    </Panel>

    <Panel title="分类清单" :count="items.length">
      <Empty
        v-if="!available"
        text="分类存储不可用"
        hint="问答库没就绪，先检查后端日志"
      />
      <template v-else>
        <DataTable :rows="items.length" empty="还没有任何分类" empty-hint="用上面的表单新增一个">
          <thead>
            <tr>
              <th>分类名</th>
              <th>显示名</th>
              <th class="c-count">词条数</th>
              <th class="c-ops">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in items" :key="c.name">
              <td><code class="mono">{{ c.name }}</code></td>
              <td>
                <Input
                  :model-value="labelOf(c)"
                  placeholder="显示名（留空 = 同分类名）"
                  :aria-label="'「' + c.name + '」的显示名'"
                  @update:model-value="(v: string) => onLabel(c, v)"
                />
              </td>
              <td class="c-count">
                <button
                  type="button"
                  class="count-btn num"
                  :disabled="c.count === 0"
                  :aria-label="'查看「' + c.name + '」下的词条'"
                  @click="openEntries(c.name)"
                >
                  {{ num(c.count) }}
                </button>
              </td>
              <td class="c-ops">
                <template v-if="isDirty(c)">
                  <Button size="sm" variant="primary" @click="saveLabel(c)">保存</Button>
                  <Button size="sm" @click="revert(c)">撤销</Button>
                </template>
                <Button size="sm" variant="danger" @click="removeCategory(c)">删除</Button>
              </td>
            </tr>
            <tr class="uncat">
              <td><Tag tone="neutral">{{ UNCATEGORIZED }}</Tag></td>
              <td class="faint">系统兜底：标签认不出、或没有标签的词条</td>
              <td class="c-count">
                <button
                  type="button"
                  class="count-btn num"
                  :disabled="uncategorized === 0"
                  :aria-label="'查看未分类的词条'"
                  @click="openEntries(UNCATEGORIZED)"
                >
                  {{ num(uncategorized) }}
                </button>
              </td>
              <td class="c-ops"><span class="faint">—</span></td>
            </tr>
          </tbody>
        </DataTable>
      </template>
    </Panel>

    <Modal :open="entryOpen" :title="entryTitle" width="880px" @close="closeEntries">
      <div class="ent-tools">
        <Input
          v-model="entryQ"
          class="ent-kw"
          placeholder="在结果里搜索（标题 / id / 文档 / 正文）…"
          aria-label="在词条里搜索"
          @keyup.enter="loadEntries"
        />
        <Button size="sm" :disabled="entryLoading" @click="loadEntries">搜索</Button>
        <span class="faint ent-total">
          共 {{ entryTotal }} 条<template v-if="entryItems.length < entryTotal">，显示前 {{ entryItems.length }} 条</template>
        </span>
      </div>

      <Notice v-if="entryError" tone="error">{{ entryError }}</Notice>
      <div v-if="entryLoading" class="faint ent-loading">载入中…</div>
      <Empty
        v-else-if="!entryItems.length"
        text="这个分类下没有词条"
        hint="检查一下文档头的 tags 是不是写成了别的分类名"
      />
      <ul v-else class="ent-list">
        <li v-for="e in entryItems" :key="e.id" class="ent-row">
          <div class="ent-head">
            <span class="ent-title">{{ e.title || '（无标题）' }}</span>
            <code class="ent-id">{{ e.id }}</code>
          </div>
          <div class="ent-meta faint">
            <span v-if="e.docId">文档 {{ e.docId }}</span>
            <span v-if="e.alias">别名 {{ e.alias }}</span>
          </div>
          <div class="ent-snip">{{ e.snippet }}</div>
        </li>
      </ul>
    </Modal>
  </div>
</template>

<style scoped>
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

.add-row { display: flex; gap: var(--sp-2); align-items: center; flex-wrap: wrap; }
.add-name { flex: 1; min-width: 200px; }
.add-label { flex: 1; min-width: 200px; }
.tip { font-size: var(--fs-meta); margin-top: var(--sp-3); }
.tip code { font-family: var(--font-mono); font-size: var(--fs-xs); }

.panel-wrap :deep(th.c-count), .panel-wrap :deep(td.c-count) { width: 110px; text-align: right; }
.panel-wrap :deep(th.c-ops), .panel-wrap :deep(td.c-ops) { width: 190px; text-align: right; white-space: nowrap; }

.count-btn {
  padding: 0;
  background: none;
  border: none;
  font: inherit;
  color: var(--ink);
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease);
}
.count-btn:hover:not(:disabled) { color: var(--flame-bright); text-decoration: underline; }
.count-btn:focus-visible { outline: 2px solid var(--flame); outline-offset: 2px; border-radius: var(--r-sm); }
.count-btn:disabled { cursor: default; color: var(--ink-faint); }

tr.uncat td { background: var(--surface-inset); }
.faint { font-size: var(--fs-meta); }

.ent-tools { display: flex; gap: var(--sp-2); align-items: center; flex-wrap: wrap; margin-bottom: var(--sp-3); }
.ent-kw { flex: 1; min-width: 220px; }
.ent-total { font-size: var(--fs-meta); }
.ent-loading { padding: var(--sp-4); text-align: center; }

.ent-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--sp-2); }
.ent-row {
  background: var(--surface-inset);
  border: 1px solid var(--hairline);
  border-radius: var(--r-sm);
  padding: var(--sp-2) var(--sp-3);
}
.ent-head { display: flex; align-items: baseline; gap: var(--sp-2); flex-wrap: wrap; }
.ent-title { font-size: var(--fs-sm); color: var(--ink); }
.ent-id { font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--ink-faint); overflow-wrap: anywhere; }
.ent-meta { font-size: var(--fs-meta); display: flex; gap: var(--sp-2); flex-wrap: wrap; margin-top: 2px; }
.ent-snip { margin-top: var(--sp-1); font-size: var(--fs-meta); color: var(--ink-dim); line-height: 1.7; overflow-wrap: anywhere; }

@media (max-width: 900px) {
  .add-name, .add-label { flex: 1 1 100%; }
  .panel-wrap :deep(th.c-count), .panel-wrap :deep(td.c-count),
  .panel-wrap :deep(th.c-ops), .panel-wrap :deep(td.c-ops) { width: auto; }
}
</style>
