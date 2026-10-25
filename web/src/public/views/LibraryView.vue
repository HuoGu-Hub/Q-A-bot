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

/** 搜索结果 */
const result = ref<KbSearchResult | null>(null)
/** 分类浏览结果 */
const browse = ref<KbBrowseResult | null>(null)
/** 大类定义（含各自的热门标签） */
const groups = ref<KbGroup[]>([])

/** 当前选中的大类 / 标签 */
const curGroup = computed(() => String(route.query.group ?? ''))
const curTag = computed(() => String(route.query.tag ?? ''))
const curPage = computed(() => Number(route.query.page ?? 0))
const hasQuery = computed(() => !!route.query.q)
const isBrowsing = computed(() => !!curGroup.value || !!curTag.value)

const groupOf = (key: string) => groups.value.find(g => g.key === key)

/**
 * 分类索引：189 个 wiki 原始分类不可能一次摊开。
 *
 * 折叠时只给条目最多的 12 个，展开后是一个**自带筛选**的紧凑清单。
 * 上一版把 189 个全渲染成带图标的卡片，手机上要滚十几屏才看得到「大家常问」。
 */
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

/**
 * 大类图标。
 *
 * ⚠️ 后端 Group.icon 里存的是 emoji，这里刻意不用它：emoji 在每个平台上长得都不一样、
 * 还自带高饱和配色，会把「全站只有一个暖色重点」的克制感直接抹掉。
 * 现在的分类是 wiki 原始分类，没有稳定的语义键，所以统一用书本图标，
 * 把辨识度交给标题本身 —— 统一比"每个都图个热闹"更像一个站。
 */
function iconOf(_key: string) { return 'book' }

