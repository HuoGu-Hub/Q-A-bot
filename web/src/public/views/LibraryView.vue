<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { renderInline } from '../renderInline'
import { useRoute, useRouter } from 'vue-router'
import { publicApi, describeError } from '@shared/api/client'
import type { KbEntry, KbSearchResult, KbGroup, KbBrowseResult } from '@shared/api/types'
import { num } from '@shared/utils/format'
import { t } from '../useSiteText'
import Button from '@shared/ui/Button.vue'
import Empty from '@shared/ui/Empty.vue'
import Icon from '@shared/ui/Icon.vue'
import ListRow from '@shared/ui/ListRow.vue'
import PageHeader from '@shared/ui/PageHeader.vue'
import SearchBar from '@shared/ui/SearchBar.vue'
import Notice from '@shared/ui/Notice.vue'
import Chip from '@shared/ui/Chip.vue'

const route = useRoute()
const router = useRouter()

const q = ref(String(route.query.q ?? ''))
const loading = ref(false)
const error = ref('')

const result = ref<KbSearchResult | null>(null)
const browse = ref<KbBrowseResult | null>(null)
const groups = ref<KbGroup[]>([])

const curGroup = computed(() => String(route.query.group ?? ''))
const curTag = computed(() => String(route.query.tag ?? ''))
const curPage = computed(() => Number(route.query.page ?? 0))
const hasQuery = computed(() => !!route.query.q)
const isBrowsing = computed(() => !!curGroup.value || !!curTag.value)

const groupOf = (key: string) => groups.value.find(g => g.key === key)

const CAT_TOP = 12
const catsOpen = ref(false)
const catFilter = ref('')

const sortedGroups = computed(() =>
  [...groups.value].sort((a, b) => b.entryCount - a.entryCount))

const topGroups = computed(() => sortedGroups.value.slice(0, CAT_TOP))

const filteredGroups = computed(() => {
  const kw = catFilter.value.trim().toLowerCase()
  if (!kw) return sortedGroups.value
  return sortedGroups.value.filter(g =>
    g.label.toLowerCase().includes(kw) || g.key.toLowerCase().includes(kw))
})

function iconOf(_key: string) { return 'book' }

async function loadGroups() {
  try {
    groups.value = await publicApi.get<KbGroup[]>('/kb/groups')
  } catch {   }
}

async function search(kw: string) {
  if (!kw) { result.value = null; return }
  loading.value = true
  error.value = ''
  try {
    result.value = await publicApi.get<KbSearchResult>('/kb/search?q=' + encodeURIComponent(kw))
  } catch (e) { error.value = describeError(e); result.value = null } finally { loading.value = false }
}

async function doBrowse(group: string, tag: string, page: number) {
  if (!group && !tag) { browse.value = null; return }
  loading.value = true
  error.value = ''
  try {
    const params = new URLSearchParams()
    if (group) params.set('group', group)
    if (tag) params.set('tag', tag)
    params.set('page', String(page))
    browse.value = await publicApi.get<KbBrowseResult>('/kb/browse?' + params.toString())
  } catch (e) { error.value = describeError(e); browse.value = null } finally { loading.value = false }
}

function submit() {
  const kw = q.value.trim()
  router.push({ name: 'library', query: kw ? { q: kw } : {} })
}

function clearAll() {
  q.value = ''
  router.push({ name: 'library' })
}

function openGroup(g: KbGroup) {
  router.push({ name: 'library', query: { group: g.key } })
}

function openTag(raw: string) {
  router.push({ name: 'library', query: { group: curGroup.value, tag: raw } })
}
function backToGroups() {
  router.push({ name: 'library' })
}
function goPage(p: number) {
  router.push({ name: 'library', query: { ...route.query, page: String(p) } })
}
const totalPages = computed(() =>
  browse.value ? Math.ceil(browse.value.total / browse.value.pageSize) : 0)

async function refresh() {
  if (hasQuery.value) {
    await search(String(route.query.q))
  } else if (isBrowsing.value) {
    await doBrowse(curGroup.value, curTag.value, curPage.value)
  } else {
    result.value = null
    browse.value = null
  }
}

onMounted(async () => {
  await loadGroups()
  await refresh()
})

watch(() => route.query, () => { refresh(); window.scrollTo({ top: 0 }) })
</script>

