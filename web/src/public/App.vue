<script setup lang="ts">
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { renderInline } from './renderInline'
import { t } from './useSiteText'
import Icon from '@shared/ui/Icon.vue'

const route = useRoute()

/**
 * 导航项。
 *
 * 每一项都配一个线性图标 —— 手机端底部那排按钮只有图标 + 小字，
 * 光靠文字在 375px 宽度里会挤成四个字块，认起来比图标慢得多。
 */
const nav = [
  { to: '/', label: '首页', icon: 'home' },
  { to: '/library', label: '资料库', icon: 'book' },
  { to: '/plaza', label: '问答广场', icon: 'chat' },
  { to: '/about', label: '关于', icon: 'info' },
]
const isActive = (to: string) =>
  to === '/' ? route.path === '/' : route.path.startsWith(to)
</script>

<template>
  <div class="shell mist-veil texture-grain">
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
      </div>
    </header>

    <main id="main" class="main">
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

    <!--
      手机端导航。
      ⚠️ 为什么另做一条底栏，而不是把顶栏那条 na v 换行塞进去：
      顶栏那条 nav 换行会占掉两行高度（首屏本来就短），而且第二行左边会留一大块空白 ——
      这是手机上最容易看出"没为移动端做过设计"的地方。
      底栏还顺手解决了拇指够不到的问题：4 个入口全在屏幕下缘 58px 内。
      ≥760px 时整条隐藏（桌面用顶栏）。
    -->
    <nav class="tabbar" aria-label="主导航（移动端）">
      <RouterLink
        v-for="n in nav"
        :key="n.to"
        :to="n.to"
        class="tab"
        :class="{ active: isActive(n.to) }"
        :aria-current="isActive(n.to) ? 'page' : undefined"
      >
        <Icon :name="n.icon" :size="21" />
        <span class="tab-label">{{ n.label }}</span>
      </RouterLink>
    </nav>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  position: relative;
  /* ☝️ 内容外壳压在 .texture-grain（z-index: 1）之上 */
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

/* ==================== 顶栏 ====================
   半透明 + 背景模糊：滚动时内容从它下面透过去，而不是被一条实心条切断。
   ⚠️ 必须同时有不透明兜底（下面的 --stone-300 那层）——*/
.hdr {
  position: sticky;
  top: 0;
  z-index: var(--z-sticky);
  /* 前一行是兜底：不认识 color-mix 的浏览器直接用不透明底，不会"透出内容重影" */
  background: var(--stone-200);
  background: color-mix(in srgb, var(--stone-200) 82%, transparent);
  backdrop-filter: saturate(1.3) blur(14px);
  -webkit-backdrop-filter: saturate(1.3) blur(14px);
}
/* 下缘那道刻线：中间实、两端虚。比通栏 1px 实线轻，也比它更像"石头的边"。 */
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
  /* 这是站标，不是正文里的链接 —— 不参加"正文链接必须有下划线"那条规则 */
  text-decoration: none;
  color: var(--ink);
  font-family: var(--font-title);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  letter-spacing: .06em;
  white-space: nowrap;
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
  padding: 7px 12px;
  font-size: var(--fs-base);
  color: var(--ink-2);
  border-radius: var(--r-sm);
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.nav-link:hover { color: var(--ink); background: var(--surface-hover); text-decoration: none; }
/* 激活态：不再是"描一圈铜边的药丸"（那看起来像个默认标签页控件），
   而是底部一道灵火线 —— 位置感靠下划线，颜色留给内容。 */
.nav-link.active { color: var(--ember-hot); }
.nav-link.active::after {
  content: '';
  position: absolute;
  left: 12px; right: 12px; bottom: 0;
  height: 2px;
  border-radius: 1px;
  background: linear-gradient(90deg, transparent, var(--ember), transparent);
}

.main { flex: 1; }

/* ==================== 页脚 ==================== */
.ftr { padding-top: var(--sp-7); padding-bottom: var(--sp-6); }
.ftr p { font-size: var(--fs-xs); color: var(--ink-3); margin: 0 0 4px; }
.ftr .rule { margin-top: 0; margin-bottom: var(--sp-4); }

/* ==================== 手机端底栏 ==================== */
.tabbar { display: none; }

@media (max-width: 760px) {
  .hdr-inner { height: 52px; }
  .sub { display: none; }
  .nav { display: none; }

  .tabbar {
    position: fixed;
    left: 0; right: 0; bottom: 0;
    z-index: var(--z-sticky);
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    background: var(--stone-300);
    background: color-mix(in srgb, var(--stone-300) 92%, transparent);
    backdrop-filter: saturate(1.3) blur(16px);
    -webkit-backdrop-filter: saturate(1.3) blur(16px);
    border-top: 1px solid var(--hairline);
    /* 全面屏的 Home 指示条占位，不然最后一排按钮会被它压住 */
    padding-bottom: var(--safe-b);
  }
  .tab {
    position: relative;
    text-decoration: none;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    /* 44px 是拇指能稳定点中的最小高度，别往下压 */
    min-height: var(--tabbar-h);
    color: var(--ink-3);
    font-size: var(--fs-micro);
    letter-spacing: .04em;
    transition: color var(--dur-fast) var(--ease);
  }
  .tab:hover { text-decoration: none; }
  .tab.active { color: var(--ember-hot); }
  .tab.active::before {
    content: '';
    position: absolute;
    top: 0;
    left: 50%;
    width: 26px;
    height: 2px;
    margin-left: -13px;
    border-radius: 0 0 2px 2px;
    background: var(--ember);
    box-shadow: 0 0 10px var(--ember-glow);
  }
  .tab-label { line-height: 1; }

  /* 给底栏让出空间，否则页脚最后一行永远压在导航下面 */
  .ftr { padding-bottom: calc(var(--tabbar-h) + var(--safe-b) + var(--sp-5)); }
}
</style>
