<script setup lang="ts">
/**
 * 公开站首页 —— 落地页，不放搜索框。
 *
 * 搜索统一在「资料库」页。首页那个框只是把用户弹到 /library?q=…，并没有自己的检索能力；
 * 同一件事两个入口，除了让首屏更挤、让人以为首页也能搜之外没有别的作用。
 *
 * ── 版面 ──
 * 首屏是一块石碑：左边一句标语与唯一的主行动，右边一张画面，脚下压一条家底数据带。
 * 上一版把首屏做成 100svh 高，于是 1440×900 的屏幕下半部分是整片空场；
 * 现在高度由内容决定，最高 620px，空场没有了，画面也不会被折线切成半张。
 *
 * 轮播属于内容、不属于门面：它在首屏里只占一栏，高度由 max-height 兜住。
 */
import { computed, onMounted, ref } from 'vue'
import { renderInline } from '../renderInline'
import { publicApi, describeError } from '@shared/api/client'
import type { CarouselPublicItem, CarouselPublicResponse, PublicStats } from '@shared/api/types'
import { num, pct } from '@shared/utils/format'
import { t } from '../useSiteText'
import Icon from '@shared/ui/Icon.vue'
import Button from '@shared/ui/Button.vue'
import ImageCarousel from '../components/ImageCarousel.vue'

const stats = ref<PublicStats | null>(null)
const loading = ref(true)
const error = ref('')

/** 首页轮播图。空数组 = 后台还没配图，这一块整个不渲染 */
const slides = ref<CarouselPublicItem[]>([])
/** 切换间隔；后端公开接口给了就以后端为准 */
const slideIntervalMs = ref(4000)

/**
 * 副标语：{kb} 占位符替换成知识库条目数。
 * 默认值保留原来的硬编码文案 —— 后端文案拿不到时照常显示。
 */
const tagline = computed(() =>
  t('home.tagline', '有问题就问一句 —— 我在 {kb} 条资料里找答案。', {
    kb: num(stats.value?.kbEntries ?? 0),
  }))

/**
 * 「怎么用」：一块多行文案，一行一条。
 * 默认值是硬编码的兜底，内容必须与 SiteTextService 注册表里 home.howto_body 的 defaultText 一致。
 */
const HOWTO_DEFAULT = [
  '在群里 @ 我提问 —— 我会先检索知识库，再依据资料回答。',
  '或者去 [资料库](/library) 翻 —— 和群里回答用的是同一套资料。',
  '查不到就问点别的 —— 资料由本项目维护，更新可能有延迟。',
].join('\n')

const howSteps = computed(() =>
  t('home.howto_body', HOWTO_DEFAULT).split('\n').map((s) => s.trim()).filter(Boolean))

/**
 * 三条路径各配一个图标。
 * ⚠️ 图标是按位置给的装饰，不参与语义：后台把第三条文案改掉，图标还是那个语义。
 */
const HOW_ICONS = ['chat', 'book', 'search'] as const
const iconAt = (i: number) => HOW_ICONS[i] ?? 'spark'

/** 首屏索引：有多少资料 / 名词对不对得上 / 有多少人真的在用 */
const index = computed(() => {
  const s = stats.value
  return [
    { label: '资料条目', value: s ? num(s.kbEntries) : '—' },
    { label: '术语对照', value: s ? num(s.glossaryTerms) : '—' },
    { label: '群友提问', value: s ? num(s.questions) : '—' },
    { label: '检索命中率', value: s ? pct(s.hitRate) : '—' },
  ]
})

/**
 * 拉轮播图。拿不到就当没有：首页主体是标语与「怎么用」，
 * 不能因为一个图片接口挂了就让整页报错。
 */
async function loadCarousel() {
  try {
    const r = await publicApi.get<CarouselPublicResponse>('/carousel')
    slides.value = r.items ?? []
    if (typeof r.intervalMs === 'number' && r.intervalMs > 0) {
      slideIntervalMs.value = r.intervalMs
    }
  } catch {
    // 静默：这一块不显示就是了
  }
}

onMounted(async () => {
  // 两个请求并行：轮播和统计互不依赖，串着等只会让首屏的图晚出来
  const statsTask = (async () => {
    try {
      stats.value = await publicApi.get<PublicStats>('/stats')
    } catch (e) {
      error.value = describeError(e)
    } finally {
      loading.value = false
    }
  })()
  await Promise.all([statsTask, loadCarousel()])
})
</script>

<template>
  <div class="home">
    <section class="hero slab">
      <div class="page hero-main" :class="{ 'has-media': !!slides.length }">
        <div class="hero-text">
          <h1 class="wordmark">知识<span class="lit">问答</span></h1>
          <p class="tagline">{{ tagline }}</p>

          <div class="acts">
            <Button variant="primary" to="/library">
              <Icon name="search" :size="17" />
              去资料库查
            </Button>
          </div>
          <p v-if="error" class="err hint">统计数据暂时取不到 · {{ error }}</p>
        </div>

        <!-- 回廊：画面在首屏之内，和文案并排 -->
        <div v-if="slides.length" class="hero-media">
          <ImageCarousel :items="slides" :interval-ms="slideIntervalMs" max-height="min(40vh, 360px)" />
        </div>
      </div>

      <div class="page hero-foot">
        <dl class="index">
          <div v-for="it in index" :key="it.label" class="idx">
            <dt>{{ it.label }}</dt>
            <dd class="num">{{ it.value }}</dd>
          </div>
        </dl>
      </div>
    </section>

    <div class="page how-wrap">
      <h2 class="section-title">怎么用</h2>
      <!-- 三条路径是并列的，不是步骤 —— 所以不编号。第一张是主路径，只有它带强调标记 -->
      <ol class="how">
        <li v-for="(step, i) in howSteps" :key="i" class="how-item" :class="{ primary: i === 0 }">
          <span class="how-icon" aria-hidden="true"><Icon :name="iconAt(i)" :size="18" /></span>
          <span class="how-text" v-html="renderInline(step)" />
        </li>
      </ol>
    </div>
  </div>