<template>
  <div class="page library">
    <PageHeader title="资料库" />

    <div class="search-wrap">
      <SearchBar
        v-model="q"
        label="搜索资料库"
        placeholder="输入关键词… 比如「怎么合成」「上限是多少」"
        @submit="submit"
        @clear="clearAll"
      />
    </div>

    <Notice v-if="error" tone="error" class="err">{{ error }}</Notice>

    <template v-if="hasQuery">
      <div class="crumb">
        <button class="back" type="button" @click="backToGroups">
          <Icon name="arrow-left" :size="16" /> 返回分类
        </button>
      </div>
      <p v-if="loading" class="muted center">查找中…</p>
      <template v-else-if="result">
        <h2 class="result-head">
          <span>找到 <b class="num">{{ result.count }}</b> 条与「{{ result.query }}」相关</span>
          <span v-if="result.mode === 'semantic'" class="mode-badge"
                title="按语义匹配：中文口语也能搜到英文资料">语义搜索</span>
          <span v-else class="mode-badge local"
                title="本地关键词匹配，只认术语表里的标准名词">关键词搜索</span>
        </h2>
        <Empty v-if="!result.entries.length" icon="search"
               :text="t('library.empty_title', '没找到相关资料')" />
        <div v-else class="rowlist">
          <ListRow
            v-for="e in result.entries"
            :key="e.title"
            :to="{ name: 'entry', params: { title: e.title } }"
            :title="e.title"
            :sub="(e.text ?? '').slice(0, 120)"
            chevron
          />
        </div>
      </template>
    </template>

    <template v-else-if="isBrowsing">
      <div class="crumb">
        <button class="back" type="button" @click="backToGroups">
          <Icon name="arrow-left" :size="16" /> 全部分类
        </button>
        <span v-if="curGroup && groupOf(curGroup)" class="crumb-cur">{{ groupOf(curGroup)!.label }}</span>
        <span v-if="curTag" class="crumb-tag">{{ curTag }}</span>
      </div>

      <section v-if="curGroup && groupOf(curGroup) && !curTag && groupOf(curGroup)!.tags.length" class="block">
        <div class="block-head">
          <h2 class="block-title">细分标签</h2>
        </div>
        <div class="tags">
          <Chip v-for="tg in groupOf(curGroup)!.tags" :key="tg.raw" :count="tg.count" @click="openTag(tg.raw)">
            {{ tg.label }}
          </Chip>
        </div>
      </section>

      <p v-if="loading" class="muted center">载入中…</p>
      <template v-else-if="browse">
        <h2 class="result-head">
          <span>共 <b class="num">{{ browse.total }}</b> 个条目<template v-if="totalPages > 1"> · 第 {{ browse.page + 1 }} / {{ totalPages }} 页</template></span>
        </h2>
        <Empty v-if="!browse.entries.length" text="这个分类下还没有条目" />
        <div v-else class="rowlist">
          <ListRow
            v-for="e in browse.entries"
            :key="e.title"
            :to="{ name: 'entry', params: { title: e.title } }"
            :title="e.title"
            :sub="(e.text ?? '').slice(0, 120)"
            chevron
          />
        </div>
        <div v-if="totalPages > 1" class="pager">
          <Button size="sm" :disabled="browse.page <= 0" @click="goPage(browse.page - 1)">上一页</Button>
          <span class="faint num">{{ browse.page + 1 }} / {{ totalPages }}</span>
          <Button size="sm" :disabled="browse.page + 1 >= totalPages" @click="goPage(browse.page + 1)">下一页</Button>
        </div>
      </template>
    </template>

    <template v-else>
      <section class="block">
        <div class="block-head">
          <h2 class="block-title">按分类找</h2>
          <span class="hint">{{ groups.length }} 个分类</span>
        </div>

        <div v-if="groups.length" class="rowlist cats">
          <ListRow
            v-for="g in (catsOpen ? filteredGroups : topGroups)"
            :key="g.key"
            :title="g.label"
            :meta="num(g.entryCount)"
            :icon="iconOf(g.key)"
            @click="openGroup(g)"
          />
        </div>
        <p v-else class="hint center empty-hint">分类载入中…</p>

        <div v-if="groups.length > CAT_TOP" class="cats-more">
          <button type="button" class="more-btn" :aria-expanded="catsOpen" @click="catsOpen = !catsOpen">
            <Icon :name="catsOpen ? 'chevron-down' : 'chevron-right'" :size="15" />
            {{ catsOpen ? '收起分类' : '查看全部 ' + groups.length + ' 个分类' }}
          </button>
          <input
            v-if="catsOpen"
            v-model="catFilter"
            class="cat-filter"
            type="search"
            placeholder="在分类里找…"
            aria-label="筛选分类"
          >
          <p v-if="catsOpen && !filteredGroups.length" class="hint">没有匹配的分类。</p>
          <div v-if="catsOpen" class="cat-scroll">
            <div class="rowlist">
              <ListRow
                v-for="g in filteredGroups"
                :key="g.key"
                :title="g.label"
                :meta="num(g.entryCount)"
                @click="openGroup(g)"
              />
            </div>
          </div>
        </div>
      </section>

      <section class="block contribute">
        <div class="contribute-text">
          <h2 class="block-title">发现内容有误或缺失？</h2>
          <p class="hint">在群里 @ 机器人，写下词条名和要改的内容。</p>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.library { max-width: 1040px; padding-top: var(--sp-5); padding-bottom: var(--sp-8); }