async function loadGroups() {
  try {
    groups.value = await publicApi.get<KbGroup[]>('/kb/groups')
  } catch { /* 拿不到就不显示分类 */ }
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

/** 清空搜索：回到分类首页。输入框也要一起清 —— 否则地址栏清了、框里还留着旧词 */
function clearAll() {
  q.value = ''
  router.push({ name: 'library' })
}

/**
 * 打开一个大类。
 *
 * ⚠️ 原来这里对空的「大佬攻略」有个特判：直接跳去一个叫 manage 的路由。
 * 而路由表里根本没有 manage —— 点了什么都不会发生（控制台一条红字而已）。
 * 空分组照常打开，由空态去承担「还没内容、等你来写」的表达。
 */
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
/** 分页信息 */
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
    <PageHeader
      title="资料库"
      :desc="t('library.subtitle', '《雾锁王国》Wiki 的中文索引 —— 直接说人话就行，比如「木头怎么弄」「等级上限」。')"
    />

    <!-- 手机上搜索条吸顶：翻到结果第 20 条时还能改词，不用滚回顶部 -->
    <div class="search-wrap">
      <SearchBar
        v-model="q"
        label="搜索资料库"
        placeholder="搜点什么… 比如「废料杯」「爆炸箭」「游牧高地」"
        @submit="submit"
        @clear="clearAll"
      />
    </div>

    <Notice v-if="error" tone="error" class="err">{{ error }}</Notice>

    <!-- ========== 搜索结果 ========== -->
    <template v-if="hasQuery">
      <div class="crumb">
        <button class="back" type="button" @click="backToGroups">
          <Icon name="arrow-left" :size="16" /> 返回分类
        </button>
      </div>
      <p v-if="loading" class="muted center">查找中…</p>
      <template v-else-if="result">
        <!-- h2 而不是 div：这一条是结果区块的标题，下面每条结果才是 h3 -->
        <h2 class="result-head">
          <span>找到 <b class="num">{{ result.count }}</b> 条与「{{ result.query }}」相关</span>
          <span v-if="result.mode === 'semantic'" class="mode-badge"
                title="按语义匹配：中文口语也能搜到英文资料">语义搜索</span>
          <span v-else class="mode-badge local"
                title="本地关键词匹配，只认术语表里的标准名词">关键词搜索</span>
        </h2>
        <Empty v-if="!result.entries.length" icon="search"
               :text="t('library.empty_title', '没找到相关资料')"
               :hint="t('library.empty_hint', '换个更具体的说法（比如把「怎么搞木头」说成「木头」），或者到群里直接问机器人')" />
        <!--
          ⚠️ 整行可点，但必须是真的链接。
          上一版是 div + tabindex + @click —— 读屏念不出目的地、右键开不了新标签。
          现在整行就是一个 RouterLink，没有第二个可聚焦元素。
        -->
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

    <!-- ========== 分类浏览 ========== -->
    <template v-else-if="isBrowsing">
      <div class="crumb">
        <button class="back" type="button" @click="backToGroups">
          <Icon name="arrow-left" :size="16" /> 全部分类
        </button>
        <span v-if="curGroup && groupOf(curGroup)" class="crumb-cur">{{ groupOf(curGroup)!.label }}</span>
        <span v-if="curTag" class="crumb-tag">{{ curTag }}</span>
      </div>

      <!-- 该大类的标签云 -->
      <section v-if="curGroup && groupOf(curGroup) && !curTag && groupOf(curGroup)!.tags.length" class="block">
        <div class="block-head">
          <h2 class="block-title">细分标签</h2>
          <span class="hint">点标签缩小范围</span>
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

    <!-- ========== 首页 ========== -->
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
        <p v-else class="hint center empty-hint">分类载入中…（如果一直没有，说明后端还没重启）</p>

        <div v-if="groups.length > CAT_TOP" class="cats-more">
          <button type="button" class="more-btn" :aria-expanded="catsOpen" @click="catsOpen = !catsOpen">
            <Icon :name="catsOpen ? 'chevron-down' : 'chevron-right'" :size="15" />
            {{ catsOpen ? '收起分类' : '查看全部 ' + groups.length + ' 个分类' }}
          </button>
          <!-- 展开后自带筛选：189 行里靠手滑找分类是不现实的 -->
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

      <!--
        ⚠️ 这里原来是一个指向 /manage 的按钮，而公开站没有这个路由：
        点了要么静默报错、要么落到 404。一个"点了没用"的按钮比没有按钮更伤，
        所以改成一句话说明该怎么提交，不装作站内有这个入口。
      -->
      <section class="block contribute">
        <div class="contribute-text">
          <h2 class="block-title">发现内容有误或缺失？</h2>
          <p class="hint">
            资料来自 enshrouded.wiki.gg。要补充或修订，请在群里 @ 机器人，写下词条名和要改的内容 ——
            经管理员审核、重建索引后会在站上生效。
          </p>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.library { max-width: 1040px; padding-top: var(--sp-5); padding-bottom: var(--sp-8); }

/* ==================== 搜索条 ====================
   手机上吸顶：翻到结果第 20 条时还能改词。桌面端不吸顶，避免压住页头。 */
.search-wrap { margin-bottom: var(--sp-5); }
@media (max-width: 760px) {
  .search-wrap {
    position: sticky;
    top: var(--header-h);
    z-index: 20;
    /* 负外边距必须等于 .page 的左右内边距，否则 360px 下会多出 4px 横向滚动 */
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

/* ==================== 结果计数 ====================
   语义是 h2，**样式上仍是一条元信息**。
   ⚠️ 必须显式压回无衬线 + 常规字重：base.css 给 h1–h4 统一设了衬线体和 semibold。 */
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
/* 检索模式标识：让用户知道这次是「懂人话的搜索」还是「只认名词的搜索」 */
.mode-badge {
  font-size: var(--fs-xs);
  color: var(--ember);
  background: var(--ember-veil);
  border-radius: var(--r-pill);
  padding: 1px 9px;
}
.mode-badge.local { color: var(--ink-3); background: var(--stone-400); }

/* ==================== 面包屑 ==================== */
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

/* ==================== 分类索引 ====================
   一行一个分类，发丝线分隔。189 个分类如果都做成卡片，手机上就是一面英文墙；
   紧凑行 + 折叠 + 自带筛选，才是「找得到」而不是「全给你」。 */
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
/* 展开后的清单自己滚：不把整页撑长，也不会把下面的区块推到几屏之外 */
.cat-scroll {
  max-height: 56vh;
  overflow-y: auto;
  overscroll-behavior: contain;
  border: 1px solid var(--hairline);
  border-radius: var(--r-md);
  background: var(--stone-300);
}
.empty-hint { padding: var(--sp-4) 0; }

/* 两列：宽屏上把 12 行压成 6 行的高度。
   ⚠️ 不能用 CSS columns —— .rowlist 是 flex 容器，columns 对 flex 无效，会静默退成单列。 */
@media (min-width: 900px) {
  .cats {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    column-gap: var(--sp-5);
  }
  /* 分列之后「上一行的下边框」会跑到右列第一项头上，所以改成每项自带底边 */
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

/* ==================== 区块 ==================== */
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

/* 贡献入口：一块横向的落款区，不和上面的清单抢注意力 */
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
  /* .page 在 380 以下收到 12px，这里跟着收，否则吸顶条会比版心宽 8px */
  .search-wrap { margin-left: calc(var(--sp-3) * -1); margin-right: calc(var(--sp-3) * -1); padding-left: var(--sp-3); padding-right: var(--sp-3); }
}
@media (max-width: 760px) {
  .library { padding-top: var(--sp-4); }
  .contribute { flex-direction: column; align-items: stretch; padding: var(--sp-4); }
  .cat-filter { max-width: none; }
}
</style>