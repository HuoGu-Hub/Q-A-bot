<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { renderInline } from '../renderInline'
import { useRoute, useRouter } from 'vue-router'
import { publicApi, describeError } from '@shared/api/client'
import type { KbEntry, KbSearchResult, KbGroup, KbBrowseResult, PlazaKeywordItem } from '@shared/api/types'
import { num } from '@shared/utils/format'
import { t } from '../useSiteText'
import Button from '@shared/ui/Button.vue'
import Empty from '@shared/ui/Empty.vue'
import Icon from '@shared/ui/Icon.vue'

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
/** 大家常问（来自问答广场的投票数据） */
const hot = ref<PlazaKeywordItem[]>([])
/** 热词是全部来自投票，还是掺了「被问最多」的 */
const hotMixed = ref(false)

/** 当前选中的大类 / 标签 */
const curGroup = computed(() => String(route.query.group ?? ''))
const curTag = computed(() => String(route.query.tag ?? ''))
const curPage = computed(() => Number(route.query.page ?? 0))
const hasQuery = computed(() => !!route.query.q)
const isBrowsing = computed(() => !!curGroup.value || !!curTag.value)

const groupOf = (key: string) => groups.value.find(g => g.key === key)

/**
 * 大类图标。
 *
 * ⚠️ 后端 Group.icon 里存的是 emoji（⚔️ 🧱 🧪 …），**这里刻意不用它**：
 * emoji 在每个平台上长得都不一样、还自带高饱和配色 —— 一排彩色 emoji 会把
 * 全站"只有一个暖色重点"的克制感直接抹掉。改用与 key 一一对应的线性图标，
 * 颜色跟着主题走，手机和桌面上长得一模一样。
 * key 是后端定义的稳定英文键（见 KbGroups.ALL），所以这个映射不会漂。
 */
const GROUP_ICONS: Record<string, string> = {
  combat: 'shield',
  build: 'blocks',
  material: 'flask',
  creature: 'paw',
  world: 'map-pin',
  quest: 'scroll',
  system: 'gear',
  guide: 'spark',
  other: 'book',
}
const iconOf = (key: string) => GROUP_ICONS[key] ?? 'book'

async function loadGroups() {
  try {
    groups.value = await publicApi.get<KbGroup[]>('/kb/groups')
  } catch { /* 拿不到就不显示分类 */ }
}

async function loadHot() {
  try {
    const r = await publicApi.get<{ keywords: PlazaKeywordItem[]; mixed?: boolean }>('/plaza/keywords?limit=16')
    hot.value = r.keywords ?? []
    hotMixed.value = r.mixed === true
  } catch { /* 忽略 */ }
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
 * 而路由表里**根本没有 manage** —— router.push({name:'manage'}) 会直接抛
 * "No match for route"，那个按钮点了什么都不会发生（控制台一条红字而已）。
 * 空分组照常打开，由空态去承担"还没内容、等你来写"的表达。
 */
function openGroup(g: KbGroup) {
  router.push({ name: 'library', query: { group: g.key } })
}

/** 攻略组还没内容时的友好显示 */
const isEmptyGuide = (g: KbGroup) => g.key === 'guide' && g.entryCount === 0
function openTag(raw: string) {
  router.push({ name: 'library', query: { group: curGroup.value, tag: raw } })
}
function backToGroups() {
  router.push({ name: 'library' })
}
function goPage(p: number) {
  router.push({ name: 'library', query: { ...route.query, page: String(p) } })
}
function openKeyword(kw: string) {
  q.value = kw
  router.push({ name: 'library', query: { q: kw } })
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
  await Promise.all([loadGroups(), loadHot()])
  await refresh()
})

watch(() => route.query, () => { refresh(); window.scrollTo({ top: 0 }) })
</script>

