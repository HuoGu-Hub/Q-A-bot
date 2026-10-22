<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { renderInline } from './renderInline'
import { t } from './useSiteText'
import Icon from '@shared/ui/Icon.vue'
import ThemeToggle from '@shared/ui/ThemeToggle.vue'
import BottomNav from '@shared/ui/BottomNav.vue'

const route = useRoute()
const router = useRouter()

/**
 * 全屏页：地图要占满一屏、页面本身不滚动。
 *
 * <p>为什么由外壳判断，而不是让地图页自己写 position: fixed：
 * fixed 会让它脱离文档流，外壳的高度计算立刻失去依据 ——
 * 页脚会跑到地图底下去、手机底栏会压住地图。
 */
const fullBleed = computed(() => route.name === 'map')

/**
 * 路由正在换页 —— 顶部那条细进度条的开关。
 *
 * <p>视图是懒加载的，点一个链接之后要先下载模块、再执行、再渲染，
 * 这中间界面完全没有变化。本机几十毫秒感觉不出来；经内网穿透到公网时，
 * 一个来回几百毫秒起，这段窗口会变成"点了没反应"。
 */
const navBusy = ref(false)

/** 兜底：万一 afterEach 因为异常没跑到，进度条不该永远挂着 */
let busyTimer: number | undefined
function setBusy(on: boolean) {
  if (busyTimer !== undefined) { window.clearTimeout(busyTimer); busyTimer = undefined }
  navBusy.value = on
  if (on) busyTimer = window.setTimeout(() => { navBusy.value = false }, 15000)
}

router.beforeEach(() => { setBusy(true) })
router.afterEach(() => { setBusy(false) })
router.onError(() => { setBusy(false) })
onBeforeUnmount(() => { if (busyTimer !== undefined) window.clearTimeout(busyTimer) })

/**
 * 导航项。
 *
 * 每一项都配一个线性图标：手机底栏只有图标 + 两三个字，
 * 光靠文字在 375px 宽度里会挤成四个字块，认起来比图标慢得多。
 */
const nav = [
  { to: '/', label: '首页', icon: 'home' },
  { to: '/library', label: '资料库', icon: 'book' },
  { to: '/map', label: '地图', icon: 'map-pin' },
  { to: '/plaza', label: '问答', icon: 'chat' },
  { to: '/about', label: '关于', icon: 'info' },
]
const isActive = (to: string) =>
  to === '/' ? route.path === '/' : route.path.startsWith(to)

const tabItems = computed(() => nav.map(n => ({ ...n, active: isActive(n.to) })))
</script>

<template>
  <div class="shell mist-veil texture-grain" :class="{ 'is-full': fullBleed }">
    <!-- 键盘用户第一个 Tab 就能跳过导航。视觉上隐藏，聚焦时才出现 -->
    <a class="skip" href="#main">跳到主要内容</a>

    <header class="hdr">
      <div class="page hdr-inner">
        <RouterLink to="/" class="brand">
          <Icon name="flame" class="mark" :size="19" />
          <span class="name">飘雪喵</span>
          <span class="sub">雾锁王国助手</span>
        </RouterLink>

        <nav class="nav" aria-label="主导航">
          <RouterLink
            v-for="n in nav"
            :key="n.to"
            :to="n.to"
            class="nav-link"
            :class="{ active: isActive(n.to) }"
            :aria-current="isActive(n.to) ? 'page' : undefined"
          >{{ n.label }}</RouterLink>
        </nav>

        <ThemeToggle class="theme-slot" />
      </div>
    </header>

    <!--
      换页进度条：贴在页头下沿的一条 2px 微光。
      常驻 DOM、只切透明度 —— 临时插入元素会晚一帧，而这一帧正是要补上的那一帧。
    -->
    <div
      class="nav-progress"
      :class="{ on: navBusy }"
      role="progressbar"
      aria-label="页面载入中"
      :aria-hidden="navBusy ? undefined : 'true'"
    ><span /></div>
    <p class="sr-only" role="status" aria-live="polite">{{ navBusy ? '页面载入中' : '' }}</p>

    <main id="main" class="main" :aria-busy="navBusy ? 'true' : undefined">
      <RouterView v-slot="{ Component }">
        <component :is="Component" />
      </RouterView>
    </main>

    <footer class="ftr">
      <div class="page">
        <hr class="rule">
        <p class="faint">
          <span v-html="renderInline(t('layout.footer_note', '资料来源于 Enshrouded Wiki（CC BY-NC-SA 3.0）· 游戏素材版权归 Keen Games 所有'))" />
        </p>
        <p class="faint"><span v-html="renderInline(t('layout.footer_copy', '© {year} 示例助手 · 由示例作者提供技术支持'))" /></p>
      </div>
    </footer>

    <!-- 手机端导航。桌面端的隐藏由 BottomNav 自己负责，不要在这里再挂一个类 -->
    <BottomNav :items="tabItems" />
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
  position: relative;
  /* 内容外壳压在 .texture-grain（z-index: 1）之上 */
  z-index: 2;
}

/* ==================== 跳过导航 ==================== */
.skip {
  text-decoration: none;
  position: absolute;
  left: var(--sp-3);
  top: var(--sp-3);
  z-index: var(--z-toast);
  padding: var(--sp-2) var(--sp-4);
  background: var(--stone-400);
  border: 1px solid var(--ember);
  border-radius: var(--r-sm);
  font-size: var(--fs-sm);
  transform: translateY(-200%);
  transition: transform var(--dur) var(--ease);
}
.skip:focus { transform: none; color: var(--ember-hot); }

