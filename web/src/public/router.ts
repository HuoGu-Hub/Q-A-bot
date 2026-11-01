import { createRouter, createWebHistory } from 'vue-router'
import { beginVisit } from '@shared/api/cache'

/**
 * 公开站路由。
 *
 * 用 history 模式（URL 好看、可分享）；部署时需要在 Nginx 配 fallback 到 index.html。
 */
export const router = createRouter({
  history: createWebHistory('/'),
  routes: [
    { path: '/', name: 'home', component: () => import('./views/HomeView.vue') },
    { path: '/library', name: 'library', component: () => import('./views/LibraryView.vue') },
    { path: '/entry/:title', name: 'entry', component: () => import('./views/EntryView.vue'), props: true },
    { path: '/map', name: 'map', component: () => import('./views/MapView.vue') },
    // 问答广场已下线，旧书签回资料库
    { path: '/plaza', redirect: '/library' },
    { path: '/about', name: 'about', component: () => import('./views/AboutView.vue') },
    { path: '/:pathMatch(.*)*', name: 'notfound', component: () => import('./views/NotFoundView.vue') },
  ],
  scrollBehavior(_to, _from, saved) {
    return saved ?? { top: 0 }
  },
})

/**
 * 每次换页标记一次「新的页面访问」。
 *
 * 缓存层据此区分「切回这一页」和「在这一页里又点了一次刷新」：
 * 前者吃缓存，后者照常请求。没有这一句，手动刷新会在 TTL 内拿到旧值。
 */
router.afterEach(() => { beginVisit() })
