<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watchEffect } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { renderInline } from './renderInline'
import { t } from './useSiteText'
import { botName } from '@shared/identity/botName'
import Icon from '@shared/ui/Icon.vue'
import ThemeToggle from '@shared/ui/ThemeToggle.vue'
import BottomNav from '@shared/ui/BottomNav.vue'

const route = useRoute()
const router = useRouter()

watchEffect(() => {
  document.title = `${botName.value} · 知识库问答机器人`
})

const fullBleed = computed(() => route.name === 'map')

const navBusy = ref(false)

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

const nav = [
  { to: '/', label: '首页', icon: 'home' },
  { to: '/library', label: '资料库', icon: 'book' },
  { to: '/map', label: '地图', icon: 'map-pin' },
  { to: '/about', label: '关于', icon: 'info' },
]
const isActive = (to: string) =>
  to === '/' ? route.path === '/' : route.path.startsWith(to)

const tabItems = computed(() => nav.map(n => ({ ...n, active: isActive(n.to) })))
</script>

<template>
  <div class="shell mist-veil texture-grain" :class="{ 'is-full': fullBleed }">
    <a class="skip" href="#main">跳到主要内容</a>

    <header class="hdr">
      <div class="page hdr-inner">
        <RouterLink to="/" class="brand">
          <Icon name="flame" class="mark" :size="19" />
          <span class="name">{{ botName }}</span>
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

        <a class="admin-link" href="/admin/" title="管理后台" aria-label="管理后台">
          <Icon name="lock" :size="14" aria-hidden="true" />
          <span>管理</span>
        </a>

        <ThemeToggle class="theme-slot" />
      </div>
    </header>

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
        <p class="faint"><span v-html="renderInline(t('layout.footer_copy', '{bot}'))" /></p>
      </div>
    </footer>

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
  z-index: 2;
}

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
@media (prefers-reduced-motion: reduce) {
  .nav-progress span {
    width: 100%;
    animation: none;
    border-radius: 0;
    opacity: .75;
  }
}

.hdr {
  position: sticky;
  top: 0;
  z-index: var(--z-sticky);
  background: var(--stone-200);
  background: color-mix(in srgb, var(--stone-200) 88%, transparent);
  backdrop-filter: saturate(1.3) blur(14px);
  -webkit-backdrop-filter: saturate(1.3) blur(14px);
}
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
  text-decoration: none;
  color: var(--ink);
  font-family: var(--font-title);
  font-size: var(--fs-lg);
  font-weight: var(--fw-semi);
  letter-spacing: .06em;
  white-space: nowrap;
  min-height: 44px;
}
.brand:hover { color: var(--ink); text-decoration: none; }
.mark {
  color: var(--ember);
  filter: drop-shadow(0 0 7px var(--ember-glow));
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

.admin-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: none;
  min-height: 40px;
  padding: 0 10px;
  font-size: var(--fs-sm);
  color: var(--ink-3);
  text-decoration: none;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease),
    border-color var(--dur-fast) var(--ease);
}
.admin-link:hover {
  color: var(--ember-hot);
  background: var(--surface-hover);
  border-color: var(--edge-soft);
  text-decoration: none;
}
.admin-link:focus-visible { outline: 2px solid var(--ember); outline-offset: 2px; }

.main { flex: 1; min-width: 0; }

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

.ftr { padding-top: var(--sp-7); padding-bottom: var(--sp-6); }
.ftr p { font-size: var(--fs-xs); color: var(--ink-3); margin: 0 0 4px; }
.ftr .rule { margin-top: 0; margin-bottom: var(--sp-4); }

@media (max-width: 760px) {
  .nav { display: none; }
  .admin-link { margin-left: auto; min-height: 44px; }
  .theme-slot { margin-left: var(--sp-1); }
  .hdr-inner { gap: var(--sp-2); }

  .shell.is-full .main { padding-bottom: calc(var(--tabbar-h) + var(--safe-b)); }

  .ftr { padding-bottom: calc(var(--tabbar-h) + var(--safe-b) + var(--sp-5)); }
}
</style>
