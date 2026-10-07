<script setup lang="ts">
import { RouterView, useRoute, useRouter } from 'vue-router'
import Toaster from '@shared/ui/Toaster.vue'
import Icon from '@shared/ui/Icon.vue'
import { computed } from 'vue'
import { useAuth } from './useAuth'

const route = useRoute()
const router = useRouter()
const auth = useAuth()

/**
 * 是否显示顶端导航栏。
 *
 * <p>只有登录页不显示。这里踩过坑：早先「数据大屏」是唯一隐藏导航的页面，
 * 而 /admin/ 又默认重定向到它，结果不熟悉的人打不开其余页面。导航是"路"，
 * 不该有让人迷路的例外 —— 即使那一页要满屏展示，也照样留着。
 */
const hasBar = computed(() => route.name !== 'login')
const nav = [
  { to: '/dashboard', label: '看板' },
  { to: '/records', label: '记录' },
  { to: '/kb', label: '知识库' },
  { to: '/system', label: '系统' },
  { to: '/bot', label: 'Bot 配置' },
  { to: '/plaza', label: '广场' },
  { to: '/pages', label: '页面信息' },
]

/**
 * 当前页面的名字，用来给屏幕阅读器一个 h1。
 *
 * <p>页面上**故意**不写可见的页面标题（顶端导航已经标出你在哪一页，再写一遍是重复），
 * 但无障碍树里的文档必须有一个 h1 —— 读屏用户靠"按标题跳转"在页面间穿行，
 * 没有 h1 的文档等于目录缺了第一行。所以这里补一个视觉隐藏的 h1，值跟着路由走。
 */
const currentLabel = computed(() => {
  if (!hasBar.value) return '管理员登录'
  const hit = nav.find(n => route.path.startsWith(n.to))
  return hit ? `${hit.label} · 飘雪喵管理后台` : '飘雪喵管理后台'
})

async function logout() {
  await auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="admin texture-grain" :class="{ 'has-bar': hasBar }">
    <header v-if="hasBar" class="bar">
      <div class="bar-inner">
        <RouterLink to="/dashboard" class="brand">
          <Icon name="flame" class="mark" :size="18" />
          <span class="brand-txt">飘雪喵</span>
          <span class="brand-sub">管理</span>
          <!-- 窄屏上 .brand-txt / .brand-sub 都被视觉隐藏了，链接就只剩一个图标 ——
               那会被判成"没有可读名字的链接"。这句只在读屏里出现，兜住站名。 -->
          <span class="sr-only">飘雪喵 管理后台</span>
        </RouterLink>
        <nav class="nav" aria-label="后台导航">
          <RouterLink v-for="n in nav" :key="n.to" :to="n.to" class="nav-link">{{ n.label }}</RouterLink>
        </nav>
        <button class="out" type="button" @click="logout">
          <Icon name="lock" :size="14" />
          <span class="out-txt">退出</span>
        </button>
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
  </div>
</template>

<style scoped>
.admin { position: relative; min-height: 100vh; z-index: 2; }
/* 导航栏高度做成变量：吸顶的页面栏与浮层要按"有没有栏"算自己的 top 偏移 */
.admin.has-bar { --admin-bar-h: 56px; }

.bar {
  position: sticky;
  top: 0;
  z-index: var(--z-sticky);
  /* 前一行是兜底：不认识 color-mix 的浏览器直接用不透明底，不会"透出内容重影" */
  background: var(--stone-200);
  background: color-mix(in srgb, var(--stone-200) 88%, transparent);
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
  height: var(--admin-bar-h, 56px);
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
}
.brand:hover { color: var(--ink); text-decoration: none; }
.mark { color: var(--ember); filter: drop-shadow(0 0 7px var(--ember-glow)); }
/* "管理"两个字压成小标签：它是**限定语**，不该和站名一样重 */
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

/* 7 个 tab：宽屏一行铺开，窄屏横向滑动 —— 既不换行堆两层，也不撑破布局 */
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
  padding: 6px 12px;
  border-radius: var(--r-sm);
  font-size: var(--fs-sm);
  color: var(--ink-3);
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.nav-link:hover { color: var(--ink); background: var(--surface-hover); text-decoration: none; }
/* 激活态用底部刻线，和公开站顶栏同一套语言（两边是同一个产品） */
.nav-link.router-link-active { color: var(--ember-hot); }
.nav-link.router-link-active::after {
  content: '';
  position: absolute;
  left: 12px; right: 12px; bottom: -2px;
  height: 2px;
  border-radius: 1px;
  background: var(--ember);
}
.out {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-left: auto;
  background: none;
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  color: var(--ink-3);
  font-size: var(--fs-xs);
  padding: 6px 12px;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease),
              background var(--dur-fast) var(--ease);
}
.out:hover { border-color: var(--blight); color: var(--blight-lift); background: var(--blight-veil); }

/* 手机上让出空间：品牌收起、tab 紧凑、内边距收窄 */
@media (max-width: 760px) {
  /* ⚠️ 用 sr-only（视觉隐藏、无障碍树里仍在）而不是 display: none。
     之前 "退出" 和站名一旦 display:none，按钮/链接就**彻底没有名字**了 ——
     axe 直接判 critical：Buttons must have discernible text。 */
  .brand-sub {
    position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
    overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
  }
  .brand-txt {
    position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
    overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
  }
  .bar-inner { gap: var(--sp-2); padding: 0 var(--sp-3); }
  .nav { margin-left: 0; }
  .nav-link { padding: 8px 10px; font-size: var(--fs-base); }
  .out { padding: 8px 10px; min-height: 40px; }
  .out-txt {
    position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
    overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
  }
}
</style>
