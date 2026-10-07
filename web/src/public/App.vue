<script setup lang="ts">
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { renderInline } from './renderInline'
import { t } from './useSiteText'

const route = useRoute()
const nav = [
  { to: '/', label: '首页' },
  { to: '/library', label: '资料库' },
  { to: '/plaza', label: '问答广场' },
  { to: '/about', label: '关于' },
]
const isActive = (to: string) =>
  to === '/' ? route.path === '/' : route.path.startsWith(to)
// 年份不再单独算：t() 里的 {year} 会自动替换成当前年份
</script>

<template>
  <div class="shell texture-grain">
    <header class="hdr veil-shroud">
      <div class="page hdr-inner">
        <RouterLink to="/" class="brand">
          <span class="mark" aria-hidden="true">✦</span>
          <span class="name">飘雪喵</span>
          <span class="sep">·</span>
          <span class="sub">雾锁王国助手</span>
        </RouterLink>
        <nav class="nav">
          <RouterLink
            v-for="n in nav"
            :key="n.to"
            :to="n.to"
            class="nav-link"
            :class="{ active: isActive(n.to) }"
          >{{ n.label }}</RouterLink>
        </nav>
      </div>
    </header>

    <main class="main">
      <RouterView v-slot="{ Component }">
        <component :is="Component" />
      </RouterView>
    </main>

    <footer class="ftr">
      <div class="page">
        <hr class="rule">
        <p class="faint">
          <!-- 这里原来钉着一个写死的「原站 ↗」链接（指向 Wiki 主站），文案块里删不掉它。
               2026-09-28 按需求去掉：署名文字本身走 layout.footer_note 那一段文案，
               需要链接时直接在文案里写 [文字](链接) —— renderInline 支持行内链接。 -->
          <span v-html="renderInline(t('layout.footer_note', '资料来源于 Enshrouded Wiki（CC BY-NC-SA 3.0）· 游戏素材版权归 Keen Games 所有'))" />
        </p>
        <p class="faint"><span v-html="renderInline(t('layout.footer_copy', '© {year} 示例助手 · 由示例作者提供技术支持'))" /></p>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.shell { min-height: 100vh; display: flex; flex-direction: column; position: relative; z-index: 2; }

.hdr {
  border-bottom: 1px solid var(--line);
  background: var(--bg-shroud);
  position: sticky;
  top: 0;
  z-index: var(--z-sticky);
}
.hdr-inner {
  height: var(--header-h);
  display: flex;
  align-items: center;
  gap: var(--sp-4);
}
.brand {
  display: flex;
  align-items: baseline;
  gap: 6px;
  color: var(--ink);
  font-family: var(--font-title);
  font-size: var(--fs-lg);
  letter-spacing: .04em;
}
.brand:hover { color: var(--ink); }
.mark {
  color: var(--flame);
  text-shadow: 0 0 10px var(--flame-glow);
  font-size: var(--fs-md);
}
.sep { color: var(--copper); }
.sub { font-size: var(--fs-sm); color: var(--ink-dim); font-family: var(--font-body); }

.nav { margin-left: auto; display: flex; gap: var(--sp-1); }
.nav-link {
  padding: 7px 14px;
  border-radius: var(--r-sm);
  font-size: var(--fs-sm);
  color: var(--ink-dim);
  border: 1px solid transparent;
  transition: all var(--dur-fast) var(--ease);
}
.nav-link:hover { color: var(--flame-bright); background: var(--flame-veil); }
.nav-link.active {
  color: var(--flame-bright);
  border-color: var(--copper-dim);
  background: var(--flame-veil);
}

.main { flex: 1; }
.ftr { padding-top: var(--sp-6); padding-bottom: var(--sp-5); }
.ftr p { font-size: var(--fs-xs); margin: 0 0 4px; }

@media (max-width: 640px) {
  .hdr-inner { flex-wrap: wrap; height: auto; padding: 10px var(--sp-4); gap: var(--sp-2); }
  .nav { margin-left: 0; width: 100%; }
  .sub { display: none; }
}
</style>
