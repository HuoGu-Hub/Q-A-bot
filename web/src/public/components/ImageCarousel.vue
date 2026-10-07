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
 *      ⚠️ 悬停区是**画面本身**（.stage），不是整块组件 —— 组件比画面宽（画面按高度
 *      上限反推宽度并居中），把悬停挂在最外层的话，鼠标扫过画面左右那两片空白
 *      也算"悬停"，手一挥就暂停。
 *   ⑤ 悬停是**冻结**、不是清零：暂停时把这一轮已经走过的时间扣掉，移开接着走完
 *      剩下的。只有手动切换（箭头 / 圆点）才从头计时 —— 刚点完就立刻跳走是最招
 *      人烦的手感。
 *   ⑤' 有一个**显式的暂停按钮**（右下角）。自动播放本身没问题，但"只给暂停、
 *      不给播放"不算数：WCAG 2.2.2 要求自动更新的内容必须能被用户停下来 ——
 *      悬停暂停只有鼠标用户享受得到，手机上根本没有 hover，键盘用户也得先知道
 *      鼠标停在画面上才停。所以这里给一个看得见、点得到、读屏念得出的开关。
 *   ⑥ prefers-reduced-motion **只关掉"运动"，不关掉轮播本身**。
 *      ⚠️ 但注意：这一档**不是少数人的兜底**。Windows 上关掉「动画效果」的人不少
 *      （开发机实测就是关的），他们看到的是**唯一**一版效果 ——
 *      所以它的观感要和主线一样认真做（见文件末尾 .no-anim 那段）。
 *      系统里关了动画的机器不少（Windows「显示动画」关掉就是 reduce），
 *      把它当成"不自动播"，表现就是"这张图怎么一直不动"；而轮换内容不是动画。
 *      这类机器**保留交叉淡化**（换图照样看得见），只去掉缩放 / 位移。
 *   ⑦ **画面有高度上限**（maxHeight）：首页上半屏还有标题，图按原始比例铺满宽度时，
 *      一张 2560×1440 的图在 1148 宽的栏里就是 646px 高 —— 标题 + 图超过一屏，
 *      得滚动才看得完一张。上限由"高度"反推"宽度"，比例不变 → 不裁也不留黑边。
 *   ⑧ **观感：玻璃 + 悬浮**（首页是门面）。玻璃只上在**外壳与控件**上，画面本身是
 *      主角：外壳 = 半透明磨砂底 + 顶部内高光 + 三层阴影，图看着像嵌在玻璃下面；
 *      壳背后再垫一层"当前图自身模糊放大"的**环境光**，光从画面边缘溢出来；
 *      说明文字与左右箭头改成玻璃药丸；圆点兼作**自动播放进度条**。
 *
 *   ⑨ **换图：软边幕布揭幕（"雾散"）**，不是交叉淡化。
 *      走过的弯路：先是 320ms 纯透明度（看不出换了），改成 900ms 交叉淡化后又被指出
 *      "太突兀"。问题不在时长 —— 交叉淡化意味着两张照片**同时**半透明叠在一起，
 *      中间那 400ms 是一幅谁也不是的混影；两张图色彩差别越大，那一下越像"闪"。
 *
 *      现在换成：新图被一块 3 倍宽的软边幕布从左侧揭出来（mask-position 100% → 0%）。
 *      任意一个瞬间，画面里要么是旧图、要么是新图，交界处是一道柔和的过渡带 ——
 *      眼睛看到的是"雾散开"，而不是"两个画面糊在一起"。
 *      含义上也对得上：这个站的世界观里，光是把迷雾推开的那个东西。
 *
 *      方向**跟着翻页方向走**：下一张 / 自动轮播时新图从**右侧**进来（幕布往左收），
 *      上一张时从**左侧**进来。语义要和箭头一致 ——「›」是往后翻，新图就该从右边推出来。
 *      实现上要额外做一步：两个方向用的是镜像渐变，而"全遮/全露"恰好是同一对位置值，
 *      方向和索引在同一帧一起改会让入场那张的起止值撞成同一个数（浏览器认为没变化，
 *      过渡直接不创建）。所以方向必须**早一帧**落定，详见 setIndex 的注释。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { CarouselItem } from '@shared/api/types'
import Icon from '@shared/ui/Icon.vue'

const props = withDefaults(defineProps<{
  items: CarouselItem[]
  /** 自动切换间隔（毫秒）。<= 0 = 不自动播放 */
  intervalMs?: number
  /** 画面高度上限（CSS 长度）。默认按"标题区 + 这一块刚好一屏"留（见文件头 ⑦） */
  maxHeight?: string
}>(), { intervalMs: 4000, maxHeight: 'min(46vh, 440px)' })

/** 间隔下限：后台要是填成 200ms，那是闪烁不是轮播 */
const MIN_INTERVAL_MS = 1000
/** 尺寸未知时的兜底比例（WebP 读不出宽高就走这里）。数字形式：样式里要拿它做乘法 */
const FALLBACK_RATIO = 16 / 9