<template>
  <div class="page library">
    <header class="lib-head">
      <p class="eyebrow">Wiki Index</p>
      <h1 class="lib-title">资料库</h1>
      <p class="lib-sub">
        <span v-html="renderInline(t('library.subtitle', '《雾锁王国》Wiki 的中文索引 —— 直接说人话就行，比如「木头怎么弄」「等级上限」。'))" />
      </p>

      <form class="search" role="search" @submit.prevent="submit">
        <span class="s-ico" aria-hidden="true"><Icon name="search" :size="18" /></span>
        <label class="sr-only" for="lib-q">搜索资料库</label>
        <input
          id="lib-q"
          v-model="q"
          class="s-inp"
          type="search"
          name="q"
          placeholder="搜点什么… 比如「废料杯」「爆炸箭」「游牧高地」"
          autocomplete="off"
          enterkeyhint="search"
        >
        <!-- 清空：只在有内容时出现。手机上没有键盘 Esc，纯靠手势清空搜索框很难受 -->
        <button v-if="q" type="button" class="s-clear" aria-label="清空" @click="clearAll">
          <Icon name="close" :size="15" />
        </button>
        <Button class="s-go" variant="primary" type="submit">
          <Icon name="search" :size="17" class="s-go-ico" />
          <span class="s-go-txt">搜索</span>
        </Button>
      </form>
    </header>

    <p v-if="error" class="err" role="alert">{{ error }}</p>

    <!-- ========== 搜索结果 ========== -->
    <template v-if="hasQuery">
      <div class="crumb">
        <button class="back" type="button" @click="backToGroups">
          <Icon name="arrow-left" :size="16" /> 返回分类
        </button>
      </div>
      <p v-if="loading" class="muted center">查找中…</p>
      <template v-else-if="result">
        <!-- h2 而不是 div：这一条是"结果区块"的标题，下面每条结果才是 h3；
             用 div 会让标题层级从 h1 直接跳到 h3，读屏用户按标题跳转时看不出结构。 -->
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
          ⚠️ 整行可点，但**必须是真的链接**。
          上一版是「<article tabindex=0 @click @keydown.enter>」—— 那是 div 假装链接：
          读屏念不出目的地、右键「在新标签打开」没有、中键点不动、也无法被浏览器预取。
          现在标题是真的 <RouterLink>，再用 ::after 把整块行铺成它的点击区
          （链接本身仍是唯一的可聚焦元素，不会出现两个焦点）。
        -->
        <div v-else class="rows">
          <article v-for="e in result.entries" :key="e.title" class="row">
            <div class="row-main">
              <h3 class="row-title">
                <RouterLink :to="{ name: 'entry', params: { title: e.title } }">{{ e.title }}</RouterLink>
              </h3>
              <p class="row-ex">{{ (e.text ?? "").slice(0, 130) }}…</p>
            </div>
            <Icon name="chevron-right" :size="16" class="row-go" />
          </article>
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
      <section v-if="curGroup && groupOf(curGroup) && !curTag" class="block">
        <div class="block-head">
          <h2 class="block-title">细分标签</h2>
          <span class="faint hint">点标签缩小范围</span>
        </div>
        <div class="tags">
          <button v-for="tg in groupOf(curGroup)!.tags" :key="tg.raw"
                  class="tag-chip" type="button" @click="openTag(tg.raw)">
            {{ tg.label }}<span class="tag-n num">{{ tg.count }}</span>
          </button>
        </div>
      </section>

      <p v-if="loading" class="muted center">载入中…</p>
      <template v-else-if="browse">
        <h2 class="result-head">
          <span>共 <b class="num">{{ browse.total }}</b> 个条目<template v-if="totalPages > 1"> · 第 {{ browse.page + 1 }} / {{ totalPages }} 页</template></span>
        </h2>
        <Empty v-if="!browse.entries.length" text="这个分类下还没有条目" />
        <div v-else class="rows">
          <article v-for="e in browse.entries" :key="e.title" class="row">
            <div class="row-main">
              <h3 class="row-title">
                <RouterLink :to="{ name: 'entry', params: { title: e.title } }">{{ e.title }}</RouterLink>
              </h3>
              <p class="row-ex">{{ (e.text ?? "").slice(0, 130) }}…</p>
              <div v-if="e.tags.length" class="row-tags">
                <span v-for="tg in e.tags" :key="tg" class="cat">{{ tg }}</span>
              </div>
            </div>
            <Icon name="chevron-right" :size="16" class="row-go" />
          </article>
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
          <span class="faint hint">{{ groups.length }} 个大类</span>
        </div>
        <div v-if="groups.length" class="grid">
          <button
            v-for="g in groups" :key="g.key"
            class="gcard" :class="{ inviting: isEmptyGuide(g) }"
            type="button" @click="openGroup(g)"
          >
            <span class="gcard-icon" aria-hidden="true"><Icon :name="iconOf(g.key)" :size="20" /></span>
            <span class="gcard-body">
              <span class="gcard-label">{{ g.label }}</span>
              <span class="gcard-desc">
                {{ isEmptyGuide(g) ? "还没有内容，来写第一篇？" : g.desc }}
              </span>
            </span>
            <span class="gcard-count num">
              {{ isEmptyGuide(g) ? "征集" : num(g.entryCount) }}
            </span>
          </button>
        </div>
        <p v-else class="faint center empty-hint">分类载入中…（如果一直没有，说明后端还没重启）</p>
      </section>

      <section class="block">
        <div class="block-head">
          <h2 class="block-title">大家常问</h2>
          <span class="faint hint">{{ hotMixed ? "群友问得最多的" : "被点赞认可的" }}</span>
        </div>
        <div v-if="hot.length" class="hot">
          <button v-for="k in hot" :key="k.keyword" class="hot-item" type="button" @click="openKeyword(k.keyword)">
            <span class="hot-kw">{{ k.keyword }}</span>
            <span v-if="k.termEn" class="hot-en num">{{ k.termEn }}</span>
            <span class="hot-count num">{{ num(k.votedCount || k.count) }}</span>
          </button>
        </div>
        <p v-else class="faint center empty-hint">还没有人查过什么。去群里 @ 机器人问一句吧～</p>
      </section>

      <!--
        ⚠️ 这里原来是一个指向 /manage 的按钮，而公开站**没有**这个路由：
        点了要么静默报错（router.push({name}) 找不到名字）、要么落到 404 页
        （写了 path 的话）。一个"点了没用"的按钮比没有按钮更伤 ——
        所以改成一句话说明该怎么提交，不装作站内有这个入口。
      -->
      <section class="block contribute">
        <div class="contribute-text">
          <h2 class="block-title">发现内容有误或缺失？</h2>
          <p class="faint">
            资料来自 enshrouded.wiki.gg。要补充或修订，请在群里 @ 机器人，写下词条名和要改的内容 ——
            经管理员审核、重建索引后会在站上生效。
          </p>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.library { max-width: 1040px; padding-top: var(--sp-6); padding-bottom: var(--sp-8); }

