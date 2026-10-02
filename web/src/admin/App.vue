<script setup lang="ts">
import { RouterView, useRoute, useRouter } from 'vue-router'
import Toaster from '@shared/ui/Toaster.vue'
import { computed } from 'vue'
import { useAuth } from './useAuth'

const route = useRoute()
const router = useRouter()
const auth = useAuth()

/**
 * 是否显示顶端导航栏。
 *
 * <p>只有登录页不显示。这里踩过坑：早先「数据大屏」是唯一隐藏导航的页面，
 * 而 `/admin/` 又默认重定向到它，结果不熟悉的人打不开其余页面。导航是"路"，
 * 不该有让人迷路的例外 —— 即使那一页要满屏展示，也照样留着。
 */
const hasBar = computed(() => route.name !== 'login')
const nav = [
  // 「大屏」已并入看板（/screen 重定向到 /dashboard）
  { to: '/dashboard', label: '看板' },
  { to: '/records', label: '记录' },
  // 「术语表」「知识库」「分类」合并成知识库页（页内二级目录）
  { to: '/kb', label: '知识库' },
  // 「模型」与「日志」合并成系统页（页内二级目录），顶端 tab 收成 8 个
  { to: '/system', label: '系统' },
  // 「指令」与「设置」合并成 Bot 配置（页内二级目录）
  { to: '/bot', label: 'Bot 配置' },
  { to: '/plaza', label: '广场' },
  { to: '/pages', label: '页面信息' },
]

async function logout() {
  await auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="admin" :class="{ 'has-bar': hasBar }">
    <header v-if="hasBar" class="bar">
      <div class="bar-inner">
        <span class="brand">
          <span class="mark" aria-hidden="true">✦</span> 飘雪喵 · 管理
        </span>
        <nav class="nav">
          <RouterLink v-for="n in nav" :key="n.to" :to="n.to" class="nav-link">{{ n.label }}</RouterLink>
        </nav>
        <button class="out" type="button" @click="logout">退出</button>
      </div>
    </header>
    <!-- 软弹窗容器：动作反馈（保存成功/失败）用浮层，不插进页面流 -->
    <Toaster />
    <RouterView v-slot="{ Component }">
      <component :is="Component" />
    </RouterView>
  </div>
</template>

<style scoped>
.admin { min-height: 100vh; }
/* 导航栏高度做成变量：吸顶的页面栏与浮层要按"有没有栏"算自己的 top 偏移 */
.admin.has-bar { --admin-bar-h: 56px; }

.bar {
  background: var(--bg-shroud);
  border-bottom: 1px solid var(--line);
  position: sticky;
  top: 0;
  z-index: var(--z-sticky);
}
.bar-inner {
  max-width: 1400px;
  margin: 0 auto;
  height: var(--admin-bar-h, 56px);
  padding: 0 var(--sp-4);
  display: flex;
  align-items: center;
  gap: var(--sp-4);
}
.brand {
  font-family: var(--font-title);
  font-size: var(--fs-md);
  letter-spacing: .04em;
  white-space: nowrap;
}
.mark { color: var(--flame); text-shadow: 0 0 10px var(--flame-glow); }
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
  flex: none;
  white-space: nowrap;
  padding: 6px 14px;
  border-radius: var(--r-sm);
  font-size: var(--fs-sm);
  color: var(--ink-dim);
  border: 1px solid transparent;
}
.nav-link:hover { color: var(--flame-bright); background: var(--flame-veil); }
.nav-link.router-link-active {
  color: var(--flame-bright);
  border-color: var(--copper-dim);
  background: var(--flame-veil);
}
.out {
  margin-left: auto;
  background: none;
  border: 1px solid var(--line-strong);
  border-radius: var(--r-sm);
  color: var(--ink-dim);
  font-size: var(--fs-xs);
  padding: 5px 12px;
  cursor: pointer;
}
.out:hover { border-color: var(--rust); color: var(--rust); }

/* 手机上让出空间：品牌收起、tab 紧凑、内边距收窄 */
@media (max-width: 720px) {
  .brand { display: none; }
  .bar-inner { gap: var(--sp-2); padding: 0 var(--sp-2); }
  .nav { margin-left: 0; }
  .nav-link { padding: 6px 10px; }
}
</style>