const root = ref<HTMLElement | null>(null)
const idx = ref(0)
/** 手动切换的计数：让进度条也从头走一遍（见 jump / ⑤） */
const runSeq = ref(0)

/**
 * 幕布扫掠方向。
 *   false = 往后（下一张 / 自动轮播）：新图**从右侧**进来
 *   true  = 往前（上一张）：新图**从左侧**进来
 * 语义要和箭头一致 —— 「›」是往后翻，新图就该从右边推出来。
 */
const rev = ref(false)

/** 方向刚翻过来的那一帧：临时关掉过渡，见 setIndex 的注释 */
const dirLock = ref(false)

const interval = computed(() => (props.intervalMs > 0 ? Math.max(MIN_INTERVAL_MS, props.intervalMs) : 0))

/**
 * 换图（幕布扫过）的时长。
 *
 * <p>**必须跟间隔挂钩，不能写死**：后台的「切换间隔」最小可以填 1000ms，
 * 而扫掠本身要 1s 才看得清 —— 写死的话，间隔一调小就会出现"上一轮还没扫完、
 * 下一轮就开始了"，两张图叠着换第三张。
 *
 * <p>取值：间隔的 30%，夹在 [420ms, 1000ms] 之间。
 * 默认间隔 4000ms → 1000ms；间隔 2000ms → 600ms；间隔 1000ms → 420ms（会明显变快，但不会打架）。
 */
const wipeMs = computed(() => Math.min(1000, Math.max(420, Math.round(interval.value * 0.3))))
const count = computed(() => props.items.length)
/** 只有一张时就是个静态图：不转、不显示箭头和圆点 */
const multi = computed(() => count.value > 1)

/**
 * 容器比例（数字）。取第一张而不是当前这张 —— 见文件头 ②。
 * ⚠️ width/height 为 0 时**不能**拿去做除法：会得到 0 或 NaN，容器直接塌成一条缝。
 * 用数字不用 '16 / 9'：下面要把「高度上限 × 比例」算成宽度（见文件头 ⑦）。
 */
const frameRatio = computed(() => {
  const it = props.items[0]
  if (!it || !it.width || !it.height) return FALLBACK_RATIO
  return it.width / it.height
})

const altOf = (it: CarouselItem) => it.caption || '站点配图'

// ==================== 自动播放 ====================
const hovered = ref(false)
/** 组件是否在视口里（首页很长，滚下去之后没必要还在转） */
const onScreen = ref(true)
/** 用户**显式**按了暂停。和"悬停/离屏"分开存：那两个是临时的，这个是人的决定 */
const userPaused = ref(false)
const pageVisible = ref(true)
/** prefers-reduced-motion: reduce */
const reduceMotion = ref(false)

/** 会自己轮换吗（只有一张 / 间隔 <= 0 时"下一张"这件事不存在，也就不该有进度条） */
const auto = computed(() => multi.value && interval.value > 0)

/**
 * 再叠三道刹车：悬停 / 滚出视口 / 页面切到后台。
 * ⚠️ reduced-motion 不在其中 —— 它只管过渡，不管轮换（见文件头 ⑥）。
 */
const canAuto = computed(() =>
  auto.value && !userPaused.value && !hovered.value && onScreen.value && pageVisible.value)

let timer: number | undefined
let media: MediaQueryList | null = null
let io: IntersectionObserver | null = null

/**
 * 本轮倒计时。用"剩余时间"而不是"重新起一个 interval"（见文件头 ⑤）：
 * 悬停暂停、移开接着走完剩下的，而不是从整个间隔重新数。
 */
let remaining = 0
/** 本轮是什么时候开始的（暂停时用它算已经走掉多少） */
let runStart = 0

function stopTimer() {
  if (timer !== undefined) {
    window.clearTimeout(timer)
    timer = undefined
  }
}

/** 从头开始一轮：首次挂载、自动翻到下一张、手动切换之后都走这里 */
function restartTimer() {
  stopTimer()
  remaining = interval.value
  if (!canAuto.value) return
  runStart = Date.now()
  timer = window.setTimeout(tick, remaining)
}

/** 暂停：扣掉这一轮**已经走掉**的时间，剩下的留着（冻结，不是清零） */
function freezeTimer() {
  if (timer === undefined) return
  stopTimer()
  remaining = Math.max(0, remaining - (Date.now() - runStart))
}

/** 恢复：接着剩下的时间走。剩到 0 时给一个完整间隔（也让进度条重来一遍） */
function thawTimer() {
  if (!canAuto.value || timer !== undefined) return
  if (remaining <= 0) {
    remaining = interval.value
    runSeq.value++
  }
  runStart = Date.now()
  timer = window.setTimeout(tick, remaining)
}

/** 到点了：翻一张，再从头计时。自动轮播永远是"往后"，方向固定 */
function tick() {
  stopTimer()
  remaining = 0
  setIndex(idx.value + 1, false)
}