/* ==================== 页头 ==================== */
.lib-head { margin-bottom: var(--sp-6); }
.lib-title {
  font-size: var(--fs-3xl);
  letter-spacing: var(--tracking-ink);
  margin: var(--sp-2) 0 var(--sp-3);
}
.lib-sub { font-size: var(--fs-base); color: var(--ink-3); max-width: 40em; margin-bottom: var(--sp-5); }

/* 搜索框：整条做成一个"刻槽"，图标在槽里，按钮贴在槽右端。
   上一版是 input + 按钮两个独立圆角方块并排、圆角还不一样（6px vs 3px）。 */
.search {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  max-width: 660px;
  padding: 6px 6px 6px var(--sp-4);
  background: var(--stone-void);
  border: 1px solid var(--edge);
  border-radius: var(--r-md);
  box-shadow: var(--bevel-inset);
  transition: border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
}
.search:focus-within {
  border-color: var(--ember);
  box-shadow: var(--bevel-inset), 0 0 0 2px var(--stone-200), 0 0 0 4px var(--ember);
}
.s-ico { flex: none; color: var(--ink-3); display: grid; place-items: center; }
.s-inp {
  flex: 1;
  min-width: 0;
  /* 手机端必须 ≥16px，否则 iOS 聚焦时会放大整页（见 base.css 的说明） */
  font-size: var(--fs-base);
  background: none;
  border: 0;
  outline: none;
  padding: 9px 0;
  color: var(--ink);
}
/* 占位符也是要读的文字，不能按"装饰"处理 —— --ink-4 在 stone-void 上只有 3.8:1 */
.s-inp::placeholder { color: var(--ink-3); }
.s-inp::-webkit-search-cancel-button { display: none; }
.s-clear {
  flex: none;
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 50%;
  background: var(--stone-400);
  color: var(--ink-3);
  cursor: pointer;
}
.s-clear:hover { background: var(--stone-500); color: var(--ink); }
.s-go { flex: none; min-height: 38px; }
.s-go-ico { display: none; }

