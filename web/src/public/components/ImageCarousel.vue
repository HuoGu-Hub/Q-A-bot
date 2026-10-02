<script setup lang="ts">
/**
 * 公开站首页的图片轮播。
 *
 * 几个刻意为之的地方：
 *   ① **只有一张时不转、也不显示控件** —— 那就是一张静态配图，给一张图配箭头和
 *      圆点只会让人以为还有别的。
 *   ② **容器比例取第一张**，不是每张各用各的：切换时容器高度必须稳定，
 *      否则整页内容会跟着上下跳。尺寸为 0（WebP 读不出来）时退回 16:9。
 *   ③ 图用 object-fit: contain + 凹槽底：比例不一致时留的是**看得见的黑边**，
 *      而不是把截图边缘悄悄裁掉（攻略图边缘常有文字，裁掉就没了）。
 *   ④ 三种情况都停自动播放：鼠标悬停、页面切到后台、组件滚出视口。
 *   ⑤ 手动切换后**重新计时** —— 刚点完就立刻跳走是最招人烦的手感。
 *   ⑥ 尊重 prefers-reduced-motion：不自动播、切换不加过渡。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { CarouselItem } from '@shared/api/types'

const props = withDefaults(defineProps<{
  items: CarouselItem[]
  /** 自动切换间隔（毫秒）。<= 0 = 不自动播放 */
  intervalMs?: number
}>(), { intervalMs: 4000 })

/** 间隔下限：后台要是填成 200ms，那是闪烁不是轮播 */
const MIN_INTERVAL_MS = 1000
/** 尺寸未知时的兜底比例（WebP 读不出宽高就走这里） */
const FALLBACK_RATIO = '16 / 9'

const root = ref<HTMLElement | null>(null)
const idx = ref(0)

const interval = computed(() => (props.intervalMs > 0 ? Math.max(MIN_INTERVAL_MS, props.intervalMs) : 0))
const count = computed(() => props.items.length)
/** 只有一张时就是个静态图：不转、不显示箭头和圆点 */
const multi = computed(() => count.value > 1)

/**
 * 容器比例。取第一张而不是当前这张 —— 见文件头 ②。
 * ⚠️ width/height 为 0 时**不能**拿去做除法：会得到 0 或 NaN，容器直接塌成一条缝。
 */
const frameRatio = computed(() => {
  const it = props.items[0]
  if (!it || !it.width || !it.height) return FALLBACK_RATIO
  return it.width + ' / ' + it.height
})

const altOf = (it: CarouselItem) => it.caption || '站点配图'

// ==================== 自动播放 ====================
const hovered = ref(false)
/** 组件是否在视口里（首页很长，滚下去之后没必要还在转） */
const onScreen = ref(true)
const pageVisible = ref(true)
/** prefers-reduced-motion: reduce */
const reduceMotion = ref(false)

/** 四种刹车任一成立就停：只有一张 / 间隔<=0 / 悬停 / 不可见 / 减少了动效 */
const canAuto = computed(() =>
  multi.value && interval.value > 0
  && !hovered.value && onScreen.value && pageVisible.value && !reduceMotion.value)

let timer: number | undefined
let media: MediaQueryList | null = null
let io: IntersectionObserver | null = null