/**
 * 换到第 i 张，并指定这一轮幕布往哪边扫（见 ⑤ 与文件头 ⑨）。
 *
 * <p>⚠️ 方向必须比索引**早一帧**落定，这是整套里最容易踩的坑：
 * 两个方向用的是镜像渐变，而"全遮"和"全露"恰好落在同一对位置值上
 * （默认方向：遮 0% / 露 100%；上一张：遮 100% / 露 0%）。
 * 如果方向和新索引在同一帧里改，正在入场那一张的**起始值**（按旧方向的静止位算）
 * 会同**目标值**（按新方向的露出位算）撞成同一个数 ——
 * 浏览器判定"这个属性没变"，于是过渡压根不会创建，表现就是又退回硬切。
 *
 * <p>所以方向一变：先只改方向，并用 .dir-lock 把过渡关掉让它无动画落定一帧，
 * 下一帧再换索引。这一帧**看不出任何变化** —— 两个方向在"全遮 / 全露"下的
 * 渲染结果都是纯透明 / 完全不透明（只是幕布停的方位不同），所以不会闪。
 */
function setIndex(i: number, backward: boolean) {
  if (count.value <= 0) return
  const next = ((i % count.value) + count.value) % count.value

  if (backward !== rev.value) {
    rev.value = backward
    dirLock.value = true
    nextTick(() => {
      // 两个 rAF：第一个等 Vue 把类名提交进样式，第二个等这一帧真的画出来
      requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          idx.value = next
          runSeq.value++
          restartTimer()
          dirLock.value = false
        })
      })
    })
    return
  }

  idx.value = next
  runSeq.value++
  restartTimer()
}

/** 手动切到第 i 张：**从头**计时（见 ⑤），进度条也跟着重来 */
function jump(i: number) {
  // 点圆点跳转时"往前还是往后"按目标在当前之前来判断
  setIndex(i, i < idx.value)
}

function shift(delta: number) {
  setIndex(idx.value + delta, delta < 0)
}

function onReduceChange(e: MediaQueryListEvent) {
  reduceMotion.value = e.matches
}
/** 切到别的标签页时停 —— 后台自动播放没人看，纯浪费 */
function onVisibilityChange() {
  pageVisible.value = !document.hidden
}

watch(canAuto, (on) => { if (on) thawTimer(); else freezeTimer() })
watch(count, () => { if (idx.value >= count.value) idx.value = 0 })
watch(root, attachObserver)

onMounted(() => {
  media = window.matchMedia('(prefers-reduced-motion: reduce)')
  reduceMotion.value = media.matches
  media.addEventListener('change', onReduceChange)

  pageVisible.value = !document.hidden
  document.addEventListener('visibilitychange', onVisibilityChange)

  attachObserver()
  restartTimer()
  scheduleWarm()
})

// 图是异步来的（items 后到），到货之后重新排一次预热
watch(count, () => { if (count.value > 0) scheduleWarm() })

/**
 * 预热后面几张图。
 *
 * <p>实测：刚打开页面就点「下一张」，遮罩要 **220ms** 才开始动 ——
 * 那一下不是在等 CSS，是在等第二张图解码完、上传成纹理才能画。
 * 提前 decode() 之后降到 **79ms**：点击到画面开始变化的间隔从 0.22s 收到 0.08s，
 * 也就是"点了没反应"那一下没有了。
 *
 * <p><b>为什么不直接把 loading 改成 eager</b>：后台允许单张图到 12MB，
 * 一上来就并发拉满会把首屏的带宽抢掉 —— 而首屏真正必需的只有第一张。
 * 所以放到**首屏稳定之后的空闲时间**再解码：用户点第一下（通常在 1s 之后）时
 * 图已经就位，首屏该多快还是多快。
 *
 * <p>decode() 会连带触发懒加载；失败也无所谓，真要显示时浏览器还会再试一次。
 */
function warmImages() {
  const imgs = root.value?.querySelectorAll<HTMLImageElement>('.img')
  if (!imgs) return
  for (const im of imgs) void im.decode?.().catch(() => { /* 预热失败不是错误 */ })
}

