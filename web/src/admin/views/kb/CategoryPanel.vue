<script setup lang="ts">
/**
 * 「知识库 → 分类」Tab 面板。
 *
 * 为什么是面板而不是页面：管理端要把「术语表」「知识库」「分类」并进同一个
 * 「知识库」页，页内用二级目录切换。页面级外壳（AdminPage / Tabs / 页面标题 /
 * 页面宽度）全部交给父页面 —— 所以这里不接收 props、不 emit 事件、不渲染
 * AdminPage，也不写 max-width：面板自己定宽会和父页面容器打架。
 *
 * 接口调用（/kb/categories 的 list / set / auto / reset）、分组与筛选、
 * 编辑与批量逻辑全部与重构前逐个字段一致，本次是纯结构与外观迁移。
 */
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type {
  CategoryList,
  CategoryItem,
  CategoryGroup,
  CategoryStat,
  CategoryEntry,
  CategoryEntryPage,
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

const loading = ref(false)
const error = ref('')
const ok = ref('')

const items = ref<CategoryItem[]>([])
const groups = ref<CategoryGroup[]>([])
const stats = ref<CategoryStat[]>([])
const customCount = ref(0)

/** 本地筛选 */
const filter = ref('')
const filterGroup = ref('')

/** 待保存的改动：raw → { groupKey, labelZh } */
const edits = ref<Record<string, { groupKey?: string; labelZh?: string }>>({})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const r = await adminApi.get<CategoryList>('/kb/categories')
    items.value = r.items ?? []
    groups.value = r.groups ?? []
    stats.value = r.stats ?? []
    customCount.value = r.customCount ?? 0
    edits.value = {}
  } catch (e) { error.value = describeError(e) } finally {
    loading.value = false
  }
}

/** 当前显示的归属（优先用未保存的编辑） */
function currentGroup(it: CategoryItem): string {
  return edits.value[it.raw]?.groupKey ?? it.groupKey
}
function currentLabel(it: CategoryItem): string {
  const e = edits.value[it.raw]
  if (e && e.labelZh !== undefined) return e.labelZh
  return it.custom ? it.labelZh : ''
}
function isEdited(raw: string): boolean {
  return raw in edits.value
}

function setGroup(it: CategoryItem, groupKey: string) {
  const cur = edits.value[it.raw] ?? {}
  edits.value = { ...edits.value, [it.raw]: { ...cur, groupKey } }
}
function setLabel(it: CategoryItem, labelZh: string) {
  const cur = edits.value[it.raw] ?? {}
  edits.value = { ...edits.value, [it.raw]: { ...cur, labelZh } }
}

/** 保存所有改动 */
async function saveAll() {
  const keys = Object.keys(edits.value)
  if (!keys.length) { ok.value = '没有改动'; return }
  error.value = ''
  let n = 0
  try {
    for (const raw of keys) {
      const it = items.value.find(x => x.raw === raw)
      if (!it) continue
      const g = currentGroup(it)
      const l = currentLabel(it)
      await adminApi.post('/kb/categories/set', { raw, groupKey: g, labelZh: l })
      n++
    }
    ok.value = '已保存 ' + n + ' 条'
    await load()
  } catch (e) { error.value = describeError(e) }
}

/** 恢复某条为自动规则 */
async function resetOne(it: CategoryItem) {
  try {
    await adminApi.post('/kb/categories/set', { raw: it.raw, groupKey: '' })
    ok.value = '已恢复自动规则：' + it.raw
    await load()
  } catch (e) { error.value = describeError(e) }
}

/** 按自动规则批量归类 */
async function autoApply() {
  if (!window.confirm('按自动规则归类所有【未被手动设置】的分类？')) return
  try {
    const r = await adminApi.post<{ message: string }>('/kb/categories/auto', {})
    ok.value = r.message
    await load()
  } catch (e) { error.value = describeError(e) }
}

/** 全部恢复自动规则 */
async function resetAll() {
  if (!window.confirm('清空所有手动设置，全部回到自动规则？此操作不可撤销。')) return
  try {
    const r = await adminApi.post<{ message: string }>('/kb/categories/reset', {})
    ok.value = r.message
    await load()
  } catch (e) { error.value = describeError(e) }
}

/** 点统计卡 = 按该大类筛选；再点一次取消。交互与重构前完全一致 */
function toggleFilter(key: string) {
  filterGroup.value = filterGroup.value === key ? '' : key
}

