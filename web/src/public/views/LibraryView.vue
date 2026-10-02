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

function openEntry(e: { title: string }) {
  router.push({ name: 'entry', params: { title: e.title } })
}

function openGroup(g: KbGroup) {
  // 「大佬攻略」还没内容时，点了直接去写 —— 比看一个空列表有用
  if (g.key === 'guide' && g.entryCount === 0) {
    router.push({ name: 'manage' })
    return
  }
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
    <header class="hero">
      <h1 class="hero-title">资料库</h1>
      <p class="hero-sub faint">
        <span v-html="renderInline(t('library.subtitle', '《雾锁王国》Wiki 的中文索引 —— 直接说人话就行，比如「木头怎么弄」「等级上限」。'))" />
      </p>
      <form class="searchbar" @submit.prevent="submit">
        <input v-model="q" class="search-input" type="search"
               placeholder="搜点什么… 比如「废料杯」「爆炸箭」「游牧高地」" autocomplete="off" />
        <Button variant="primary" @click="submit">搜索</Button>
      </form>
    </header>

    <p v-if="error" class="err">{{ error }}</p>

    <!-- ========== 搜索结果 ========== -->
    <template v-if="hasQuery">
      <div class="crumb">
        <button class="link" @click="backToGroups">← 返回分类</button>
      </div>
      <div v-if="loading" class="muted center">查找中…</div>
      <template v-else-if="result">
        <div class="result-head faint">
          找到 <b class="num">{{ result.count }}</b> 条与「{{ result.query }}」相关
          <span v-if="result.mode === 'semantic'" class="mode-badge"
                title="按语义匹配：中文口语也能搜到英文资料">语义搜索</span>
          <span v-else class="mode-badge local"
                title="本地关键词匹配，只认术语表里的标准名词">关键词搜索</span>
        </div>
        <Empty v-if="!result.entries.length"
               :text="t('library.empty_title', '没找到相关资料')"
               :hint="t('library.empty_hint', '换个更具体的说法（比如把「怎么搞木头」说成「木头」），或者到群里直接问机器人')" />
        <div v-else class="cards">
          <article v-for="e in result.entries" :key="e.title" class="card"
                   tabindex="0" @click="openEntry(e)" @keydown.enter="openEntry(e)">
            <div class="card-title">{{ e.title }}</div>
            <div class="card-excerpt">{{ (e.text ?? "").slice(0, 130) }}…</div>
          </article>
        </div>
      </template>
    </template>

    <!-- ========== 分类浏览 ========== -->
    <template v-else-if="isBrowsing">
      <div class="crumb">
        <button class="link" @click="backToGroups">← 全部分类</button>
        <span v-if="curGroup && groupOf(curGroup)" class="crumb-cur">
          {{ groupOf(curGroup)!.icon }} {{ groupOf(curGroup)!.label }}
        </span>
        <span v-if="curTag" class="crumb-tag">{{ curTag }}</span>
      </div>

      <!-- 该大类的标签云 -->
      <section v-if="curGroup && groupOf(curGroup) && !curTag" class="block">
        <div class="block-head">
          <h2 class="block-title">细分标签</h2>
          <span class="faint">点标签缩小范围</span>
        </div>
        <div class="tags">
          <button v-for="t in groupOf(curGroup)!.tags" :key="t.raw"
                  class="tag-chip" type="button" @click="openTag(t.raw)">
            {{ t.label }}<span class="tag-n num">{{ t.count }}</span>
          </button>
        </div>
      </section>

      <div v-if="loading" class="muted center">载入中…</div>
      <template v-else-if="browse">
        <div class="result-head faint">
          共 <b class="num">{{ browse.total }}</b> 个条目
          <template v-if="totalPages > 1"> · 第 {{ browse.page + 1 }} / {{ totalPages }} 页</template>
        </div>
        <Empty v-if="!browse.entries.length" text="这个分类下还没有条目" />
        <div v-else class="cards">
          <article v-for="e in browse.entries" :key="e.title" class="card"
                   tabindex="0" @click="openEntry(e)" @keydown.enter="openEntry(e)">
            <div class="card-title">{{ e.title }}</div>
            <div class="card-excerpt">{{ (e.text ?? "").slice(0, 130) }}…</div>
            <div v-if="e.tags.length" class="card-foot">
              <span v-for="t in e.tags" :key="t" class="cat">{{ t }}</span>
            </div>
          </article>
        </div>
        <div v-if="totalPages > 1" class="pager">
          <Button size="sm" :disabled="browse.page <= 0" @click="goPage(browse.page - 1)">上一页</Button>
          <span class="faint">{{ browse.page + 1 }} / {{ totalPages }}</span>
          <Button size="sm" :disabled="browse.page + 1 >= totalPages" @click="goPage(browse.page + 1)">下一页</Button>
        </div>
      </template>
    </template>

    <!-- ========== 首页 ========== -->
    <template v-else>
      <section class="block">
        <div class="block-head">
          <h2 class="block-title">按分类找</h2>
          <span class="faint">{{ groups.length }} 个大类</span>
        </div>
        <div v-if="groups.length" class="grid">
          <button
            v-for="g in groups" :key="g.key"
            class="gcard" :class="{ inviting: isEmptyGuide(g) }"
            type="button" @click="openGroup(g)"
          >
            <div class="gcard-icon">{{ g.icon }}</div>
            <div class="gcard-body">
              <div class="gcard-label">{{ g.label }}</div>
              <div class="faint gcard-desc">
                {{ isEmptyGuide(g) ? "还没有内容，来写第一篇？" : g.desc }}
              </div>
            </div>
            <div class="gcard-count num">
              {{ isEmptyGuide(g) ? "征集中" : num(g.entryCount) }}
            </div>
          </button>
        </div>
        <p v-else class="faint center empty-hint">分类载入中…（如果一直没有，说明后端还没重启）</p>
      </section>

      <section class="block">
        <div class="block-head">
          <h2 class="block-title">大家常问</h2>
          <span class="faint">{{ hotMixed ? "群友问得最多的" : "被点赞认可的" }}</span>
        </div>
        <div v-if="hot.length" class="hot">
          <button v-for="k in hot" :key="k.keyword" class="hot-item" type="button" @click="openKeyword(k.keyword)">
            <span class="hot-kw">{{ k.keyword }}</span>
            <span v-if="k.termEn" class="hot-en faint">{{ k.termEn }}</span>
            <span class="hot-count num">{{ num(k.votedCount || k.count) }}</span>
          </button>
        </div>
        <p v-else class="faint center empty-hint">还没有人查过什么。去群里 @ 机器人问一句吧～</p>
      </section>

      <section class="block">
        <div class="block-head">
          <h2 class="block-title">发现内容有误或缺失？</h2>
          <Button size="sm" @click="router.push({ name: 'manage' })">+ 新增 / 变更知识</Button>
        </div>
        <p class="faint">
          资料来自 enshrouded.wiki.gg。提交修订需要邀请码，通过管理员审核并建立索引后才会生效。
        </p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.library { max-width: 1000px; padding-top: var(--sp-6); padding-bottom: var(--sp-7); }

.hero { text-align: center; margin-bottom: var(--sp-6); }
.hero-title { font-size: var(--fs-3xl); margin-bottom: var(--sp-2); }
.hero-sub { font-size: var(--fs-sm); margin-bottom: var(--sp-5); }

.searchbar { display: flex; gap: var(--sp-2); max-width: 640px; margin: 0 auto; }
.search-input {
  flex: 1; background: var(--bg-shroud); border: 1px solid var(--line-strong);
  border-radius: var(--r-md); padding: 12px 16px; font-size: var(--fs-base); color: var(--ink);
}
.search-input:focus { outline: none; border-color: var(--flame); box-shadow: 0 0 0 4px var(--flame-glow); }

.center { text-align: center; }
.err { color: var(--rust); font-size: var(--fs-sm); text-align: center; }
.result-head { margin-bottom: var(--sp-4); font-size: var(--fs-sm); }
/* 检索模式标识：让用户知道这次是"懂人话的搜索"还是"只认名词的搜索" */
.mode-badge {
  margin-left: var(--sp-2); font-size: var(--fs-xs); color: var(--flame-bright);
  background: var(--flame-glow); border-radius: var(--r-pill); padding: 1px 8px;
}
.mode-badge.local { color: var(--mist); background: var(--bg-sunken); }

.crumb { display: flex; align-items: center; gap: var(--sp-3); margin-bottom: var(--sp-4); }
.link { background: none; border: 0; color: var(--ink-dim); font-size: var(--fs-sm); cursor: pointer; padding: 0; }
.link:hover { color: var(--flame); }
.crumb-cur { font-size: var(--fs-sm); color: var(--flame-bright); }
.crumb-tag { font-size: var(--fs-xs); color: var(--mist); background: var(--bg-sunken); border-radius: var(--r-pill); padding: 2px 10px; }

/* 大类卡片 */
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: var(--sp-3); }
.gcard {
  display: flex; align-items: center; gap: var(--sp-3); text-align: left;
  background: var(--bg-shroud); border: 1px solid var(--line); border-radius: var(--r-md);
  padding: var(--sp-4); cursor: pointer; transition: all var(--dur-fast) var(--ease);
}
.gcard:hover { border-color: var(--flame); transform: translateY(-2px); }
.gcard-icon { font-size: 26px; }
.gcard-body { flex: 1; min-width: 0; }
.gcard-label { font-size: var(--fs-base); color: var(--ink); }
.gcard-desc { font-size: var(--fs-xs); margin-top: 2px; }
.gcard-count { font-size: var(--fs-lg); color: var(--copper); }
/* 空的攻略组：用虚线 + 淡色，暗示「等你来填」 */
.gcard.inviting { border-style: dashed; border-color: var(--copper-dim); }
.gcard.inviting .gcard-count { font-size: var(--fs-sm); color: var(--mist); }
.gcard.inviting:hover { border-color: var(--flame); }