/** 空闲时预热。没有 requestIdleCallback 的浏览器退回一个短延时 */
let warmHandle: number | undefined
let warmIsIdle = false
function scheduleWarm() {
  cancelWarm()
  const idle = (window as unknown as { requestIdleCallback?: (cb: () => void, o?: { timeout: number }) => number }).requestIdleCallback
  if (typeof idle === 'function') {
    warmIsIdle = true
    warmHandle = idle(warmImages, { timeout: 1500 })
  } else {
    warmIsIdle = false
    warmHandle = window.setTimeout(warmImages, 800)
  }
}
function cancelWarm() {
  if (warmHandle === undefined) return
  if (warmIsIdle) {
    (window as unknown as { cancelIdleCallback?: (h: number) => void }).cancelIdleCallback?.(warmHandle)
  } else {
    window.clearTimeout(warmHandle)
  }
  warmHandle = undefined
}

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
  cancelWarm()
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
    :class="{ 'no-anim': reduceMotion, paused: !canAuto, rev, 'dir-lock': dirLock }"
    :style="{ '--wipe': wipeMs + 'ms' }"
    role="region"
    aria-label="站点配图"
  >
    <!--
      ⚠️ 悬停挂在**画面**上，不是挂在外层 figure 上：figure 是整栏宽（1148），
      画面只有 864 且居中，挂外层时鼠标扫过左右空白也会暂停，手感像"鼠标一动就重来"。
    -->
    <div
      class="stage"
      :style="{ '--ratio': frameRatio, '--max-h': maxHeight }"
      @mouseenter="hovered = true"
      @mouseleave="hovered = false"
    >
      <!--
        环境光：把每张图自身模糊放大后垫在画面背后（见文件头 ⑧）。
        不需要额外的取色逻辑 —— 图换了，光晕的颜色自己就跟着换。
        z-index:-1 让它落在画面**下面**，同时仍留在 .stage 这个层叠上下文里。
      -->
      <div class="ambient" aria-hidden="true">
        <span
          v-for="(it, i) in items"
          :key="'halo-' + it.id"
          class="halo"
          :class="{ on: i === idx }"
          :style="{ backgroundImage: 'url(' + it.imageUrl + ')' }"
        />
      </div>

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
          <!--
            ⚠️ 带上 width/height：容器已经用 aspect-ratio 兜住了比例，这两个属性
            再给浏览器一个"图还没到、位置先占好"的确定性依据（CLS 直接归零）。
            后端读不出尺寸时是 0 —— 传 0 会让浏览器按 0 宽预留，所以用 || undefined
            退回"由 CSS 决定"。
          -->
          <img
            class="img"
            :src="it.imageUrl"
            :alt="altOf(it)"
            :width="it.width || undefined"
            :height="it.height || undefined"
            :loading="i === 0 ? 'eager' : 'lazy'"
            decoding="async"
          >
          <span v-if="it.caption" class="cap">{{ it.caption }}</span>
        </component>

        <!-- 玻璃罩：顶上那道光 + 底部压暗。⚠️ 必须不吃事件，否则图上那层链接点不动 -->
        <span class="sheen" aria-hidden="true" />
      </div>

      <template v-if="multi">
        <button type="button" class="nav prev" aria-label="上一张" @click="shift(-1)">‹</button>
        <button type="button" class="nav next" aria-label="下一张" @click="shift(1)">›</button>
      </template>

      <!-- 显式的播放/暂停开关：WCAG 2.2.2 要求自动轮换的内容可被停下（见文件头 ⑤'） -->
      <button
        v-if="auto"
        type="button"
        class="toggle"
        :aria-pressed="userPaused"
        :aria-label="userPaused ? '继续自动播放' : '暂停自动播放'"
        :title="userPaused ? '继续自动播放' : '暂停自动播放'"
        @click="userPaused = !userPaused"
      >
        <Icon :name="userPaused ? 'play' : 'pause'" :size="15" />
      </button>
    </div>

    <!--
      页码兼进度：当前那张拉长成胶囊，里面走一条灵火进度 = 还有多久换下一张。
      ⚠️ 进度条是 CSS 动画，不自己再起一个 JS 计时器：
        · 换图 / 手动切换 → :key 换一个新元素，动画从头（restartTimer 也是从头）
        · 悬停暂停 → 不重建，只靠 .paused 把 animation-play-state 冻住，
          和 freezeTimer 扣掉的那段时间对齐（见 ⑤）
    -->
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
      >
        <span
          v-if="i === idx && auto"
          :key="'fill-' + idx + '-' + runSeq"
          class="fill"
          aria-hidden="true"
          :style="{ '--dot-dur': interval + 'ms' }"
        />
      </button>
    </div>
  </figure>
</template>

