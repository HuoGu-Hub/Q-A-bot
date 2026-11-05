import { createRouter, createWebHistory } from 'vue-router'
import { beginVisit } from '@shared/api/cache'

export const router = createRouter({
  history: createWebHistory('/'),
  routes: [
    { path: '/', name: 'home', component: () => import('./views/HomeView.vue') },
    { path: '/library', name: 'library', component: () => import('./views/LibraryView.vue') },
    { path: '/entry/:title', name: 'entry', component: () => import('./views/EntryView.vue'), props: true },
    { path: '/map', name: 'map', component: () => import('./views/MapView.vue') },
    { path: '/plaza', redirect: '/library' },
    { path: '/about', name: 'about', component: () => import('./views/AboutView.vue') },
    { path: '/:pathMatch(.*)*', name: 'notfound', component: () => import('./views/NotFoundView.vue') },
  ],
  scrollBehavior(_to, _from, saved) {
    return saved ?? { top: 0 }
  },
})

router.afterEach(() => { beginVisit() })