.center { text-align: center; }
.err {
  color: var(--blight-lift);
  font-size: var(--fs-base);
  background: var(--blight-veil);
  border: 1px solid color-mix(in srgb, var(--blight) 40%, transparent);
  border-radius: var(--r-sm);
  padding: var(--sp-3) var(--sp-4);
}
/* 语义是 h2（结果区块的标题），**样式上仍是一条元信息**。
   ⚠️ 必须显式压回无衬线 + 常规字重：base.css 给 h1–h4 统一设了衬线体和 semibold，
   不写这两行，"找到 12 条与「近战」相关"会突然变成一行宋体粗字。 */
.result-head {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
  margin: 0 0 var(--sp-4);
  font-family: var(--font-body);
  font-size: var(--fs-sm);
  font-weight: var(--fw-normal);
  letter-spacing: 0;
  color: var(--ink-3);
}
.result-head b { color: var(--ink); }
/* 检索模式标识：让用户知道这次是"懂人话的搜索"还是"只认名词的搜索" */
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
  margin-bottom: var(--sp-4);
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
  padding: 4px 0;
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

/* ==================== 大类卡片 ====================
   一块块石板，网格里靠 gap 分开 —— 每一块都要描边的话，8 块并排就是 8 圈线。
   图标放在"刻进去"的方槽里：这是全站统一的"物件槽"语言。 */
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(272px, 1fr));
  gap: var(--sp-3);
}
.gcard {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  text-align: left;
  background: var(--stone-300);
  border: 0;
  border-radius: var(--r-md);
  padding: var(--sp-3) var(--sp-4);
  cursor: pointer;
  box-shadow: var(--bevel-raised);
  transition: background var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
}
.gcard:hover { background: var(--stone-400); }
.gcard:active { background: var(--stone-300); box-shadow: var(--bevel-inset); }
.gcard-icon {
  flex: none;
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: var(--r-sm);
  background: var(--stone-void);
  box-shadow: var(--bevel-inset);
  color: var(--ink-3);
  transition: color var(--dur-fast) var(--ease);
}
.gcard:hover .gcard-icon { color: var(--ember); }
.gcard-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.gcard-label { font-size: var(--fs-base); color: var(--ink); font-weight: var(--fw-medium); }
.gcard-desc {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 计数用等宽 + 次要色：上一版给的是铜色 #8a6a3f，在深底上只有 3.4:1 —— 看不清 */
.gcard-count { flex: none; font-size: var(--fs-sm); color: var(--ink-3); }
/* 空的攻略组：用更淡的底 + 灵火描边，暗示「等你来填」（虚线框显得廉价） */
.gcard.inviting { background: var(--stone-200); box-shadow: var(--bevel-raised), inset 0 0 0 1px var(--ember-veil); }
.gcard.inviting .gcard-icon { color: var(--ember); }
.gcard.inviting .gcard-count { color: var(--ember); font-size: var(--fs-xs); }

/* ==================== 标签云 ==================== */
.tags { display: flex; flex-wrap: wrap; gap: var(--sp-2); }
.tag-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  background: var(--stone-400);
  border: 1px solid transparent;
  border-radius: var(--r-pill);
  padding: 6px 14px;
  font-size: var(--fs-sm);
  color: var(--ink-2);
  cursor: pointer;
  box-shadow: var(--bevel-shelf);
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease),
              border-color var(--dur-fast) var(--ease);
}
.tag-chip:hover { background: var(--stone-500); color: var(--ink); border-color: var(--edge); }
.tag-n { font-size: var(--fs-micro); color: var(--ink-3); }

/* ==================== 结果行 ====================
   一列行 + 发丝分隔，而不是一摞卡片。
   卡片适合"每条都值得被单独看"的内容；搜索结果是一张清单，行更好扫。 */