.search-wrap { margin-bottom: var(--sp-5); }
@media (max-width: 760px) {
  .search-wrap {
    position: sticky;
    top: var(--header-h);
    z-index: 20;
    margin: 0 calc(var(--sp-4) * -1) var(--sp-4);
    padding: var(--sp-2) var(--sp-4);
    background: var(--stone-200);
    background: color-mix(in srgb, var(--stone-200) 94%, transparent);
    backdrop-filter: blur(10px);
    -webkit-backdrop-filter: blur(10px);
    border-bottom: 1px solid var(--hairline);
  }
}

.center { text-align: center; }
.err { margin-bottom: var(--sp-4); }

.result-head {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
  margin: 0 0 var(--sp-3);
  font-family: var(--font-body);
  font-size: var(--fs-sm);
  font-weight: var(--fw-normal);
  letter-spacing: 0;
  color: var(--ink-3);
}
.result-head b { color: var(--ink); }
.mode-badge {
  font-size: var(--fs-xs);
  color: var(--ember);
  background: var(--ember-veil);
  border-radius: var(--r-pill);
  padding: 1px 9px;
}
.mode-badge.local { color: var(--ink-3); background: var(--stone-400); }

.crumb {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-bottom: var(--sp-3);
  flex-wrap: wrap;
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  background: none;
  border: 0;
  color: var(--ink-3);
  font-size: var(--fs-sm);
  cursor: pointer;
  padding: 6px 0;
  min-height: 40px;
  text-decoration: none;
}
.back:hover { color: var(--ember); }
.crumb-cur { font-size: var(--fs-sm); color: var(--ember); }
.crumb-tag {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  background: var(--stone-400);
  border-radius: var(--r-pill);
  padding: 2px 10px;
}

.cats { columns: 1; }
.cats-more { margin-top: var(--sp-3); display: flex; flex-direction: column; gap: var(--sp-2); }
.more-btn {
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 40px;
  padding: 8px 14px;
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  background: var(--stone-400);
  color: var(--ink-2);
  font-size: var(--fs-sm);
  cursor: pointer;
  box-shadow: var(--bevel-shelf);
}
.more-btn:hover { background: var(--stone-500); border-color: var(--edge-hover); color: var(--ink); }
.cat-filter {
  width: 100%;
  max-width: 360px;
  min-height: 40px;
  padding: 8px 12px;
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  background: var(--stone-void);
  color: var(--ink);
  font-size: var(--fs-base);
  box-shadow: var(--bevel-inset);
}
.cat-filter:focus-visible { outline: none; border-color: var(--ember); box-shadow: var(--bevel-inset), var(--focus-ring); }
.cat-scroll {
  max-height: 56vh;
  overflow-y: auto;
  overscroll-behavior: contain;
  border: 1px solid var(--hairline);
  border-radius: var(--r-md);
  background: var(--stone-300);
}
.empty-hint { padding: var(--sp-4) 0; }

@media (min-width: 900px) {
  .cats {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    column-gap: var(--sp-5);
  }
  .cats > * + * { border-top: 0; }
  .cats > * { border-bottom: 1px solid var(--hairline); }
}

.tags { display: flex; flex-wrap: wrap; gap: var(--sp-2); }

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--sp-4);
  margin-top: var(--sp-5);
  font-size: var(--fs-sm);
}

.block { margin-bottom: var(--sp-6); }
.block-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-bottom: var(--sp-2);
  padding-bottom: var(--sp-2);
  border-bottom: 1px solid var(--hairline);
}
.block-title {
  font-size: var(--fs-lg);
  letter-spacing: .03em;
  margin: 0;
}

.contribute {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-5);
  flex-wrap: wrap;
  margin-bottom: 0;
  padding: var(--sp-4) var(--sp-5);
  background: var(--stone-300);
  border-radius: var(--r-md);
  box-shadow: var(--bevel-raised);
}
.contribute-text { min-width: 0; }
.contribute-text .block-title { border: 0; padding: 0; margin-bottom: var(--sp-2); }
.contribute-text p { max-width: 52em; margin: 0; }

@media (max-width: 380px) {
  .search-wrap { margin-left: calc(var(--sp-3) * -1); margin-right: calc(var(--sp-3) * -1); padding-left: var(--sp-3); padding-right: var(--sp-3); }
}
@media (max-width: 760px) {
  .library { padding-top: var(--sp-4); }
  .contribute { flex-direction: column; align-items: stretch; padding: var(--sp-4); }
  .cat-filter { max-width: none; }
}
</style>