// ==================== 分类穿透 ====================

/**
 * 「这个分类下面具体有哪些条目」。
 *
 * 两个入口共用这一份状态，靠打开时传的 scope 区分：
 *   分类列表里的「N 条目」  → 按【原始分类】精确匹配
 *   大类卡片上的「查看条目」 → 按【大类】匹配（含它下面所有原始分类）
 *
 * 为什么不做翻页：分类面板里绝大多数分类都在一屏之内，真正大的分类应该
 * 用搜索收窄，而不是让用户翻几十页。所以只取前 ENTRY_LIMIT 条，并把
 * 「共 N 条 / 显示前 M 条」明确写出来，不假装是全量。
 */
const entryOpen = ref(false)
const entryRaw = ref('')
const entryGroup = ref('')
const entryQ = ref('')
const entryTotal = ref(0)
const entryItems = ref<CategoryEntry[]>([])
const entryLoading = ref(false)
const entryError = ref('')
const ENTRY_LIMIT = 300

async function openEntries(scope: { raw?: string; group?: string }) {
  entryRaw.value = scope.raw ?? ''
  entryGroup.value = scope.group ?? ''
  entryQ.value = ''
  entryOpen.value = true
  await loadEntries()
}

function closeEntries() {
  entryOpen.value = false
}

