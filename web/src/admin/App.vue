<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import Toaster from '@shared/ui/Toaster.vue'
import Icon from '@shared/ui/Icon.vue'
import ThemeToggle from '@shared/ui/ThemeToggle.vue'
import NavDrawer from '@shared/ui/NavDrawer.vue'
import { useAuth } from './useAuth'

const route = useRoute()
const router = useRouter()
const auth = useAuth()

/**
 * 是否显示顶端导航栏。
 *
 * <p>只有登录页不显示。这里踩过坑：早先「数据大屏」是唯一隐藏导航的页面，
 * 而 /admin/ 又默认重定向到它，结果不熟悉的人打不开其余页面。导航是「路」，
 * 不该有让人迷路的例外。
 */
const hasBar = computed(() => route.name !== 'login')

const drawerOpen = ref(false)

/**
 * 6 个目的地。桌面端平铺，手机端收进抽屉 —— 上一版 7 个 tab 横排，
 * 390px 下「页面信息」和「退出」被挤到屏幕外，等于导航只剩 5 项。
 * desc 只在抽屉里显示：它是解释，不是标签。
 */
const nav = [
  { to: '/stats', label: '统计', icon: 'chart', desc: '提问量、命中率与记录' },
  { to: '/kb', label: '知识库', icon: 'book', desc: '文档、词条与分类' },
  { to: '/map', label: '地图', icon: 'map-pin', desc: '底图同步与点位维护' },
  { to: '/system', label: '系统', icon: 'gear', desc: '模型配置与运行日志' },
  { to: '/bot', label: 'Bot 配置', icon: 'bot', desc: '指令、回复与功能开关' },
  { to: '/pages', label: '页面信息', icon: 'list', desc: '前台文案与轮播' },
]

const isActive = (to: string) => route.path.startsWith(to)
const drawerItems = computed(() =>
  nav.map(n => ({ ...n, active: isActive(n.to) })))

/**
 * 当前页面的名字，用来给屏幕阅读器一个 h1。
 *
 * <p>页面上故意不写可见的页面标题（顶端导航已经标出你在哪一页，再写一遍是重复），
 * 但无障碍树里的文档必须有一个 h1 —— 读屏用户靠「按标题跳转」在页面间穿行。
 */
const currentLabel = computed(() => {
  if (!hasBar.value) return '管理员登录'
  const hit = nav.find(n => route.path.startsWith(n.to))
  return hit ? hit.label + ' · Q-A-bot 管理后台' : 'Q-A-bot 管理后台'
})