/* ==================== 换页进度条 ==================== */
.nav-progress {
  position: fixed;
  top: var(--header-h);
  left: 0;
  right: 0;
  height: 2px;
  z-index: var(--z-sticky);
  pointer-events: none;
  opacity: 0;
  transition: opacity 180ms linear;
}
.nav-progress.on { opacity: 1; }

/* 一道从左往右来回的微光。用灵火色，和站内唯一被点亮的东西是一套语言 */
.nav-progress span {
  display: block;
  height: 100%;
  width: 34%;
  border-radius: 0 var(--r-pill) var(--r-pill) 0;
  background: linear-gradient(90deg, transparent, var(--ember), var(--ember-hot));
  box-shadow: 0 0 8px color-mix(in srgb, var(--ember) 55%, transparent);
  animation: nav-sweep 1.1s cubic-bezier(.45, .05, .35, 1) infinite;
}
@keyframes nav-sweep {
  0%   { transform: translateX(-100%); }
  100% { transform: translateX(394%); }
}
/* reduced-motion：光别扫，改成"亮着不动" —— 依然明确告诉人"在等" */
@media (prefers-reduced-motion: reduce) {
  .nav-progress span {
    width: 100%;
    animation: none;
    border-radius: 0;
    opacity: .75;
  }
}

/* ==================== 顶栏 ====================
   半透明 + 背景模糊：滚动时内容从它下面透过去，而不是被一条实心条切断。 */
.hdr {
  position: sticky;
  top: 0;
  z-index: var(--z-sticky);
  /* 前一行是兜底：不认识 color-mix 的浏览器直接用不透明底 */
  background: var(--stone-200);
  background: color-mix(in srgb, var(--stone-200) 88%, transparent);
  backdrop-filter: saturate(1.3) blur(14px);
  -webkit-backdrop-filter: saturate(1.3) blur(14px);
}
/* 下缘那道刻线：中间实、两端虚，比通栏实线轻，也更像石头的边 */
.hdr::after {
  content: '';
  position: absolute;
  left: 0; right: 0; bottom: 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--hairline) 14%, var(--hairline) 86%, transparent);
}
.hdr-inner {
  height: var(--header-h);
  display: flex;
  align-items: center;
  gap: var(--sp-5);
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  /* 这是站标，不是正文链接 —— 不参加"正文链接必须有下划线"那条规则 */
  text-decoration: none;
  color: var(--ink);
  font-family: var(--font-title);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  letter-spacing: .06em;
  white-space: nowrap;
  /* 手机上让站标也有 44 的命中高度 */
  min-height: 44px;
}
.brand:hover { color: var(--ink); text-decoration: none; }
.mark {
  color: var(--ember);
  /* 灯芯那一点光：这是全站唯一允许"发光"的图标 */
  filter: drop-shadow(0 0 7px var(--ember-glow));
}
.sub {
  font-family: var(--font-body);
  font-size: var(--fs-xs);
  font-weight: var(--fw-normal);
  letter-spacing: .04em;
  color: var(--ink-3);
  padding-left: var(--sp-3);
  border-left: 1px solid var(--hairline);
}

.nav { margin-left: auto; display: flex; align-items: center; gap: var(--sp-1); }
.nav-link {
  position: relative;
  text-decoration: none;
  padding: 8px 12px;
  font-size: var(--fs-base);
  color: var(--ink-2);
  border-radius: var(--r-sm);
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.nav-link:hover { color: var(--ink); background: var(--surface-hover); text-decoration: none; }
/* 激活态：底部一道灵火线。位置感靠下划线，颜色留给内容 */
.nav-link.active { color: var(--ember-hot); }
.nav-link.active::after {
  content: '';
  position: absolute;
  left: 12px; right: 12px; bottom: 0;
  height: 2px;
  border-radius: 1px;
  background: linear-gradient(90deg, transparent, var(--ember), transparent);
}
.theme-slot { margin-left: var(--sp-3); }

.main { flex: 1; min-width: 0; }

/* ==================== 全屏页（地图） ====================
   一屏到底：外壳高度锁死、主区域撑满、页脚藏掉。
   ⚠️ 主区域必须给 min-height: 0 —— flex 子项默认 min-height: auto，
   内容一高就会把容器顶破，那时 overflow: hidden 反而会裁掉地图的底部。 */
.shell.is-full {
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
}
.shell.is-full .main {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.shell.is-full .ftr { display: none; }

/* ==================== 页脚 ==================== */
.ftr { padding-top: var(--sp-7); padding-bottom: var(--sp-6); }
.ftr p { font-size: var(--fs-xs); color: var(--ink-3); margin: 0 0 4px; }
.ftr .rule { margin-top: 0; margin-bottom: var(--sp-4); }

/* ==================== 窄屏 ==================== */
@media (max-width: 760px) {
  .sub { display: none; }
  .nav { display: none; }
  /* 站名 + 主题开关各占一头 */
  .theme-slot { margin-left: auto; }
  .hdr-inner { gap: var(--sp-2); }

  /* 手机上地图让出底栏高度，否则底部一截永远被压着 */
  .shell.is-full .main { padding-bottom: calc(var(--tabbar-h) + var(--safe-b)); }

  /* 给底栏让出空间，否则页脚最后一行永远压在导航下面 */
  .ftr { padding-bottom: calc(var(--tabbar-h) + var(--safe-b) + var(--sp-5)); }
}
</style>