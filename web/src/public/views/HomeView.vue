<script setup lang="ts">
/**
 * 公开站首页 —— 落地页，**不放搜索框**。
 *
 * 搜索统一在「资料库」页（导航第二项）。原来首页那个框只是把用户弹到
 * /library?q=…，并没有自己的检索能力；同一件事有两个入口，除了让首屏更挤、
 * 让人以为首页也能搜之外没有别的作用，所以 2026-09-27 连示例词一起删掉了。
 *
 * 删的是**入口**，不是搜索本身：/library 的搜索与后端 /api/public/kb/search 一行没动。
 */
import { computed, onMounted, ref } from 'vue'
import { renderInline } from '../renderInline'
import { publicApi } from '@shared/api/client'
import { describeError } from '@shared/api/client'
import type { CarouselItem, CarouselPublicResponse, PublicStats } from '@shared/api/types'
import { num } from '@shared/utils/format'
import { t } from '../useSiteText'
import Panel from '@shared/ui/Panel.vue'
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
  <div class="page home">
    <section class="hero">
      <h1 class="hero-title">
        雾中<span class="flame">灵火</span>
      </h1>
      <p class="hero-sub">{{ tagline }}</p>
    </section>

    <!-- 没有图时**整块不渲染**：留个空壳会平白多出一段空白。
         有图时给一点下边距，和「怎么用」的小标题隔开。 -->
    <ImageCarousel
      v-if="slides.length"
      class="hero-carousel"
      :items="slides"
      :interval-ms="slideIntervalMs"
    />

    <section class="section-title"><span v-html="renderInline(t('home.howto_title', '怎么用'))" /></section>
    <Panel corners>
      <!-- 「怎么用」是一块多行文案，一行一条（后台也是一个多行框）：
           行内标记由 renderInline 渲染，所以「[资料库](/library)」这类链接照样是可点的。
           ⚠️ 样式表没动（.howto strong 规则留着）。 -->
      <ol class="howto">
        <li v-for="(step, i) in howSteps" :key="i">
          <span v-html="renderInline(step)" />
        </li>
      </ol>
    </Panel>
  </div>
</template>

<style scoped>
.home { padding-top: var(--sp-7); padding-bottom: var(--sp-7); }

.hero { text-align: center; max-width: 720px; margin: 0 auto var(--sp-7); }
.hero-title {
  font-size: clamp(30px, 6vw, 46px);
  letter-spacing: .12em;
  margin-bottom: var(--sp-3);
}
.flame {
  color: var(--flame);
  text-shadow: 0 0 26px var(--flame-glow);
}
.hero-sub { color: var(--ink-dim); font-size: var(--fs-md); margin-bottom: var(--sp-5); }

/* 轮播自己不带外边距（组件是通用的），间距由使用方给 */
.hero-carousel { margin-bottom: var(--sp-4); }

.howto { margin: 0; padding-left: 1.2em; }
.howto li { margin-bottom: var(--sp-2); }
.howto strong { color: var(--ink); font-weight: 600; }
</style>