/* 标签云 */
.tags { display: flex; flex-wrap: wrap; gap: var(--sp-2); }
.tag-chip {
  display: inline-flex; align-items: center; gap: 6px;
  background: var(--bg-shroud); border: 1px solid var(--line); border-radius: var(--r-pill);
  padding: 5px 14px; font-size: var(--fs-xs); color: var(--ink-dim); cursor: pointer;
  transition: all var(--dur-fast) var(--ease);
}
.tag-chip:hover { border-color: var(--copper); color: var(--ink); }
.tag-n { font-size: 10px; color: var(--mist); }

/* 结果卡片 */
.cards { display: flex; flex-direction: column; gap: var(--sp-3); }
.card {
  background: var(--bg-shroud); border: 1px solid var(--line); border-radius: var(--r-md);
  padding: var(--sp-4); cursor: pointer; transition: all var(--dur-fast) var(--ease);
}
.card:hover { border-color: var(--copper); transform: translateX(3px); }
.card-title { font-size: var(--fs-base); color: var(--flame-bright); margin-bottom: 6px; }
.card-excerpt { font-size: var(--fs-xs); color: var(--ink-dim); line-height: 1.7; }
.card-foot { margin-top: var(--sp-2); display: flex; gap: 6px; flex-wrap: wrap; }
.cat { font-size: 10px; color: var(--mist); background: var(--bg-sunken); border: 1px solid var(--line); border-radius: var(--r-pill); padding: 1px 8px; }

.pager { display: flex; align-items: center; justify-content: center; gap: var(--sp-4); margin-top: var(--sp-5); font-size: var(--fs-sm); }

/* 区块 */
.block { margin-bottom: var(--sp-6); }
.block-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: var(--sp-3); gap: var(--sp-3); }
.block-title { font-size: var(--fs-lg); }

.hot { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: var(--sp-2); }
.hot-item {
  display: flex; align-items: baseline; gap: var(--sp-2);
  background: var(--bg-shroud); border: 1px solid var(--line); border-radius: var(--r-md);
  padding: 10px 14px; cursor: pointer; text-align: left; transition: all var(--dur-fast) var(--ease);
}
.hot-item:hover { border-color: var(--flame); }
.hot-item:hover .hot-kw { color: var(--flame-bright); }
.hot-kw { font-size: var(--fs-sm); color: var(--ink); flex: 1; }
.hot-en { font-size: 10px; }
.hot-count { font-size: var(--fs-xs); color: var(--mist); }
.empty-hint { padding: var(--sp-5) 0; }
</style>
