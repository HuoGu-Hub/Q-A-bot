<script setup lang="ts">
/**
 * 公开站首页 —— 落地页，**不放搜索框**。
 *
 * 搜索统一在「资料库」页。原来首页那个框只是把用户弹到 /library?q=…，
 * 并没有自己的检索能力；同一件事有两个入口，除了让首屏更挤、让人以为首页也能搜之外
 * 没有别的作用，所以 2026-09-27 连示例词一起删掉了。删的是**入口**，不是搜索本身。
 *
 * ── 版面为什么长这样（2026-11 重做）──
 * 上一版是「居中大标题 + 一句副标语 + 一张铺满宽度的轮播图」，是落地页的默认解：
 * 视线的落点从页面正中开始，左右各空一大块，读完标题要先滚过半屏图才看到"怎么用"。
 *
 * 现在把首屏做成一块**石板**（.slab，全宽、无圆角、承接页面顶上那盏灯）：
 *   · 标题左对齐 —— 中文标题居中时，每行的起止点都在跳，左对齐才有"碑面"的稳；
 *   · 标题下面直接接**索引刻痕**（资料条目 / 术语对照 / 群友提问），
 *     用等宽数字 + 发丝竖线分隔。这三个数是首屏最该先被看到的"家底"；
 *   · 主行动只有一个（去资料库），次路径（在群里 @ 我）用一行小字说明 ——
 *     两个一样重的按钮等于没有主次。
 * 轮播图**移出首屏**：它是内容，不是门面。
 */
import { computed, onMounted, ref } from 'vue'
import { renderInline } from '../renderInline'
import { publicApi, describeError } from '@shared/api/client'
import type { CarouselItem, CarouselPublicResponse, PublicStats } from '@shared/api/types'
import { num, pct } from '@shared/utils/format'
import { t } from '../useSiteText'
import Icon from '@shared/ui/Icon.vue'
import ImageCarousel from '../components/ImageCarousel.vue'

const stats = ref<PublicStats | null>(null)
const loading = ref(true)
const error = ref('')

/** 首页轮播图。空数组 = 后台还没配图，这一块整个不渲染 */
const slides = ref<CarouselItem[]>([])
/** 切换间隔；后端公开接口给了就以后端为准（后台那个「切换间隔」才真的生效） */
const slideIntervalMs = ref(4000)

/**
 * 副标语：`{kb}` 占位符替换成知识库条目数。
 * 默认值保留原来的硬编码文案 —— 后端文案拿不到时照常显示（见 useSiteText）。
 */
const tagline = computed(() =>
  t('home.tagline', '在《雾锁王国》的迷雾里，问一句就好 —— 我帮你翻遍 {kb} 条资料。', {
    kb: num(stats.value?.kbEntries ?? 0),
  }))

/**
 * 「怎么用」：**一块多行文案**，一行一条（与关于页「回答是怎么产生的」同一套编辑方式）。
 *
 * 原来是 howto_1/2/3 三块，后台三个输入框、这里写死三条 —— 同一件事两种编辑方式，
 * 加一条要改代码。2026-09-27 合并成一块。
 *
 * 默认值是**硬编码的兜底**（后端文案拿不到时页面照常有字，见 useSiteText）：
 * 内容必须与 SiteTextService 注册表里 home.howto_body 的 defaultText 一致。
 */
const HOWTO_DEFAULT = [
  '在群里 @ 我提问 —— 群里直接问，我会查资料后回答。',
  '或者去 [资料库](/library) 翻 —— 和群里回答用的是同一套 Wiki 资料。',
  '查不到就问点别的 —— 资料来自官方 Wiki，游戏更新后可能有延迟。',
].join('\n')

const howSteps = computed(() =>
  t('home.howto_body', HOWTO_DEFAULT).split('\n').map((s) => s.trim()).filter(Boolean))

/**
 * 三条路径各配一个图标。
 * ⚠️ 图标是**按位置**给的装饰，不参与语义：后台把第三条文案改掉，
 * 图标还是那个"查不到"的语义 —— 会有点错位，但不会错到误导（三个都是中性的动作图标）。
 * 多出来的行一律用 fallback。
 */
const HOW_ICONS = ['chat', 'book', 'search'] as const
const iconAt = (i: number) => HOW_ICONS[i] ?? 'spark'

