/**
 * 环境垫片 —— **不是被测代码的一部分**，只为绕开宿主 Vite dev 当前的一个缓存 bug。
 *
 * ## 症状
 * 打开 http://localhost:5173/ 或 /admin/kb，页面只剩顶端导航，router-view 渲染成
 * 一个空注释（<!---->），**0 console error**，加不加 mock 都一样。
 *
 * ## 根因（实测）
 * 宿主 Vite 的依赖预构建缓存自相矛盾 —— 同一个 chunk 被两个不同的 `?v=` 引用：
 *     vue.js        →  /node_modules/.vite/deps/chunk-2N2F6J7L.js?v=7e6f0953
 *     vue-router.js →  /node_modules/.vite/deps/chunk-2N2F6J7L.js?v=0f1fd56d
 * ESM 按「完整 URL」认模块，于是浏览器里装进了**两份 Vue 响应式系统**
 * （页内实测 `await import(a) === await import(b)` → false）。
 * vue-router 的 computed 属于 A 份、渲染 effect 属于 B 份，依赖收集跨了系统
 * → RouterView 的 computed 永远不再求值 → 路由内容永远不渲染。
 *
 * 这不是「词条」改造引入的：公开站首页同样中招。重启一次 Vite
 * （或删掉 web/node_modules/.vite 再启动）即可自愈 —— 那时本垫片变成恒等变换。
 *
 * ## 垫片做什么
 * 拦下 /node_modules/.vite/deps/*.js，把包内 import 里的 `chunk-xxx.js?v=...`
 * **统一去掉 query**，让所有引用落到同一个 URL ⇒ 同一份模块实例。
 * 只动 query 串，一行代码语义都不改。
 *
 * 另外顺带补了一个 `__VUE_HMR_RUNTIME__` 全局（同一次缓存故障的另一个后果，
 * 详见 installDepShim 里的注释）—— 没有它 /admin/pages 会因为 plugin-vue 生成的
 * HMR 尾巴而 ReferenceError 白屏。
 *
 * ⚠️ 为什么不用 route.fetch()：那个请求是 **Playwright 客户端（容器）** 发的，
 * 而 BASE 里的 localhost 只有宿主浏览器解析得到，容器里会 ECONNREFUSED。
 * 所以这里用 Node 的 fetch 打容器可达的地址（宿主网关 IP），再 fulfill 回浏览器。
 *
 * @param {import('playwright').Page} page
 * @param {{ fetchBase?: string }} [opts] 容器侧可访问的同一份 dev server 地址
 * @returns 计数器，用来在报告里如实说明垫片动了几次
 */
function installDepShim(page, opts = {}) {
  const fetchBase = (opts.fetchBase || '').replace(/\/+$/, '')
  const stat = { seen: 0, rewritten: 0, samples: [], fetched: 0, failed: 0 }
  const cache = new Map()

  // 第二个环境缺陷：宿主 Vite 预构建出来的 vue chunk 里，dev-only 的
  // `if (__DEV__) { getGlobalThis().__VUE_HMR_RUNTIME__ = {...} }` 被折成了 `if (false)`，
  // 于是这个全局根本不存在；而 @vitejs/plugin-vue 为「最近改过的那个 SFC」生成的
  // HMR 尾巴里有一句**不加保护**的 `__VUE_HMR_RUNTIME__.CHANGED_FILE`，
  // 一执行就 ReferenceError，那个页面直接白屏（实测 /admin/pages）。
  // 这里按 plugin-vue 期望的接口补一个惰性实现（测试里不做 HMR，空实现即可）。
  page.addInitScript(() => {
    if (!window.__VUE_HMR_RUNTIME__) {
      window.__VUE_HMR_RUNTIME__ = {
        CHANGED_FILE: null,
        createRecord() {},
        rerender() {},
        reload() {},
      }
    }
  })

  page.route('**/node_modules/.vite/deps/*.js*', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('.map')) return route.continue()
    stat.seen++
    try {
      const key = url.pathname
      let before = cache.get(key)
      if (before === undefined) {
        const target = (fetchBase || (url.origin)) + url.pathname + url.search
        const res = await fetch(target)
        if (!res.ok) { stat.failed++; return route.continue() }
        before = await res.text()
        cache.set(key, before)
        stat.fetched++
      }
      const after = before.replace(/(chunk-[A-Za-z0-9_-]+\.js)\?v=[0-9a-f]+/g, '$1')
      if (after !== before) {
        stat.rewritten++
        if (stat.samples.length < 4) {
          const hits = [...new Set(before.match(/chunk-[A-Za-z0-9_-]+\.js\?v=[0-9a-f]+/g) || [])]
          stat.samples.push(url.pathname.split('/').pop() + ' → ' + hits.join(' , '))
        }
      }
      await route.fulfill({ status: 200, contentType: 'application/javascript', body: after })
    } catch (e) {
      stat.failed++
      return route.continue()
    }
  })
  return stat
}

module.exports = { installDepShim }