async function loadEntries() {
  entryLoading.value = true
  entryError.value = ''
  try {
    const p = new URLSearchParams()
    if (entryRaw.value) p.set('raw', entryRaw.value)
    else if (entryGroup.value) p.set('group', entryGroup.value)
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

/** 弹窗标题：按原始分类打开就写分类名，按大类打开就写大类名 */
const entryTitle = computed(() => {
  if (entryRaw.value) return '「' + entryRaw.value + '」下的条目'
  const g = groups.value.find(x => x.key === entryGroup.value)
  return '「' + (g ? g.label : entryGroup.value) + '」下的条目'
})

/**
 * 下拉选项改成 Select 组件要的数组结构。
 * label 仍是「图标 + 名称」，和原来的 <option> 文本逐字一致；
 * 「隐藏」是后端约定的特殊值 __hidden__，不属于 groups，所以单独追加。
 */
const groupOptions = computed(() => [
  ...groups.value.map(g => ({ value: g.key, label: g.icon + ' ' + g.label })),
  { value: '__hidden__', label: '🙈 隐藏' },
])
const filterOptions = computed(() => [
  { value: '', label: '全部大类' },
  ...groupOptions.value,
])

const filtered = computed(() => {
  const kw = filter.value.trim().toLowerCase()
  const g = filterGroup.value
  let list = items.value
  if (kw) list = list.filter(i => i.raw.toLowerCase().includes(kw))
  if (g) list = list.filter(i => currentGroup(i) === g)
  return list.slice(0, 300)
})

const editedCount = () => Object.keys(edits.value).length

/**
 * 页面栏上的「未保存 N 条」「重新载入」「保存改动」由父页面 KbView 渲染。
 * 用函数（不用 ref / computed）：视图渲染时调用，读到 edits / loading 的变化
 * 会计入视图的渲染副作用，数字和禁用态都跟得上。
 *
 * 「怎么用」面板里的「按自动规则归类 / 全部恢复自动规则」**留在原处**：
 * 它们是不可撤销的批量操作，紧挨着那四条规则说明才好判断该不该点，
 * 挂到常驻的吸顶栏里反而容易误触。
 */
defineExpose({
  reload: load,
  saveAll,
  editedCount,
  isLoading: () => loading.value,
})

onMounted(load)
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-if="ok" tone="ok">{{ ok }}</Notice>

    <!--
      大类统计 + 快捷筛选。
      为什么不用共享的 Stat：Stat 是纯展示的 div，这里需要【整卡可点】来切筛选，
      把 Stat 塞进 button 又会出现 div 嵌在按钮里的非法结构。
      所以保留自定义卡，但配色只用令牌、悬浮反馈按 Button 的规矩同时动背景与边缘。
    -->
    <div class="cat-grid">
      <div
        v-for="s in stats" :key="s.key"
        class="cat-card" :class="{ on: filterGroup === s.key, muted: s.key === 'other' }"
      >
        <!-- 主按钮仍是「筛选」，和重构前一致；穿透是下面那个独立的小按钮 -->
        <button
          type="button"
          class="cat-main"
          :aria-pressed="filterGroup === s.key"
          @click="toggleFilter(s.key)"
        >
          <span class="cat-icon" aria-hidden="true">{{ s.icon }}</span>
          <span class="cat-label">{{ s.label }}</span>
          <span class="cat-value num">{{ num(s.chunkCount) }}</span>
          <span class="faint cat-sub">{{ s.rawCount }} 个原始分类</span>
        </button>
        <button
          type="button"
          class="cat-view"
          :aria-label="'查看「' + s.label + '」大类下的条目'"
          @click="openEntries({ group: s.key })"
        >
          查看条目 ›
        </button>
      </div>
    </div>

    <!-- 说明 + 批量操作 -->
    <Panel title="怎么用">
      <ol class="flow faint">
        <li>系统已按<b>关键词规则</b>自动归类了一遍 —— 大多数分类不用动。</li>
        <li>发现归错的，改下「大类」下拉即可。<b>只保存改动过的</b>，没改的继续跟着规则走。</li>
        <li>「中文标签」是给玩家看的名字。留空则只显示大类名，不显示这个标签。</li>
        <li>版本更新类的分类（如 Early Access Launch）自动设为<b>隐藏</b>，不展示给玩家。</li>
      </ol>
      <div class="batch">
        <Button size="sm" @click="autoApply">按自动规则归类</Button>
        <Button size="sm" @click="resetAll">全部恢复自动规则</Button>
        <span class="faint batch-note">已手动设置 {{ customCount }} 条</span>
      </div>
    </Panel>

    <!-- 筛选 -->
    <div class="filters">
      <Input v-model="filter" class="filter-kw" mono placeholder="搜索原始分类名（英文）…" aria-label="搜索原始分类名" />
      <div class="filter-group">
        <Select v-model="filterGroup" :options="filterOptions" aria-label="按大类筛选" />
      </div>
      <span class="faint filter-note">
        显示 {{ filtered.length }} / {{ items.length }} 条
        <template v-if="items.length > 300">（超过 300 条请用搜索缩小范围）</template>
      </span>
    </div>


    <!-- 列表 -->
    <Panel title="原始分类" :count="filtered.length">
      <DataTable
        :rows="filtered.length"
        empty="没有匹配的分类"
        empty-hint="换个关键词，或点上面的统计卡切换大类"
      >
        <thead>
          <tr>
            <th>原始分类</th>
            <th class="cell-group">大类</th>
            <th class="cell-label">中文标签</th>
            <th class="cell-ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="it in filtered" :key="it.raw" :class="{ edited: isEdited(it.raw) }">
            <td class="cell-raw">
              <div class="raw" :class="{ dim: currentGroup(it) === '__hidden__' }">{{ it.raw }}</div>
              <div class="faint meta">
                <button
                  type="button"
                  class="count-btn"
                  :disabled="it.count === 0"
                  :aria-label="'查看「' + it.raw + '」下的 ' + it.count + ' 个条目'"
                  @click="openEntries({ raw: it.raw })"
                >
                  <span class="num">{{ it.count }}</span> 条目 ›
                </button>
                <Tag v-if="it.custom" tone="flame">已手动</Tag>
              </div>
            </td>

            <td class="cell-group">
              <Select
                :model-value="currentGroup(it)"
                :options="groupOptions"
                :aria-label="'「' + it.raw + '」的大类'"
                @update:model-value="setGroup(it, $event)"
              />
            </td>

            <td class="cell-label">
              <Input
                :model-value="currentLabel(it)"
                placeholder="中文标签（选填）"
                :aria-label="'「' + it.raw + '」的中文标签'"
                @update:model-value="setLabel(it, $event)"
              />
            </td>

            <td class="cell-ops">
              <Button v-if="it.custom" size="sm" @click="resetOne(it)">恢复自动</Button>
              <span v-else class="faint auto-mark">自动</span>
            </td>
          </tr>
        </tbody>
      </DataTable>
    </Panel>

    <!--
      分类穿透：点某个分类的「N 条目」或大类卡片的「查看条目」打开。
      用共享 Modal（Teleport 到 body、Esc / 点遮罩关闭、自动焦点管理），
      不自己再写一层遮罩 —— 抽屉那套样式抄第二遍必然漂移。
    -->
    <Modal :open="entryOpen" :title="entryTitle" width="880px" @close="closeEntries">
      <div class="ent-tools">
        <Input
          v-model="entryQ"
          mono
          placeholder="在结果里搜索（标题 / id / 文档 / 正文）…"
          aria-label="在条目里搜索"
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
        text="这个分类下没有条目"
        hint="换个关键词搜，或检查是不是归类归错了"
      />
      <ul v-else class="ent-list">
        <li v-for="e in entryItems" :key="e.id" class="ent-row">
          <div class="ent-head">
            <span class="ent-title">{{ e.title || '（无标题）' }}</span>
            <code class="ent-id">{{ e.id }}</code>
          </div>
          <div class="ent-meta faint">
            <span v-if="e.docId">文档 {{ e.docId }}</span>
            <span v-if="e.tags && e.tags.length">分类 {{ e.tags.join('、') }}</span>
            <Tag v-if="e.curated" tone="flame">自维护</Tag>
          </div>
          <div class="ent-snip">{{ e.snippet }}</div>
        </li>
      </ul>
    </Modal>
  </div>
</template>

<style scoped>
/* 宽度交给父页面的 AdminPage —— 面板自己定 max-width 会和页面容器打架 */
.panel-wrap {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

/* 工具栏已上移到页面栏（KbView 的 AdminPage#actions），这里只剩内容排布。
   层级靠背景色差，不靠多描一层框。 */

/* 统计卡：层级靠背景色差（surface-raised → 悬浮抬到 bg-raised），不靠多描一层框 */
.cat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--sp-3);
}
/* 卡片外壳只负责「框」：因为要在里面放两个按钮（筛选 / 查看条目），
   不能再把整张卡做成 <button>（按钮里套按钮是非法结构）。
   悬浮反馈留在外壳上，点哪都像在点这张卡。 */
.cat-card {
  display: flex;
  flex-direction: column;
  background: var(--surface-raised);
  border: 1px solid var(--edge);
  border-radius: var(--r-md);
  transition: background var(--dur-fast) var(--ease),
              border-color var(--dur-fast) var(--ease),
              transform var(--dur-fast) var(--ease),
              box-shadow var(--dur-fast) var(--ease);
}
/* 主按钮：筛选。占满卡片上半部，点哪都切筛选 */
.cat-main {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--sp-1);
  width: 100%;
  padding: var(--sp-3) var(--sp-4) var(--sp-2);
  background: none;
  border: none;
  border-radius: var(--r-md) var(--r-md) 0 0;
  text-align: left;
  color: inherit;
  cursor: pointer;
}
.cat-main:focus-visible { outline: 2px solid var(--flame); outline-offset: -2px; }
/* 次要动作：穿透。小字、右对齐、低对比，不跟主按钮抢注意力 */
.cat-view {
  align-self: flex-end;
  margin: 0 var(--sp-4) var(--sp-3);
  padding: 0;
  background: none;
  border: none;
  font-size: var(--fs-meta);
  color: var(--ink-faint);
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease);
}
.cat-view:hover { color: var(--flame-bright); text-decoration: underline; }
.cat-view:focus-visible { outline: 2px solid var(--flame); outline-offset: 2px; border-radius: var(--r-sm); }
/* 悬浮必须【背景 + 边缘 + 位移】同时变：只改边缘色在深底上看不出能不能点 */
.cat-card:hover {
  background: var(--bg-raised);
  border-color: var(--edge-hover);
  transform: translateY(-1px);
}
.cat-card:active { background: var(--surface-active); transform: translateY(0); }
/* 外壳本身不再可聚焦（焦点在里面的两个按钮上），这条留给键盘用户看清位置 */
.cat-card:focus-within { border-color: var(--edge-hover); }
/* 选中态：灵火边缘 + 柔光，选中项一眼可见 */
.cat-card.on {
  background: var(--flame-veil);
  border-color: var(--flame);
  box-shadow: 0 0 0 3px var(--flame-glow);
}
.cat-card.on:hover { background: var(--flame-glow); border-color: var(--flame-bright); }
/* 「其它」是兜底大类，压暗一档不抢戏 */
.cat-card.muted { opacity: .6; }
.cat-icon { font-size: var(--fs-lg); line-height: 1; }
.cat-label { font-size: var(--fs-body); color: var(--ink-dim); }
.cat-value { font-size: var(--fs-xl); line-height: 1.15; color: var(--flame-bright); }
.cat-sub { font-size: var(--fs-meta); }