/** 首屏索引刻痕。三个数分别回答"有多少资料 / 名词对不对得上 / 有多少人真的在用" */
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
 * 拉轮播图。
 *
 * <p>**拿不到就当没有**：首页的主体是标语和「怎么用」，不能因为一个图片接口挂了
 * 就让整页报错。所以这里单独 try、不写 error、失败也不影响下面任何一块。
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
  // 两个请求**并行**：轮播和统计互不依赖，串着等只会让首屏的图晚出来
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
    <!--
      首屏 = 一屏。
      ------------------------------------------------------------------
      ⚠️ 改这里之前先看这一段：上一版是「石碑（约 300px）+ 下面一条全宽轮播」，
      于是首屏的观感取决于视口高度 —— 在笔记本（可视区 600~700px）上，
      石碑只占了上半屏，轮播正好被折线**切成半张**。截图里看就是"一半一半"。

      现在把画面收进石碑的右栏：
        · 画面高度由"栏宽 ÷ 图片比例"决定，再被 ImageCarousel 的 maxHeight 兜住 ——
          它永远不可能越过折线，因为折线由整块石碑的高度决定，而石碑是一屏；
        · 石碑的高度就是"一屏"：min(100svh - 导航高, 820px)。
          820 的上限是给超高的显示器留的 —— 否则在 1440 高的屏上，首屏会是一片空场。
        · 家底（四个数）压到石碑底边做成一条数据带，用一条顶线收口。
          它既是"报家底"，也顺手把首屏的纵向空间撑满，不再是中间一大块空。
    -->
    <section class="hero slab">
      <div class="page hero-main" :class="{ 'has-media': !!slides.length }">
        <div class="hero-text">
          <p class="eyebrow">Enshrouded · 中文资料助手</p>
          <h1 class="wordmark">雾中<span class="lit">灵火</span></h1>
          <p class="tagline">{{ tagline }}</p>

          <div class="acts">
            <RouterLink class="cta" to="/library">
              <Icon name="search" :size="17" />
              去资料库查
            </RouterLink>
            <span class="alt">或在群里 <b>@ 飘雪喵</b> 直接问</span>
          </div>
          <p v-if="error" class="err faint">统计数据暂时取不到 · {{ error }}</p>
        </div>

        <!-- 回廊：画面在首屏之内，和文案并排 -->
        <div v-if="slides.length" class="hero-media">
          <ImageCarousel :items="slides" :interval-ms="slideIntervalMs" />
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
      <!-- 三条路径是**并列**的，不是步骤 —— 所以不编号。
           第一张（在群里 @ 我）是主路径，只有它带灵火标记。 -->
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
   这一块的整体高度就是"一屏"，理由见模板里的长注释。
   ⚠️ 两行 min-height 不是重复：第一行是给不认识 svh 的老浏览器兜底的。
   svh（small viewport height）取的是"地址栏还没收起时"的高度，
   手机/平板上用它才不会出现"滚一下整屏跟着跳"。 */
.hero {
  display: flex;
  flex-direction: column;
  min-height: min(calc(100vh - var(--header-h)), 820px);
  min-height: min(calc(100svh - var(--header-h)), 820px);
  padding: 0;
}
/* 正文区 flex:1 吃掉数据条以上的全部高度，内容在其中垂直居中 ——
   所以无论视口多高，正文都在"视线落点"上，而不是贴着顶。 */
.hero-main {
  flex: 1;
  /* ⚠️ width: 100% 必须写。
  .page 带的是 margin: 0 auto，而这里的外层 .hero 是**纵向 flex 容器**：
  在交叉轴（宽度）上，auto 外边距的优先级高于 align-items: stretch ——
  子项会被"收缩到内容宽度"再居中，于是整块正文只剩 775px 宽、两边空一大截。
  width: 100% 先把宽度定死，max-width(1160) 再收口，auto 外边距才回到它该干的居中。 */
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-content: center;
  align-items: center;
  gap: var(--sp-6) var(--sp-7);
  padding-top: var(--sp-6);
  padding-bottom: var(--sp-5);
}
/* 有画面时才是两栏。没有图的时候不要硬留一格空白 */
.hero-main.has-media { grid-template-columns: minmax(0, .95fr) minmax(0, 1.05fr); }
.hero-text { min-width: 0; }
.hero-media { min-width: 0; }

.eyebrow { margin: 0 0 var(--sp-3); }

.wordmark {
  /* 中文标题的自在：字号越大、字距越要收，否则字与字之间会"散开" */
  font-size: clamp(42px, 8.4vw, 68px);
  font-weight: var(--fw-semi);
  line-height: 1.02;
  letter-spacing: .02em;
  margin: 0 0 var(--sp-4);
  color: var(--ink);
  /* 刻进石头：暗色在上、微光在下。比"整行发光"安静得多，也更像碑面 */
  text-shadow: 0 1px 0 rgba(0, 0, 0, .55), 0 -1px 0 rgba(255, 255, 255, .045);
}
/* 熔金嵌字：灵火这两个字是"被照到的地方"，不是"在发光的字"。
   ⚠️ 全站只有这一处用渐变文字 —— 用第二次就变成模板了。 */