function stopTimer() {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

/** 重新排一次计时。**每次手动切换都要调它** —— 否则刚点完就立刻跳走 */
function schedule() {
  stopTimer()
  if (!canAuto.value) return
  timer = window.setInterval(autoNext, interval.value)
}

/** 自动走一步：只换图，不动计时器（它本来就在跑） */
function autoNext() {
  if (count.value > 0) idx.value = (idx.value + 1) % count.value
}

/** 手动切到第 i 张，并重新计时 */
function jump(i: number) {
  if (count.value <= 0) return
  idx.value = ((i % count.value) + count.value) % count.value
  schedule()
}

function shift(delta: number) {
  jump(idx.value + delta)
}

function onReduceChange(e: MediaQueryListEvent) {
  reduceMotion.value = e.matches
}
/** 切到别的标签页时停 —— 后台自动播放没人看，纯浪费 */
function onVisibilityChange() {
  pageVisible.value = !document.hidden
}

watch(canAuto, schedule)
watch(count, () => { if (idx.value >= count.value) idx.value = 0 })
watch(root, attachObserver)

onMounted(() => {
  media = window.matchMedia('(prefers-reduced-motion: reduce)')
  reduceMotion.value = media.matches
  media.addEventListener('change', onReduceChange)

  pageVisible.value = !document.hidden
  document.addEventListener('visibilitychange', onVisibilityChange)

  attachObserver()
  schedule()
})

/**
 * 挂视口观察。
 *
 * <p>单独抽出来是因为 root 可能**后出现**（items 是异步来的、上面还有 v-if）——
 * 只写一个 onMounted 的话，那种情况下永远挂不上观察器，滚出视口还在后台转。
 */
function attachObserver() {
  io?.disconnect()
  io = null
  if (typeof IntersectionObserver === 'undefined') return
  const el = root.value
  if (!el) return
  io = new IntersectionObserver(
    (entries) => { onScreen.value = entries[0]?.isIntersecting ?? true },
    { threshold: 0.2 },
  )
  io.observe(el)
}

onBeforeUnmount(() => {
  stopTimer()
  io?.disconnect()
  io = null
  media?.removeEventListener('change', onReduceChange)
  media = null
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <!-- 一张都没有就什么都不渲染：空的框和空的外边距只会让页面莫名多一段空白 -->
  <figure
    v-if="count > 0"
    ref="root"
    class="carousel"
    role="region"
    aria-label="站点配图"
    @mouseenter="hovered = true"
    @mouseleave="hovered = false"
  >
    <div class="stage">
      <div class="frame" :style="{ aspectRatio: frameRatio }">
        <!--
          用 component :is 而不是写两个分支：有 link 才是 <a>，没有就是 <div>。
          两边的插槽内容一模一样，写两遍必然会漂。
        -->
        <component
          :is="it.link ? 'a' : 'div'"
          v-for="(it, i) in items"
          :key="it.id"
          class="slide"
          :class="{ on: i === idx }"
          :href="it.link || undefined"
          :target="it.link ? '_blank' : undefined"
          :rel="it.link ? 'noopener' : undefined"
          :aria-hidden="i !== idx"
          :tabindex="i === idx || !it.link ? undefined : -1"
        >
          <img
            class="img"
            :src="it.imageUrl"
            :alt="altOf(it)"
            :loading="i === 0 ? 'eager' : 'lazy'"
            decoding="async"
          >
          <span v-if="it.caption" class="cap">{{ it.caption }}</span>
        </component>
      </div>

      <template v-if="multi">
        <button type="button" class="nav prev" aria-label="上一张" @click="shift(-1)">‹</button>
        <button type="button" class="nav next" aria-label="下一张" @click="shift(1)">›</button>
      </template>
    </div>

    <div v-if="multi" class="dots">
      <button
        v-for="(it, i) in items"
        :key="it.id"
        type="button"
        class="dot"
        :class="{ on: i === idx }"
        :aria-label="'第 ' + (i + 1) + ' 张'"
        :aria-current="i === idx ? 'true' : undefined"
        @click="jump(i)"
      />
    </div>
  </figure>
</template>

<style scoped>
/* figure 自带外边距，会把这页的节奏顶歪 */
.carousel { margin: 0; display: flex; flex-direction: column; gap: var(--sp-2); }

.stage { position: relative; }

/* 凹槽底：图片比例与容器不一致时，留的是看得见的留白而不是裁掉内容（见文件头 ③） */
.frame {
  position: relative;
  overflow: hidden;
  border-radius: var(--r-md);
  border: 1px solid var(--edge-soft);
  background: var(--surface-inset);
  box-shadow: var(--shadow-inset);
}

/* 叠着放：淡入淡出切，不动布局 */
.slide {
  position: absolute;
  inset: 0;
  display: block;
  opacity: 0;
  pointer-events: none;
  transition: opacity var(--dur-slow) var(--ease);
}
.slide.on { opacity: 1; pointer-events: auto; }

.img { width: 100%; height: 100%; object-fit: contain; display: block; }

/* 说明文字压在图底：垫一层由透明到深的渐变，浅色截图上才读得清 */
.cap {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: var(--sp-2) var(--sp-4);
  font-size: var(--fs-sm);
  color: var(--ink);
  text-align: center;
  background: linear-gradient(transparent, rgba(0, 0, 0, .72));
  pointer-events: none;
}

.nav {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  font: inherit;
  font-size: var(--fs-xl);
  line-height: 1;
  color: var(--ink);
  background: rgba(0, 0, 0, .45);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-pill);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--dur) var(--ease), background var(--dur-fast) var(--ease),
              border-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.prev { left: var(--sp-3); }
.next { right: var(--sp-3); }
/* 平时收起不挡图，鼠标进来（或键盘聚焦）才出现 */
.stage:hover .nav, .nav:focus-visible { opacity: 1; }
.nav:hover { background: var(--flame-veil); border-color: var(--flame); color: var(--flame-bright); }

/* 圆点放在画面**下面**一排：压在图上会和说明文字抢位置 */
.dots { display: flex; justify-content: center; gap: var(--sp-2); }
.dot {
  width: 8px;
  height: 8px;
  padding: 0;
  border-radius: var(--r-pill);
  border: 1px solid var(--line-strong);
  background: var(--line-strong);
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease),
              transform var(--dur-fast) var(--ease);
}
.dot:hover { border-color: var(--flame); background: var(--flame-veil); }
.dot.on { background: var(--flame); border-color: var(--flame); transform: scale(1.25); }

/* 触摸设备没有 hover：箭头必须常驻，否则等于没有（圆点在画面下面，仍然能切） */
@media (hover: none) {
  .nav { opacity: .92; }
}

@media (max-width: 640px) {
  /* 手机上箭头会占掉本就不多的画面宽度，做小一点 */
  .nav { width: 28px; height: 28px; font-size: var(--fs-lg); }
  .cap { font-size: var(--fs-xs); padding: var(--sp-1) var(--sp-3); }
}
</style>