<style scoped>
/* figure 自带外边距，会把这页的节奏顶歪 */
.carousel {
  /* 换图时长集中在这里：进度条、环境光、落定动画都跟着它走 */
  /* 兜底值；实际时长由 JS 按间隔算好、写在行内样式上（见 wipeMs）。
     ⚠️ 这个数别再凭感觉调，它是有实测的：
     在页面里按 rAF 采 .slide.on 的 mask-position，取软边真正跨过画面的区间。
        v1 640ms + cubic-bezier(.32,.3,.22,.95)：遮罩 54ms 开始动、420ms 到位，
           **可见扫掠只有 325ms** —— 换一整张图只用三分之一秒，眼睛来不及看见软边，
           剩下的只有"闪一下"。病根在缓动：那条曲线**前 50% 的时间走完 82% 的距离**，
           软边被压在最前面一小段里冲过去。
        v2（现在）：接近匀速的浅 S 曲线，可见扫掠约占时长的 72% →
           1000ms × 0.72 ≈ **720ms**，这才是"能看清一道雾边扫过整幅画"的量级。
     另外用逐列像素比对确认过方向：新图确实从**左**往右揭开（别在缩略图上凭眼睛判断，
     两张照片缩到 360px 宽时很容易看反）。 */
  --wipe: 1000ms;
  /* 接近匀速、两端各留一点软：拉幕布的手感。
     ⚠️ 别用站里通用的 --ease（强 ease-out，前 50% 走完 82%），
     也别用强 ease-in（开头会留出 200ms"什么都没发生"，看着像卡了）。 */
  --ease-wipe: cubic-bezier(.4, .2, .6, .8);
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

/*
  画面外壳。
  宽度 = min(容器宽, 高度上限 × 比例) —— 由高度上限**反推**宽度（见文件头 ⑦）：
  比例不变，所以图既不会被裁，也不会在左右留下黑边；比容器更宽的图（比例大于
  容器比例）依旧是"铺满宽度、按比例决定高度"，和以前完全一样。
*/
.stage {
  position: relative;
  /* 自成一个层叠上下文：环境光用 z-index:-1 躲在画面后面，出不了这一层 */
  z-index: 1;
  width: min(100%, calc(var(--max-h, 60vh) * var(--ratio, 1.7778)));
  margin-inline: auto;
}

/* ==================== 环境光 ====================
   当前图的模糊放大版，垫在画面背后往外溢一圈（见文件头 ⑧）。
   用百分比而不是固定 px：手机上画面只有 ~350 宽，固定 -34px 会顶出容器。 */
/*
  ⚠️ 环境光会溢到画面之外（那正是它的作用），但**不能把页面撑出一条横向滚动条**。
  实测：390px 宽的 iPhone 上，"inset: -8% -5%" 加 ".halo.on 的 1.06 缩放" 一起，
  把文档宽度顶到 405 —— 整页可以左右晃，手机上这是最廉价的一种破绽。
  试过用 overflow: clip 在 .carousel 上裁：overflow-clip-margin 一旦 > 0，
  溢出的那部分又会被算回可滚动区域，等于没裁；而 margin: 0 会在光晕外缘砍出一条硬边。
  所以正确的做法是**在窄屏上把外溢量收进页边距里**（见文件末尾的 @media）。
*/
.ambient {
  position: absolute;
  /* ⚠️ 横向外溢量必须写 0。
  它原来是 -5%：外溢量随栏宽**线性增长**，而页边距是固定 24px ——
  画面越宽越容易越界。实测 1024px 视口下（两栏、右栏约 460 宽）
  光晕右缘到 1040，整页出现 16px 的横向滚动。
  纵向留着 -8%：上下没有"页边距"这回事，光晕往下淌是它该有的样子。 */
  inset: -8% 0;
  z-index: -1;
  pointer-events: none;
}
.halo {
  position: absolute;
  inset: 0;
  border-radius: 24px;
  background-position: center;
  background-size: cover;
  filter: blur(48px) saturate(1.4);
  opacity: 0;
  transform: scale(.97);
  /* 旧的那层**快收**（360ms）：两层模糊色同时停在高不透明度上，
     叠出来的中间色会明显偏亮、发灰 —— 快收掉一层就没这个问题 */
  transition: opacity 420ms var(--ease), transform var(--wipe) var(--ease);
}
/* 放大倍率从 1.06 收到 1.04：横向外溢 = 画面宽 ×(倍率-1)/2，
   1.06 在 1112 宽的画面上就是 33px，仍然会顶破 24px 的页边距。 */
.halo.on {
  opacity: .42;
  transform: scale(1.04);
  /* 新的那层跟着幕布一起浮起来（用同一个 --wipe），这样画面外的那圈光
     和画面内的扫掠是同一拍 —— 两条时间线错开的话，光会"早到"。 */
  transition: opacity var(--wipe) var(--ease), transform var(--wipe) var(--ease);
}

/* ==================== 玻璃外壳 ====================
   三层阴影 = 外投影（浮起来）+ 一圈落地边 + 顶部内高光（玻璃的"厚度"）。
   ⚠️ backdrop-filter 是"透明感"的来源；老浏览器不认识这条就退化成半透明底，
   观感仍然成立，所以不为它写 @supports 分支。图比例不齐时留的也不是死黑（见 ③）。 */
.frame {
  position: relative;
  overflow: hidden;
  /* 画面用 --r-lg：这是"一屏只出现一次"的一块画面，比面板(10px)再大一级 */
  border-radius: var(--r-lg);
  border: 1px solid rgba(255, 255, 255, .10);
  background: rgba(8, 9, 12, .50);
  backdrop-filter: blur(22px) saturate(1.2);
  -webkit-backdrop-filter: blur(22px) saturate(1.2);
  box-shadow:
    0 28px 64px -20px rgba(0, 0, 0, .80),
    0 0 0 1px rgba(0, 0, 0, .35),
    inset 0 1px 0 rgba(255, 255, 255, .12);
}

/* 玻璃罩：顶上一道高光 + 底部压暗，让图看着是"嵌在玻璃下面"。
   ⚠️ pointer-events:none 是必须的 —— 图上那层 <a> 还得能点 */
.sheen {
  position: absolute;
  inset: 0;
  /* ⚠️ 必须高于 .slide.on 的 z-index:1。
     幻灯片为了"新图从旧图上扫过去"抬到了 1，玻璃罩若留在 auto，就会被盖在下面 ——
     顶上那道光和底部压暗会一起消失。 */
  z-index: 2;
  pointer-events: none;
  border-radius: inherit;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, .12) 0%, rgba(255, 255, 255, 0) 20%),
    linear-gradient(0deg, rgba(0, 0, 0, .32) 0%, rgba(0, 0, 0, 0) 26%);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, .05);
}

