import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from './useAuth'

/**
 * 管理后台路由。
 *
 * 除登录页外全部需要会话 —— 守卫在这里统一拦一道，
 * 后端也会独立校验（前端守卫只是体验优化，不是安全边界）。
 */
export const router = createRouter({
  history: createWebHistory('/admin/'),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/login', name: 'login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
    // 「大屏」和「看板」合并成一张页面（指标 8 个里 6 个重复、四块图完全一样）。
    // 旧路径保留重定向 —— 书签、肌肉记忆和挂在墙上的那个地址都不该因为一次合并失效。
    { path: '/screen', redirect: { path: '/dashboard' } },
    { path: '/dashboard', name: 'dashboard', component: () => import('./views/DashboardView.vue') },
    { path: '/records', name: 'records', component: () => import('./views/RecordsView.vue') },
    // 「关键词」「术语表」「分类」已合并为知识库页，用二级目录切换。
    // 旧的 /glossary 与 /keywords 都指向同一个 tab —— 后端把术语表收成 kb_term
    // 单表后，这两块本来就是同一批对象，redirect 到同一处正好说明这一点。
    { path: '/kb', name: 'kb', component: () => import('./views/KbView.vue') },
    { path: '/glossary', redirect: { path: '/kb', query: { tab: 'terms' } } },
    { path: '/keywords', redirect: { path: '/kb', query: { tab: 'terms' } } },
    { path: '/categories', redirect: { path: '/kb', query: { tab: 'category' } } },
    // 「模型」与「日志」已合并为系统页，页内用二级目录切换。
    // 旧路径保留重定向 —— 书签和肌肉记忆不该因为一次重构就失效。
    { path: '/system', name: 'system', component: () => import('./views/SystemView.vue') },
    { path: '/logs', redirect: { path: '/system', query: { tab: 'logs' } } },
    { path: '/models', redirect: { path: '/system', query: { tab: 'models' } } },
    // 「设置」与「指令」已合并为 Bot 配置，页内用二级目录切换。
    // 旧路径保留重定向 —— 书签和肌肉记忆不该因为一次重构就失效。
    { path: '/bot', name: 'bot', component: () => import('./views/BotConfigView.vue') },
    { path: '/settings', redirect: { path: '/bot', query: { tab: 'settings' } } },
    { path: '/commands', redirect: { path: '/bot', query: { tab: 'commands' } } },
    { path: '/plaza', name: 'plaza', component: () => import('./views/PlazaAdminView.vue') },
    // 公开站的固定文案（首页标语、关于页正文、页脚、404 等）
    { path: '/pages', name: 'pages', component: () => import('./views/PagesView.vue') },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  const auth = useAuth()
  if (!auth.checked.value) await auth.check()
  if (!auth.ok.value) return { name: 'login', query: { r: to.fullPath } }
  return true
})