.rows { display: flex; flex-direction: column; }
.row {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  padding: var(--sp-4) var(--sp-3);
  border-bottom: 1px solid var(--hairline);
  border-radius: var(--r-sm);
  transition: background var(--dur-fast) var(--ease);
}
.row:first-child { border-top: 1px solid var(--hairline); }
.row:hover { background: var(--surface-hover); }
.row:hover .row-title a { color: var(--ember-hot); }
.row:hover .row-go { color: var(--ember); transform: translateX(2px); }
/* 键盘走到标题链接上时，整行也要亮起来 —— 否则焦点只在两个字上，看不清落在哪一行 */
.row:focus-within { background: var(--surface-hover); box-shadow: inset 2px 0 0 var(--ember); }
.row-main { flex: 1; min-width: 0; }
.row-title { font-size: var(--fs-base); font-weight: var(--fw-medium); margin: 0 0 4px; letter-spacing: 0; }
.row-title a { color: var(--ember); text-decoration: none; }
/* 链接的点击区铺满整行，但可聚焦元素仍然只有这一个 */
.row-title a::after { content: ''; position: absolute; inset: 0; border-radius: inherit; }
.row-ex { font-size: var(--fs-sm); color: var(--ink-3); line-height: 1.7; margin: 0; }
.row-tags { margin-top: var(--sp-2); display: flex; gap: 6px; flex-wrap: wrap; }
.row-go { flex: none; color: var(--ink-4); transition: color var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease); }
.cat {
  font-size: var(--fs-micro);
  color: var(--ink-3);
  background: var(--stone-400);
  border-radius: var(--r-pill);
  padding: 1px 8px;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--sp-4);
  margin-top: var(--sp-5);
  font-size: var(--fs-sm);
}

/* ==================== 区块 ==================== */
.block { margin-bottom: var(--sp-7); }
.block-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-bottom: var(--sp-3);
  padding-bottom: var(--sp-2);
  border-bottom: 1px solid var(--hairline);
}
.block-title {
  font-size: var(--fs-lg);
  letter-spacing: .04em;
}
.hint { font-size: var(--fs-xs); }

/* 常问词：两列清单，行与行之间一条发丝线。
   上一版是 4 列的小卡片，每张卡里"中文 / 英文 / 次数"贴着三个角，
   列宽一变就对不齐 —— 清单反而是最不容易散架的形式。 */
.hot { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 0 var(--sp-5); }
.hot-item {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
  width: 100%;
  background: none;
  border: 0;
  border-bottom: 1px solid var(--hairline);
  padding: 11px var(--sp-2);
  cursor: pointer;
  text-align: left;
  border-radius: var(--r-sm);
  transition: background var(--dur-fast) var(--ease);
}
.hot-item:hover { background: var(--surface-hover); }
.hot-kw { flex: 1; min-width: 0; font-size: var(--fs-base); color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hot-item:hover .hot-kw { color: var(--ember-hot); }
.hot-en {
  flex: none;
  max-width: 40%;
  font-size: var(--fs-micro);
  /* ⚠️ 这是**英文词条名**，是要读的内容，不是装饰 —— 用三级文字色（6.4:1）。
     axe 实测：原来的 --ink-4 在页面底上只有 3.63:1，16 个词条全部不合格。 */
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hot-count { flex: none; font-size: var(--fs-xs); color: var(--ink-3); min-width: 22px; text-align: right; }
.empty-hint { padding: var(--sp-5) 0; }

/* 贡献入口：一块横向的"落款"区，不和上面的清单抢注意力 */
.contribute {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-5);
  flex-wrap: wrap;
  margin-bottom: 0;
  padding: var(--sp-5);
  background: var(--stone-300);
  border-radius: var(--r-md);
  box-shadow: var(--bevel-raised);
}
.contribute-text { min-width: 0; }
.contribute-text .block-title { border: 0; padding: 0; margin-bottom: var(--sp-2); }
.contribute-text p { font-size: var(--fs-sm); max-width: 46em; }

@media (max-width: 760px) {
  .lib-title { font-size: var(--fs-2xl); }
  /* 搜索：按钮收成纯图标方块，把宽度全让给输入框。
     上一版在 390px 下 placeholder 直接被截成"搜点什么… 比如「废料杯」「爆炸箭」」，很寒酸。 */
  .search { padding-right: 5px; }
  .s-go { min-width: 44px; padding: 0; }
  /* ⚠️ 用 sr-only 而不是 display:none。
  display:none 会把"搜索"两个字从无障碍树里一起删掉，按钮就只剩一个没有名字的图标
  —— axe 直接判 critical（Buttons must have discernible text）。
  视觉上它同样不可见，但读屏仍然会念"搜索"。 */
  .s-go-txt {
    position: absolute;
    width: 1px; height: 1px;
    padding: 0; margin: -1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    white-space: nowrap;
    border: 0;
  }
  .s-go-ico { display: block; }
  .grid { grid-template-columns: 1fr; }
  .hot { grid-template-columns: 1fr; }
  .contribute { flex-direction: column; align-items: stretch; }
}
</style>