/*
  ==================== 换图：软边幕布揭幕 ====================
  遮罩是一块 **2.6 倍宽**的软边幕布：左 45% 全实、45%~58% 是软边、再往右全透。
    · mask-position 100% → 幕布右移，窗口落进"全透"那段 → 整幅看不见
    · mask-position 0%   → 幕布归位，窗口落进"全实"那段 → 整幅露出来
  百分比在 mask-position 上是可以插值的，所以不需要 @property，
  也不需要动 transform —— 硬边平移做不出"软"，而软边正是这个效果的全部。

  ⚠️ 渐变方向决定"新图从哪边进来"，要和翻页方向一致：
     · 默认（下一张 / 自动轮播）：实心在**右**（270deg），位置 0% → 100%
       幕布向左收 → 画面**从右往左**露出来，像往后翻一页
     · .rev（上一张）：把渐变镜像回实心在**左**（90deg），位置 100% → 0%
       → 画面**从左往右**露出来
    两个方向的位置端点不同（0/100 ↔ 100/0），这正是 setIndex 必须早一帧落定的原因。

  ⚠️ 倍数与停止点是被"扫掠占多少行程"倒推出来的，别随手改：
  软边只在 mask-position 的某一段区间里真正跨过画面，两端各有死角。
    3 倍宽 + 40%/50%  → 软边跨过画面的区间只占行程的 65%，两端加起来 35% 是空走
    2.6 倍宽 + 45%/58% → 占 84%，且能保证两端完全遮住 / 完全露出都有余量
  空走的那段在观感上就是"按下去先愣一下、然后软边一闪而过"。

  ⚠️ 进出两态的 transition **不一样**，这是关键：
    入场（.on 生效）  遮罩按 --wipe 扫过来；不透明度立刻到位（反正被遮罩挡着）
    退场（.on 移除）  先**原地不动**，等 --wipe 走完再瞬间归位。
  退场若立刻归位，旧图会先消失、露出空底，再被新图扫出来 —— 中间那一下黑闪
  正是要避免的"突兀"。所以：.slide 上的 transition 带 0s + 延时（这是"退场"用的），
  .slide.on 上的 transition 才是真正的扫掠时长。

  实测（把 --wipe 拉长 10 倍逐帧采样）：软边真正跨过画面的那一段约占行程的 62%，
  也就是说 --wipe: 640ms 时，眼睛看到的扫掠约 400ms —— 再快就回到"唰一下"，
  再慢就拖成"图片在慢慢溶"。 */