.flow { line-height: 1.9; padding-left: 1.2em; font-size: var(--fs-body); }
.batch { display: flex; gap: var(--sp-2); align-items: center; margin-top: var(--sp-4); flex-wrap: wrap; }
.batch-note { font-size: var(--fs-meta); }

.filters { display: flex; gap: var(--sp-2); align-items: center; flex-wrap: wrap; }
/* Input / Select 自身是 width:100%，所以宽度加在外层包装或类上，避免和组件样式打架 */
.filter-kw { flex: 1; min-width: 200px; }
.filter-group { flex: none; width: 200px; }
.filter-note { font-size: var(--fs-meta); }

/* 原始分类名：等宽 + 可断行，长名字不会把表格撑破 */
.cell-raw { min-width: 0; }
.raw {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  color: var(--ink);
  overflow-wrap: anywhere;
}
.raw.dim { opacity: .45; text-decoration: line-through; }
.meta {
  font-size: var(--fs-meta);
  display: flex;
  gap: var(--sp-2);
  align-items: center;
  margin-top: 2px;
}

/* 未保存改动的行：背景染色 + 左缘火焰线（和共享 Field 的 changed 同一套语言）。
   DataTable 自己的行样式的特异性也是 tr 级，所以用面板祖先 + :deep 提权，
   而不是加 !important。上下留白由 DataTable 的单元格内边距提供。 */