.lit {
  background: linear-gradient(176deg, var(--ember-hot) 4%, var(--ember) 46%, var(--ember-deep) 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  /* background-clip:text 之后 text-shadow 会被一起裁掉，所以光晕走 drop-shadow */
  filter: drop-shadow(0 1px 0 rgba(0, 0, 0, .5)) drop-shadow(0 0 22px rgba(242, 171, 85, .22));
}

.tagline {
  font-size: var(--fs-lg);
  line-height: 1.7;
  color: var(--ink-2);
  max-width: 34em;
  margin: 0 0 var(--sp-5);
}

.acts { display: flex; align-items: center; gap: var(--sp-5); flex-wrap: wrap; }
.cta {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  /* 44px 高：拇指友好的下限，同时也是这一页唯一的主按钮 */
  padding: 0 var(--sp-5);
  min-height: 44px;
  border-radius: var(--r-sm);
  text-decoration: none;
  background: linear-gradient(180deg, var(--ember), var(--ember-deep));
  color: var(--ink-on-ember);
  font-weight: var(--fw-semi);
  font-size: var(--fs-base);
  box-shadow: var(--bevel-shelf), var(--shadow-flame);
  transition: background var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease),
              transform var(--dur-fast) var(--ease);
}
.cta:hover {
  background: linear-gradient(180deg, var(--ember-hot), var(--ember));
  color: var(--ink-on-ember);
  box-shadow: var(--bevel-shelf), 0 0 0 1px rgba(255, 208, 138, .35), 0 8px 26px -6px var(--ember-glow);
  text-decoration: none;
}
.cta:active { transform: translateY(1px); }
.alt { font-size: var(--fs-sm); color: var(--ink-3); }
.alt b { color: var(--ink-2); font-weight: var(--fw-medium); }

/* ==================== 家底：首屏底部的数据带 ====================
   四个数平分整条宽度、贴着石碑下沿，用一条顶线收口。
   它同时解决两件事：报家底，以及把首屏的纵向空间撑满 ——
   否则正文居中之后，下半屏会是一块什么都没用的空场。 */
.hero-foot {
  /* 同上：纵向 flex 容器里的 flex 项要自己声明 width: 100% */
  width: 100%;
  padding-top: var(--sp-4);
  padding-bottom: var(--sp-5);
  border-top: 1px solid var(--hairline);
}
.index {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-4) var(--sp-5);
  margin: 0;
  padding: 0;
}
.idx { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
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

.err { margin: var(--sp-4) 0 0; font-size: var(--fs-xs); }

/* ==================== 怎么用 ====================
   首屏已经是一整屏了，这里给足上边距 —— 它是"翻过去"的第二页，不是首屏的尾巴。 */
.how-wrap { margin-top: var(--sp-8); }
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
/* 主路径（在群里 @ 我）单独点亮一盏灯 —— 一屏之内只有它 */
.how-item.primary {
  background:
    radial-gradient(120% 150% at 0% 0%, rgba(242, 171, 85, .10), transparent 58%),
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

/* 1000px 以下：两栏并排会让标题和画面互相压，退回单栏 ——
   画面移到文案下方；首屏也不再强制一屏高（窄屏"一屏"本来就装不下这些内容，
   硬撑只会把正文挤扁）。 */
@media (max-width: 1000px) {
  .hero {
    min-height: 0;
    padding: var(--sp-7) 0 var(--sp-5);
  }
  .hero-main,
  .hero-main.has-media { grid-template-columns: minmax(0, 1fr); }
  .hero-main { padding-top: 0; padding-bottom: 0; }
  .hero-media { margin-top: var(--sp-2); }
  .hero-foot { margin-top: var(--sp-6); }
  .index { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 760px) {
  .hero { padding: var(--sp-6) 0 var(--sp-5); }
  .tagline { font-size: var(--fs-base); }
  .acts { gap: var(--sp-4); }
  /* 手机上一行放不下"按钮 + 说明"，说明另起一行，不要挤 */
  .alt { width: 100%; }
  /* 2×2：四列在 390px 下每个只有 ~85px，"检索命中率"会被压成两行 */
  .index { gap: var(--sp-4) var(--sp-3); }
  .idx dd { font-size: var(--fs-lg); }
  .how-wrap { margin-top: var(--sp-7); }
  .how { grid-template-columns: 1fr; }
}
</style>