.slide {
  position: absolute;
  inset: 0;
  display: block;
  opacity: 0;
  /* 默认方向：实心在右 → 幕布向左收 → 新图从右侧进来 */
  -webkit-mask-image: linear-gradient(270deg, #000 0 45%, rgba(0, 0, 0, 0) 58% 100%);
  mask-image: linear-gradient(270deg, #000 0 45%, rgba(0, 0, 0, 0) 58% 100%);
  -webkit-mask-size: 260% 100%;
  mask-size: 260% 100%;
  -webkit-mask-repeat: no-repeat;
  mask-repeat: no-repeat;
  -webkit-mask-position: 0% 0;
  mask-position: 0% 0;
  pointer-events: none;
  /* 提前把这两个属性提到合成层：首次切换时遮罩层要现栅格化，
     实测冷启动开头有 ~220ms 的空档（栈里 2~3 张图，这点显存换掉那一下很值）。 */
  will-change: opacity, mask-position;
  transition: opacity 0s var(--wipe),
              -webkit-mask-position 0s var(--wipe),
              mask-position 0s var(--wipe);
}
.slide.on {
  opacity: 1;
  /* 正在出场的那张排在下面，让新图从它身上扫过去 */
  z-index: 1;
  -webkit-mask-position: 100% 0;
  mask-position: 100% 0;
  pointer-events: auto;
  transition: opacity 0s,
              -webkit-mask-position var(--wipe) var(--ease-wipe),
              mask-position var(--wipe) var(--ease-wipe);
}

/* ==================== 上一张：把幕布镜像回去 ====================
   渐变翻回实心在左，位置端点也跟着对调（遮 100% / 露 0%）。
   新图于是从左往右露出来，和「‹」的方向一致。 */
.carousel.rev .slide {
  -webkit-mask-image: linear-gradient(90deg, #000 0 45%, rgba(0, 0, 0, 0) 58% 100%);
  mask-image: linear-gradient(90deg, #000 0 45%, rgba(0, 0, 0, 0) 58% 100%);
  -webkit-mask-position: 100% 0;
  mask-position: 100% 0;
}
.carousel.rev .slide.on {
  -webkit-mask-position: 0% 0;
  mask-position: 0% 0;
}

/* ==================== 方向翻转的那一帧 ====================
   ⚠️ 必须排在 .slide.on 之后（同特异度，靠源码顺序压制），
   负责让"方向落定"这一步不产生动画 —— 两个方向在全遮/全露下渲染结果相同，
   所以这一帧无动画地换过去是看不见的（理由见 setIndex 的注释）。 */
.carousel.dir-lock .slide { transition: none; }

.img { width: 100%; height: 100%; object-fit: contain; display: block; }

/*
  落定：新图从 1.035 缓缓收到 1。
  ⚠️ 用 @keyframes 而不是 transition —— 想要的是"进场时呼吸一次、然后停在原尺寸"，
  而 transition 的终点必须是稳态值，写不出"一次性回正"。
  退场时动画被移除，transform 回到 none（= 1），与动画终点一致，所以不会跳。
  自带减少动效的机器上，base.css 会把所有 keyframes 压成瞬时，这里自然就跳过了。 */
@keyframes img-settle {
  from { transform: scale(1.035); }
  to { transform: scale(1); }
}
.slide.on .img { animation: img-settle 1250ms var(--ease) both; }

/* ==================== 关了动效的机器 ====================
   ⚠️ 先看这段"踩过的坑"，它比代码本身重要：

   这条路被当成"少数人的兜底"，其实**不是**。prefers-reduced-motion 在 Windows 上
   跟着「设置 → 辅助功能 → 视觉效果 → 动画效果」走，关掉它的人相当多 ——
   而**这台开发机就是关着的**（实测 SPI_GETCLIENTAREAANIMATION 返回 0）。
   也就是说：作者本人一直看到的都是这一档，而我在 headless 里验证的却是另一档，
   因为 **Playwright 的 reducedMotion 默认值是 no-preference**（不是"跟随系统"），
   等于每次都帮我把这条分支屏蔽掉了。于是"改了半天没变化"。

   结论：这一档必须**当成正经样式来做**，不能随手糊一个短淡化。
   语义上：把"一道有方向的扫掠"整个去掉（那才是可能引起不适的运动），
   只留一次足够慢的淡入。

   ⚠️ 时长别写 340ms —— 实测反馈里被当成"硬切 / 闪一下"的正是这一档。
   现在跟幕布共用同一个节拍：新图在 0.7 × --wipe（默认 700ms）里浮起来。
   结构上仍然是"旧的原地不动、新的叠在上面浮起来"，不是两边同时淡 ——
   两边同时淡会在中点掉进页面底色，画面先暗一下（暗底上特别明显）。 */
.no-anim .slide {
  -webkit-mask-image: none;
  mask-image: none;
  transition: opacity 0s var(--wipe);
}
/* ⚠️ 曲线用 --ease-wipe（接近匀速），不能用 --ease。
   --ease 是强 ease-out：实测新图在 330ms 时就已经浮到 0.5、413ms 到 0.72 ——
   变化全挤在前 40% 的时间里，观感仍然是"快"。匀速之后 0.5 出现在 350ms、
   0.9 在 630ms，才真的是"慢慢换过去"。 */
.no-anim .slide.on { transition: opacity calc(var(--wipe) * .7) var(--ease-wipe); }
.no-anim .img { animation: none; }
.no-anim .halo { transform: none; transition: opacity calc(var(--wipe) * .7) var(--ease-wipe); }

/* 说明文字：玻璃药丸压在图底正中 —— 比原来那条通栏黑渐变轻，浅色截图上也不糊 */
.cap {
  position: absolute;
  left: 50%;
  bottom: var(--sp-4);
  transform: translateX(-50%);
  max-width: calc(100% - var(--sp-6));
  padding: 6px var(--sp-4);
  font-size: var(--fs-sm);
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  background: rgba(12, 14, 18, .55);
  border: 1px solid rgba(255, 255, 255, .12);
  border-radius: var(--r-pill);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  pointer-events: none;
}

/* ==================== 暂停 / 继续 ====================
   与箭头同一套玻璃语言，但**一直可见**（只是压到 .5 的不透明度）：
   一个只在悬停时才出现的"暂停"按钮，等于没有 —— 用户不知道它存在，
   也就找不到那个"让它停下来"的开关。 */
.toggle {
  position: absolute;
  top: var(--sp-3);
  right: var(--sp-3);
  z-index: 3;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  padding: 0;
  font: inherit;
  color: var(--ink);
  background: rgba(12, 14, 18, .48);
  border: 1px solid rgba(255, 255, 255, .14);
  border-radius: var(--r-pill);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  cursor: pointer;
  opacity: .5;
  transition: opacity var(--dur) var(--ease), background var(--dur-fast) var(--ease),
              border-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.stage:hover .toggle, .toggle:focus-visible { opacity: 1; }
.toggle:hover {
  background: rgba(242, 171, 85, .20);
  border-color: var(--ember);
  color: var(--ember-hot);
}

/* ==================== 左右箭头 ====================
   玻璃圆钮：半透明底 + backdrop-filter + 发丝边。平时收起不挡图，
   鼠标进来（或键盘聚焦）才"弹"出来 —— 位移 + 透明 + 缩放一起动。 */
.nav {
  position: absolute;
  top: 50%;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  font: inherit;
  font-size: var(--fs-xl);
  line-height: 1;
  color: var(--ink);
  background: rgba(12, 14, 18, .42);
  border: 1px solid rgba(255, 255, 255, .14);
  border-radius: var(--r-pill);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  cursor: pointer;
  opacity: 0;
  transform: translateY(-50%) scale(.92);
  transition: opacity var(--dur) var(--ease), transform var(--dur) var(--ease),
              background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
}
.prev { left: var(--sp-4); }
.next { right: var(--sp-4); }
.stage:hover .nav, .nav:focus-visible { opacity: 1; transform: translateY(-50%) scale(1); }
.nav:hover {
  background: rgba(232, 160, 76, .18);
  border-color: var(--flame);
  color: var(--flame-bright);
  box-shadow: 0 0 18px var(--flame-glow);
}
.nav:active { transform: translateY(-50%) scale(.94); }

/* ==================== 页码 + 自动播放进度 ====================
   放在画面**下面**一排（压在图上会和说明文字抢位置）。当前那张拉长成胶囊，
   里面走一条灵火进度 —— "它自己会翻"这件事因此看得见，不用干等。 */
.dots {
  position: relative;
  /* 环境光会溢到这一排来，页码得压在它上面（见 .ambient） */
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  /* ⚠️ 间距由 8px 放到 12px：下面给每个圆点加了一圈伪元素来撑大点击区(见 .dot::after)，
     间距太小时相邻圆点的可点区会重叠 —— 按左边那个却跳到右边，是最恼人的一种 bug。 */
  gap: var(--sp-3);
}
.dot {
  position: relative;
  width: 8px;
  height: 8px;
  padding: 0;
  border-radius: var(--r-pill);
  border: 1px solid var(--line-strong);
  background: var(--line-strong);
  overflow: hidden;
  cursor: pointer;
  transition: width var(--dur) var(--ease), background var(--dur-fast) var(--ease),
              border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
}
/* ⚠️ 视觉尺寸 8px，可点区域撑到 28px —— WCAG 2.5.8 要求指针目标至少 24×24。
   不能直接改 width/height：那会把这一排页码变成一排大圆球，画面会被抢走。 */
.dot::after { content: ''; position: absolute; inset: -10px -6px; }
.dot:hover { border-color: var(--flame); background: var(--flame-veil); }
.dot.on {
  width: 34px;
  border-color: rgba(255, 255, 255, .16);
  background: rgba(255, 255, 255, .10);
  box-shadow: 0 0 12px var(--flame-glow);
}
/* 时长由内联的 --dot-dur 给（= 后台配的切换间隔），这里只描述"从空到满"。
   ⚠️ 两条都是实测踩出来的：
   1. 时长不能走内联 animation-duration —— 内联样式压不过 base.css 那条
      `animation-duration: .01ms !important`，只能走自定义属性 + !important。
   2. **必须写成长手**：`animation: ... !important` 简写会把 animation-play-state
      也一并 importance 成 running，下面「悬停暂停」那条就永远不生效 ——
      表现是画面停住了、进度条却自顾自走到底。 */
.fill {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--flame-deep), var(--flame-bright));
  transform-origin: left center;
  transform: scaleX(0);
  animation-name: dotfill;
  animation-duration: var(--dot-dur, 4000ms) !important;
  animation-timing-function: linear;
  animation-fill-mode: forwards;
}
@keyframes dotfill { from { transform: scaleX(0); } to { transform: scaleX(1); } }
/* 停下时（悬停 / 滚出视口）进度也停住：人在看的时候它不该偷偷走完 */
.paused .fill { animation-play-state: paused; }

/* 触摸设备没有 hover：箭头必须常驻，否则等于没有（圆点在画面下面，仍然能切） */
@media (hover: none) {
  .nav { opacity: .95; transform: translateY(-50%) scale(1); }
  /* 触摸设备上箭头就是主要操作，撑到 44px 才点得稳 */
  .nav { width: 44px; height: 44px; }
  /* 触摸设备没有 hover，暂停按钮也就永远不会"亮起来"，直接给足不透明度 */
  .toggle { opacity: .95; }
}

@media (max-width: 640px) {
  /* 手机上箭头会占掉本就不多的画面宽度，所以做小，但仍在 40px 以上（可点性优先于省地方） */
  .nav { width: 40px; height: 40px; font-size: var(--fs-lg); }
  .prev { left: var(--sp-2); }
  .next { right: var(--sp-2); }
  .cap { font-size: var(--fs-xs); padding: 4px var(--sp-3); bottom: var(--sp-2); }
  .halo { filter: blur(26px) saturate(1.3); border-radius: 14px; }
  .dot.on { width: 26px; }
  .ambient { inset: -7% 0; }
}
</style>