.panel-wrap :deep(table.tbl tbody tr.edited) { background: var(--flame-veil); }
.panel-wrap :deep(table.tbl tbody tr.edited:hover) {
  background: var(--flame-veil);
  box-shadow: inset 0 0 0 1px var(--flame-glow);
}
.panel-wrap :deep(table.tbl tbody tr.edited td:first-child) {
  box-shadow: inset 2px 0 0 var(--flame);
}

/* 操作列：表头右对齐要压过 DataTable 自带的 th 样式，同样用祖先 + :deep 提权 */
.panel-wrap :deep(th.cell-ops),
.panel-wrap :deep(td.cell-ops) { text-align: right; white-space: nowrap; }
.panel-wrap :deep(th.cell-group),
.panel-wrap :deep(td.cell-group) { width: 210px; }
.panel-wrap :deep(th.cell-label),
.panel-wrap :deep(td.cell-label) { width: 210px; }

.auto-mark { font-size: var(--fs-meta); opacity: .6; }



/* 行内「N 条目」：看着像数字，其实是个按钮 —— 悬停加下划线 + 火焰色，
   点下去打开该分类的条目清单。禁用态（0 条）保持普通文本的样子。 */
.count-btn {
  padding: 0;
  background: none;
  border: none;
  font: inherit;
  color: inherit;
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease);
}
.count-btn:hover:not(:disabled) { color: var(--flame-bright); text-decoration: underline; }
.count-btn:focus-visible { outline: 2px solid var(--flame); outline-offset: 2px; border-radius: var(--r-sm); }
.count-btn:disabled { cursor: default; }

/* ===== 穿透弹窗 ===== */
.ent-tools {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: var(--sp-3);
}
/* Input 自身是 width:100%，让它弹性占满，搜索框才不会被「搜索」按钮挤成一条 */
.ent-tools > .inp, .ent-tools > input { flex: 1; min-width: 220px; }
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
.ent-id {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--ink-faint);
  overflow-wrap: anywhere;
}
.ent-meta {
  font-size: var(--fs-meta);
  display: flex;
  gap: var(--sp-2);
  align-items: center;
  flex-wrap: wrap;
  margin-top: 2px;
}
.ent-snip {
  margin-top: var(--sp-1);
  font-size: var(--fs-meta);
  color: var(--ink-dim);
  line-height: 1.7;
  overflow-wrap: anywhere;
}

@media (max-width: 900px) {
  /* 窄屏：表单控件占满整行，不再并排 */
  .filter-kw, .filter-group { flex: 1 1 100%; width: auto; }
  .panel-wrap :deep(th.cell-group),
  .panel-wrap :deep(td.cell-group),
  .panel-wrap :deep(th.cell-label),
  .panel-wrap :deep(td.cell-label) { width: auto; }
}
</style>
