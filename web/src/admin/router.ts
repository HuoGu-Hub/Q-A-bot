import { createRouter, createWebHistory } from 'vue-router'
import { beginVisit } from '@shared/api/cache'
import { useAuth } from './useAuth'

const HOME: Array<[string, string]> = [
  ['stats', '/stats'],
  ['kb', '/kb'],
  ['map', '/map'],
  ['bot', '/bot'],
  ['pages', '/pages'],
  ['system', '/system'],
  ['users', '/users'],
]

function homePath() {
  const auth = useAuth()
  const hit = HOME.find(([area]) => auth.areaOk(area))
  return hit ? hit[1] : '/login'
}

export const router = createRouter({
  history: createWebHistory('/admin/'),
  routes: [
    { path: '/', redirect: { path: '/stats', query: { tab: 'overview' } } },
    { path: '/login', name: 'login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
    { path: '/bind-qq', name: 'bind-qq', component: () => import('./views/BindQqView.vue'), meta: { bind: true } },
    { path: '/stats', name: 'stats', component: () => import('./views/StatsView.vue'), meta: { area: 'stats' } },
    { path: '/screen', redirect: { path: '/stats', query: { tab: 'overview' } } },
    { path: '/dashboard', redirect: { path: '/stats', query: { tab: 'overview' } } },
    { path: '/records', redirect: { path: '/stats', query: { tab: 'records' } } },
    { path: '/eval', name: 'eval', component: () => import('./views/EvalView.vue'), meta: { area: 'eval' } },
    { path: '/eval/datasets/:id', name: 'eval-dataset', component: () => import('./views/EvalDatasetView.vue'), meta: { area: 'eval' } },
    { path: '/eval/trends', name: 'eval-trends', component: () => import('./views/EvalTrendsView.vue'), meta: { area: 'eval' } },
    { path: '/eval/alerts', name: 'eval-alerts', component: () => import('./views/EvalAlertsView.vue'), meta: { area: 'eval' } },
    { path: '/eval/prices', name: 'eval-prices', component: () => import('./views/EvalPricesView.vue'), meta: { area: 'eval' } },
    { path: '/eval/reviews', name: 'eval-reviews', component: () => import('./views/EvalReviewsView.vue'), meta: { area: 'eval-judge' } },
    { path: '/eval/batches/:id', name: 'eval-batch', component: () => import('./views/EvalBatchView.vue'), meta: { area: 'eval' } },
    { path: '/eval/runs/:id', name: 'eval-run', component: () => import('./views/EvalRunView.vue'), meta: { area: 'eval' } },
    { path: '/kb', name: 'kb', component: () => import('./views/KbView.vue'), meta: { area: 'kb' } },
    { path: '/glossary', redirect: { path: '/kb', query: { tab: 'terms' } } },
    { path: '/keywords', redirect: { path: '/kb', query: { tab: 'terms' } } },
    { path: '/categories', redirect: { path: '/kb', query: { tab: 'category' } } },
    { path: '/system', name: 'system', component: () => import('./views/SystemView.vue'), meta: { area: 'system' } },
    { path: '/logs', redirect: { path: '/system', query: { tab: 'logs' } } },
    { path: '/models', redirect: { path: '/system', query: { tab: 'models' } } },
    { path: '/bot', name: 'bot', component: () => import('./views/BotConfigView.vue'), meta: { area: 'bot' } },
    { path: '/settings', redirect: { path: '/bot', query: { tab: 'settings' } } },
    { path: '/commands', redirect: { path: '/bot', query: { tab: 'commands' } } },
    { path: '/plaza', redirect: { path: '/stats', query: { tab: 'overview' } } },
    { path: '/map', name: 'map', component: () => import('./views/MapAdminView.vue'), meta: { area: 'map' } },
    { path: '/pages', name: 'pages', component: () => import('./views/PagesView.vue'), meta: { area: 'pages' } },
    { path: '/users', name: 'users', component: () => import('./views/UsersView.vue'), meta: { area: 'users' } },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  const auth = useAuth()
  if (!auth.checked.value) await auth.check()
  if (!auth.ok.value) return { name: 'login', query: { r: to.fullPath } }

  const bind = Boolean(to.meta.bind)
  const needBind = !!auth.me.value?.qqRequired && !auth.me.value.qqVerified
  if (needBind && !bind) return { name: 'bind-qq' }
  if (bind && !needBind) return homePath()

  const need = to.meta.area as string | undefined
  if (need && !auth.areaOk(need)) return homePath()
  return true
})

router.afterEach(() => { beginVisit() })