</template>

<style scoped>
.home { padding-bottom: var(--sp-8); }

/* ==================== 首屏石板 ====================
   高度由内容决定，只给一个上限 —— 不强制一屏，就不会出现「下半屏一片空」。 */
.hero {
  display: flex;
  flex-direction: column;
  padding: 0;
}
.hero-main {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: center;
  gap: var(--sp-6) var(--sp-7);
  /* 垂直节奏跟着视口走：矮屏收紧、高屏放宽，但永远由内容决定高度 */
  padding-top: clamp(36px, 6.5vh, 64px);
  padding-bottom: clamp(28px, 5vh, 48px);
}
/* 有画面时才是两栏。没有图的时候不要硬留一格空白 */
.hero-main.has-media { grid-template-columns: minmax(0, 1fr) minmax(360px, 46%); }
.hero-text { min-width: 0; }
.hero-media { min-width: 0; }

.wordmark {
  /* 中文标题的自在：字号越大、字距越要收，否则字与字之间会散开 */
  font-size: clamp(40px, 5.6vw, 58px);
  font-weight: var(--fw-semi);
  line-height: 1.04;
  letter-spacing: .015em;
  margin: 0 0 var(--sp-4);
  color: var(--ink);
  /* 刻进石头：暗色在上、微光在下。比整行发光安静得多，也更像碑面 */
  text-shadow: 0 1px 0 rgba(0, 0, 0, .18);
}
/* 熔金嵌字：高亮的那几个字是「被照到的地方」，不是「在发光的字」。
   ⚠️ 全站只有这一处用渐变文字 —— 用第二次就变成模板了。 */
.lit {
  background: linear-gradient(176deg, var(--ember-hot) 4%, var(--ember) 46%, var(--ember-deep) 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  /* background-clip:text 之后 text-shadow 会被一起裁掉，所以光晕走 drop-shadow */
  filter: drop-shadow(0 0 22px var(--ember-glow));
}

.tagline {
  font-size: var(--fs-lg);
  line-height: 1.75;
  color: var(--ink-2);
  max-width: 32em;
  margin: 0 0 var(--sp-5);
}

.acts { display: flex; align-items: center; gap: var(--sp-4); flex-wrap: wrap; }
.err { margin: var(--sp-3) 0 0; }

/* ==================== 家底：首屏底部的数据带 ====================
   四个数平分整条宽度、贴着石碑下沿，用一条顶线收口。
   它既是报家底，也把首屏撑满 —— 否则正文居中之后，下半屏会是一块空场。 */
.hero-foot {
  width: 100%;
  padding-top: var(--sp-4);
  padding-bottom: var(--sp-5);
  border-top: 1px solid var(--hairline);
}
.index {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-4);
  margin: 0;
  padding: 0;
}
.idx { display: flex; flex-direction: column; gap: 2px; min-width: 0; padding-left: var(--sp-4); }
/* 用竖刻线分成四格，而不是给每格套一个框 */
.idx + .idx { border-left: 1px solid var(--hairline); }
.idx dt {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  letter-spacing: .04em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.idx dd {
  margin: 0;
  font-size: var(--fs-xl);
  line-height: 1.2;
  color: var(--ink);
  font-weight: var(--fw-medium);
}

/* ==================== 怎么用 ==================== */
.how-wrap { margin-top: var(--sp-7); }
.how {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: var(--sp-3);
}
.how-item {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-3);
  padding: var(--sp-4);
  background: var(--stone-300);
  border-radius: var(--r-md);
  box-shadow: var(--bevel-raised);
  font-size: var(--fs-base);
  line-height: 1.7;
  color: var(--ink-2);
}
/* 主路径单独点亮一盏灯 —— 一屏之内只有它 */
.how-item.primary {
  background:
    radial-gradient(120% 150% at 0% 0%, var(--veil-warm), transparent 58%),
    var(--stone-300);
}
.how-icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 34px;
  height: 34px;
  border-radius: var(--r-sm);
  background: var(--stone-void);
  box-shadow: var(--bevel-inset);
  color: var(--ink-3);
}
.how-item.primary .how-icon { color: var(--ember); }
.how-text { min-width: 0; }
.how-text :deep(a) { color: var(--ember); }

/* 1000px 以下：两栏并排会让标题和画面互相压，退回单栏 */
@media (max-width: 1000px) {
  .hero-main,
  .hero-main.has-media { grid-template-columns: minmax(0, 1fr); }
  .hero-main { padding-top: var(--sp-6); padding-bottom: var(--sp-5); }
  .hero-media { margin-top: var(--sp-2); }
  .index { grid-template-columns: repeat(2, minmax(0, 1fr)); row-gap: var(--sp-4); }
  .idx { padding-left: 0; }
  .idx + .idx { border-left: 0; }
  .idx:nth-child(even) { border-left: 1px solid var(--hairline); padding-left: var(--sp-4); }
}

@media (max-width: 760px) {
  .hero-main { padding-top: var(--sp-5); padding-bottom: var(--sp-4); gap: var(--sp-5); }
  .wordmark { font-size: clamp(36px, 11vw, 46px); margin-bottom: var(--sp-3); }
  .tagline { font-size: var(--fs-base); margin-bottom: var(--sp-4); }
  .acts { gap: var(--sp-3); }
  .how-wrap { margin-top: var(--sp-6); }
  .how { grid-template-columns: 1fr; }
  .idx dd { font-size: var(--fs-lg); }
}
</style>