async function logout() {
  await auth.logout()
  drawerOpen.value = false
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="admin texture-grain" :class="{ 'has-bar': hasBar }">
    <header v-if="hasBar" class="bar">
      <div class="bar-inner">
        <RouterLink to="/stats" class="brand">
          <Icon name="flame" class="mark" :size="18" />
          <span class="brand-txt">Q-A-bot</span>
          <span class="brand-sub">管理</span>
          <!-- 窄屏上 .brand-txt / .brand-sub 都被视觉隐藏了，链接就只剩一个图标 ——
               那会被判成「没有可读名字的链接」。这句只在读屏里出现，兜住站名。 -->
          <span class="sr-only">Q-A-bot 管理后台</span>
        </RouterLink>

        <nav class="nav" aria-label="后台导航">
          <RouterLink
            v-for="n in nav"
            :key="n.to"
            :to="n.to"
            class="nav-link"
            :class="{ active: isActive(n.to) }"
            :aria-current="isActive(n.to) ? 'page' : undefined"
          >{{ n.label }}</RouterLink>
        </nav>

        <div class="bar-right">
          <ThemeToggle />
          <button class="out" type="button" @click="logout">
            <Icon name="lock" :size="14" />
            <span class="out-txt">退出</span>
          </button>
          <button
            class="menu-btn"
            type="button"
            aria-label="打开导航菜单"
            :aria-expanded="drawerOpen"
            @click="drawerOpen = true"
          >
            <Icon name="menu" :size="18" />
          </button>
        </div>
      </div>
    </header>

    <!-- 软弹窗容器：动作反馈（保存成功/失败）用浮层，不插进页面流 -->
    <Toaster />

    <main class="admin-main">
      <h1 class="sr-only">{{ currentLabel }}</h1>
      <RouterView v-slot="{ Component }">
        <component :is="Component" />
      </RouterView>
    </main>

    <NavDrawer
      :open="drawerOpen"
      :items="drawerItems"
      @close="drawerOpen = false"
      @logout="logout"
    />
  </div>
</template>

<style scoped>
.admin { position: relative; min-height: 100vh; min-height: 100dvh; z-index: 2; }
/* 导航栏高度做成变量：吸顶的页面栏与浮层要按「有没有栏」算自己的 top 偏移 */
.admin.has-bar { --admin-bar-h: 60px; }

.bar {
  position: sticky;
  top: 0;
  z-index: var(--z-sticky);
  background: var(--stone-200);
  background: color-mix(in srgb, var(--stone-200) 90%, transparent);
  backdrop-filter: saturate(1.3) blur(14px);
  -webkit-backdrop-filter: saturate(1.3) blur(14px);
}
.bar::after {
  content: '';
  position: absolute;
  left: 0; right: 0; bottom: 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--hairline) 12%, var(--hairline) 88%, transparent);
}
.bar-inner {
  max-width: 1400px;
  margin: 0 auto;
  height: var(--admin-bar-h, 60px);
  padding: 0 var(--sp-5);
  display: flex;
  align-items: center;
  gap: var(--sp-4);
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  text-decoration: none;
  font-family: var(--font-title);
  font-size: var(--fs-base);
  font-weight: var(--fw-semi);
  letter-spacing: .06em;
  color: var(--ink);
  white-space: nowrap;
  min-height: 44px;
}
.brand:hover { color: var(--ink); text-decoration: none; }
.mark { color: var(--ember); filter: drop-shadow(0 0 7px var(--ember-glow)); }
/* 「管理」两个字压成小标签：它是限定语，不该和站名一样重 */
.brand-sub {
  font-family: var(--font-body);
  font-size: var(--fs-micro);
  font-weight: var(--fw-normal);
  letter-spacing: .08em;
  color: var(--ink-3);
  background: var(--stone-400);
  border-radius: var(--r-xs);
  padding: 1px 6px;
}

/* 6 个 tab：宽屏一行铺开。手机端整条隐藏，改用抽屉 */
.nav {
  display: flex;
  gap: var(--sp-1);
  margin-left: var(--sp-3);
  flex: 1;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}
.nav::-webkit-scrollbar { display: none; }
.nav-link {
  position: relative;
  flex: none;
  white-space: nowrap;
  text-decoration: none;
  padding: 7px 12px;
  border-radius: var(--r-sm);
  font-size: var(--fs-sm);
  color: var(--ink-3);
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.nav-link:hover { color: var(--ink); background: var(--surface-hover); text-decoration: none; }
/* 激活态用底部刻线，和公开站顶栏同一套语言 */
.nav-link.active { color: var(--ember-hot); }
.nav-link.active::after {
  content: '';
  position: absolute;
  left: 12px; right: 12px; bottom: -2px;
  height: 2px;
  border-radius: 1px;
  background: var(--ember);
}

.bar-right { display: flex; align-items: center; gap: var(--sp-2); flex: none; margin-left: auto; }
.out {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  background: none;
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  color: var(--ink-3);
  font-size: var(--fs-xs);
  padding: 7px 12px;
  min-height: 36px;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease),
              background var(--dur-fast) var(--ease);
}
.out:hover { border-color: var(--blight); color: var(--blight-lift); background: var(--blight-veil); }

/* 手机端的菜单按钮：只在窄屏出现 */
.menu-btn {
  display: none;
  place-items: center;
  width: 38px;
  height: 38px;
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  background: var(--stone-400);
  color: var(--ink-2);
  cursor: pointer;
}

.admin-main { min-width: 0; }

@media (max-width: 760px) {
  .admin.has-bar { --admin-bar-h: 54px; }
  /* ⚠️ 用 sr-only（视觉隐藏、无障碍树里仍在）而不是 display: none：
     之前「退出」和站名一旦 display:none，按钮/链接就彻底没有名字了。 */
  /* 只藏「管理」这枚小标签：站名留着，否则左上角只剩一个火苗图标，认不出这是哪 */
  .brand-sub {
    position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
    overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
  }
  .bar-inner { gap: var(--sp-2); padding: 0 var(--sp-3); }
  .nav { display: none; }
  .out { display: none; }
  .menu-btn { display: grid; }
}
</style>