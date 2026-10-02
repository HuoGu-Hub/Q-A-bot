/**
 * 全站布局重构（两条工具栏 → 一条吸顶栏）的真机验收脚本。
 *
 * 检查的不是「好不好看」，而是本次重构承诺的几件**客观事实**：
 *   ① 9 个后台页面都不再有 page-head，且只剩一条工具栏（page-bar）
 *   ② Bot 配置页的 tabs 与按钮在**同一个** .page-bar 里（同一个父元素）
 *   ③ 滚到底时 .page-bar 仍贴在顶端导航下沿（不是负数、不是飘走）
 *   ④ 视图通过 ref 真能调到面板内部的动作（KeepAlive 切换后依然拿得到）
 *   ⑤ 全程 0 console error
 *
 * 用法：
 *   node tests/verify-redesign-t9.js
 *   BASE=http://localhost:8080 node tests/verify-redesign-t9.js
 *
 * 宿主机 Chrome 需带 --remote-debugging-port=9222 启动。
 * 跑完关掉自己开的标签页，不动用户正在看的页面。
 */
const fs = require('node:fs')
const path = require('node:path')
const { connectCDP } = require('/root/.playwright/cdp')
const { installDepShim } = require('./lib/dep-shim.cjs')

const ROOT = path.resolve(__dirname, '..')
/** 容器视角的宿主地址：只给依赖垫片取源码用，不给浏览器用 */
const HOST_IP = (() => {
  try {
    return require('node:child_process')
      .execSync("getent ahostsv4 host.docker.internal | awk '{print $1}' | head -n1", { encoding: 'utf8' }).trim()
  } catch { return '' }
})()
/**
 * MOCK_API=1 时把 /admin/api 全部拦下来喂假数据，用宿主的 Vite dev（5173）跑。
 *
 * 为什么需要这个模式：验收要的是**布局 / 吸顶 / ref** 这些前端行为，
 * 而后端不在时页面根本渲染不出来（守卫会把人踢回登录页）。
 * 这个模式只换数据，浏览器、Vue 运行时、DOM、样式、事件全是真的。
 * 后端在的时候请用默认模式（8080，不 mock）跑一遍，那才是完整口径。
 *
 * 2026-09-27：BASE 指向 5173（宿主 Vite dev，服务的正是本工作区源码）时**自动**进 mock ——
 * 那个端口背后没有后端；而宿主 8080 上跑的是改造前的旧代码（旧的关键词 / 术语表两个
 * 面板还是分开的两套接口），打真接口只会拿到 404。所以这个脚本一律走 mock。
 */
const MOCK = process.env.MOCK_API === '1' || /:5173\b/.test(process.env.BASE || '')
/**
 * BASE 是给**宿主浏览器**打开用的。容器视角的宿主网关 IP 在宿主上反而连不上
 * （实测 ERR_CONNECTION_TIMED_OUT），所以出现网关 IP 就换回 localhost。
 */
const RAW_BASE = process.env.BASE || (MOCK ? 'http://localhost:5173' : 'http://localhost:8080')
const BASE = HOST_IP ? RAW_BASE.split(HOST_IP).join('localhost') : RAW_BASE
const SHOT_DIR = path.join(ROOT, 'tests/screenshots/redesign')
const WIDE = { width: 1440, height: 900 }

/** 假的接口数据：形状与 @shared/api/types 对齐，只保页面能渲染出真实结构 */
function mockBody(pathname) {
  const day = (n, c) => ({ day: n, count: c })
  const daily = Array.from({ length: 14 }, (_, i) => day(`2026-09-${String(i + 1).padStart(2, '0')}`, 3 + i))
  const overview = {
    total: 1163, questions: 72, hits: 51, hitRate: 0.71, users: 18, groups: 4,
    dropped: 9, fixedReplies: 3, commands: 12,
    p50RetrieveMs: 41, p95RetrieveMs: 180, p50TotalMs: 900, p95TotalMs: 2600,
    guardActions: { pass: 72, 'no-mention': 900, 'rate-limit': 9 },
    daily,
  }
  const visits = { total: 420, visitors: 96, daily: daily.map((d, i) => day(d.day, 10 + i)), paths: [{ source: '/library', count: 120, hitRate: 0 }] }

  // 设置页：8 个分组，确保长到必须滚动（吸顶实测需要真的能滚）
  const settingsGroups = {}
  for (let g = 1; g <= 8; g++) {
    settingsGroups[`分组 ${g}`] = Array.from({ length: 6 }, (_, i) => ({
      key: `app.group${g}.item${i}`,
      label: `配置项 ${g}-${i}`,
      hint: '一行说明',
      type: i % 3 === 0 ? 'bool' : i % 3 === 1 ? 'int' : 'string',
      value: i % 3 === 0 ? true : i % 3 === 1 ? 100 + i : 'text',
    }))
  }

  const table = {
    '/session': {},
    '/settings': { groups: settingsGroups },
    '/commands': {
      commands: [
        { id: 1, trigger: 'help', reply: '可用指令：{cmd.list}', description: '指令列表', scope: 'all', enabled: true, builtin: true, sortOrder: 0 },
        { id: 2, trigger: 'wiki', reply: '查资料：{query}', description: '查 Wiki', scope: 'group', enabled: true, builtin: false, sortOrder: 1 },
      ],
    },
    '/commands/variables': { variables: [{ name: 'user.name', label: '昵称', example: '飘雪', advanced: false }] },
    '/commands/stats': { topUsed: [{ trigger: 'help', count: 31 }], unmatched: [{ trigger: 'roll', count: 2 }] },
    // 词条层：一个列表承载「名字 + 正文 + 核对状态」，所以只有这一个取数接口
    '/kb/terms': {
      total: 40,
      items: Array.from({ length: 40 }, (_, i) => ({
        en: i === 0 ? 'Flame Altar' : `Entry ${String(i).padStart(2, '0')}`,
        zh: i === 0 ? '灵火祭坛、火焰祭坛' : `词条 ${i}`,
        aliases: i === 0 ? ['灵火祭坛', '火焰祭坛'] : [`词条 ${i}`],
        status: i % 3 === 0 ? 'draft' : 'verified',
        board: 'craft', boardLabel: '制作', cats: ['Crafting'], chunkCount: 2, chars: 512,
        url: 'https://enshrouded.wiki.gg/wiki/Flame_Altar',
        retired: false,
      })),
      counts: { all: 3630, main: 3600, withChunks: 3600, noChunk: 30, draft: 3358, verified: 240, rejected: 12, unnamed: 30 },
      boards: [
        { key: 'craft', label: '制作', icon: '🔨', desc: '制作', terms: 420, chunks: 1800 },
        { key: 'mob', label: '怪物', icon: '👹', desc: '怪物', terms: 180, chunks: 900 },
      ],
      available: true,
    },
    '/kb/terms/chunks': { title: 'Flame Altar', chunks: [] },
    '/kb/categories': {
      items: Array.from({ length: 40 }, (_, i) => ({
        raw: i === 0 ? 'Crafting' : `RawCat${i}`, count: 5 + i,
        groupKey: i % 2 ? 'mob' : 'craft', labelZh: i % 3 ? '' : `标签${i}`, custom: i % 4 === 0,
      })),
      groups: [{ key: 'craft', label: '制作', icon: '🔨' }, { key: 'mob', label: '怪物', icon: '👹' }],
      stats: [{ key: 'craft', label: '制作', icon: '🔨', rawCount: 5, chunkCount: 1200 }, { key: 'mob', label: '怪物', icon: '👹', rawCount: 3, chunkCount: 800 }],
      customCount: 1,
    },
    '/kb/proposals/stats': { threshold: 20, pendingGood: 5, canAnalyze: false, pending: 2, approved: 1, rejected: 0, available: true },
    '/kb/proposals': {
      rows: [{
        id: 7, createdAt: '2026-09-20T10:00:00', status: 'pending', kind: 'overwrite', title: 'Flame Altar',
        question: '灵火祭坛在哪', currentText: '旧文本', proposedText: '新文本', reason: '玩家补充了坐标',
        sourceStatId: 12, reviewedAt: null, reviewedBy: null,
      }],
      status: 'pending',
    },
    '/kb/overview': {
      indexChunks: 12345, indexDimensions: 1024, counts: { pending: 3, approved: 2, indexed: 12340 },
      tokens: 1, embeddingModel: 'BAAI/bge-m3',
      publicSearch: { usedToday: 5, dailyLimit: 50, cached: 2, vectorEnabled: true },
    },
    '/kb/contributions': { items: [] },
    '/kb/tokens': { tokens: [] },
    '/overview': overview,
    '/keywords': [{ zh: '灵火祭坛', en: 'Flame Altar', count: 22, missCount: 0 }, { zh: '深渊行者', en: 'Abysswalker', count: 9, missCount: 2 }],
    '/misses': {
      withKeyword: [{ zh: '灵火祭坛', en: 'Flame Altar', count: 3, bestCosineRaw: 0.41 }],
      unmatched: [{ ts: '2026-09-20T10:00:00', question: '怎么修装备', bestCosineRaw: 0.12 }],
    },
    '/cosine': [{ range: '0.3-0.4', count: 4 }, { range: '0.4-0.5', count: 12 }, { range: '0.5-0.6', count: 30 }],
    '/sources': { sources: [{ source: 'vector', count: 40, hitRate: 0.8 }], verdicts: [{ verdict: 'good', count: 12 }] },
    '/visits': visits,
    '/guard/metrics': {
      inbound: 1200, passed: 72, blocked: 9, blockRate: 0.007,
      byStage: { 'rate-limit': 9 }, replyByStage: { 'rate-limit': 9 },
      topBlockedUsers: [{ userId: 10001, count: 5 }], uptimeSeconds: 3600,
      budget: { day: '2026-09-20', globalCalls: 72, globalTokens: 123456, trackedUsers: 18, trackedGroups: 4, globalPerDay: 0, globalTokensPerDay: 0 },
    },
    '/records': { rows: [], total: 0, scope: 'questions' },
    '/models': {
      defaultProvider: 'opencode',
      fallbackChain: ['opencode', 'siliconflow'],
      providers: [{
        name: 'opencode', modelName: 'deepseek-v4.1-flash', baseUrl: 'https://api.example.com/v1',
        ready: true, configured: true, capabilities: ['chat', 'vision'], temperature: 0.3,
        timeoutSeconds: 60, maxTokens: 2048, reasoningEffort: 'medium',
      }],
    },
    '/logs/status': {
      enabled: true, bufferSize: 120, capacity: 2000, totalWritten: 9000, dropped: 0,
      maskSensitive: true, currentSeq: 9000, activeStreams: 1, napcatAvailable: false, napcatDir: 'deploy/data/napcat/logs',
    },
    '/logs/history': {
      entries: [{ seq: 1, ts: '2026-09-20T10:00:00', level: 'INFO', logger: 'c.q.Bot', message: '启动完成' }],
    },
    '/pages': {
      pages: {
        home: [
          { page: 'home', pageLabel: '首页', key: 'hero', label: '主标题', hint: '首页大标题', multiline: false, text: '雾锁王国助手', defaultText: '雾锁王国助手', overridden: false },
          { page: 'home', pageLabel: '首页', key: 'lead', label: '副标题', hint: '', multiline: true, text: '问点什么', defaultText: '问点什么', overridden: true },
        ],
        about: [
          { page: 'about', pageLabel: '关于', key: 'body', label: '正文', hint: '', multiline: true, text: '关于本站', defaultText: '关于本站', overridden: false },
        ],
      },
      pageLabels: { home: '首页', about: '关于' },
    },
    '/plaza/overview': {
      voteCount: 0, helpCount: 0, downvoteThreshold: 3, enabled: true, onlyVoted: true,
      usage: { askTodayTotal: 0, askLimitGlobal: 10 },
    },
    '/plaza/answers': { answers: [] },
    '/plaza/help': { requests: [] },
    '/screen': { overview, keywords: [{ zh: '灵火祭坛', en: 'Flame Altar', count: 22, missCount: 0 }], misses: table0(), cosine: [{ range: '0.4-0.5', count: 12 }], sources: { sources: [{ source: 'vector', count: 40, hitRate: 0.8 }], verdicts: [] }, visits, generatedAt: '2026-09-20T10:00:00' },
  }
  return table[pathname]
}
// /screen 里的 misses 用同一个形状（写在函数外会被覆盖成 undefined，所以单独给）
function table0() {
  return { withKeyword: [{ zh: '灵火祭坛', en: 'Flame Altar', count: 3, bestCosineRaw: 0.41 }], unmatched: [] }
}

async function installMocks(page) {
  // EventSource 没法用 route 模拟成"永不结束的流"，直接换成假的实现，
  // 免得反复重连刷一屏网络报错（这里只看布局，日志内容与本任务无关）
  await page.addInitScript(() => {
    class FakeES {
      constructor() { this.onerror = null; setTimeout(() => this.onerror && this.onerror({}), 0) }
      addEventListener() {}
      close() {}
    }
    window.EventSource = FakeES
  })

  await page.route('**/admin/api/**', async (route) => {
    const u = new URL(route.request().url())
    const pathname = u.pathname.replace(/^\/admin\/api/, '')
    // 给指令取数故意加延迟：为了抓到页面栏按钮的「读取中…」中间态
    if (pathname === '/commands' || pathname === '/kb/terms') await new Promise((r) => setTimeout(r, 600))
    const body = mockBody(pathname)
    if (body === undefined) {
      console.log(`   ⚠️ mock 未覆盖：${u.pathname}${u.search}`)
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

function readPassword() {
  try {
    const txt = fs.readFileSync(path.join(ROOT, '.env'), 'utf8')
    const m = txt.match(/^\s*ADMIN_PASSWORD\s*=\s*(.*)$/m)
    return m ? m[1].trim().replace(/^["']|["']$/g, '') : ''
  } catch { return '' }
}

const PAGES = [
  { name: 'bot', path: '/admin/bot' },
  { name: 'kb', path: '/admin/kb' },
  { name: 'dashboard', path: '/admin/dashboard' },
  { name: 'records', path: '/admin/records' },
  { name: 'logs', path: '/admin/logs' },
  { name: 'models', path: '/admin/models' },
  { name: 'plaza', path: '/admin/plaza' },
  { name: 'screen', path: '/admin/screen' },
  { name: 'pages', path: '/admin/pages' },
]

const results = []
function check(name, pass, detail) {
  results.push({ name, pass, detail })
  console.log(`${pass ? '✅' : '❌'} ${name}${detail ? '  — ' + detail : ''}`)
}

/** 逐页实测数字，最后打成表 */
const perPage = []

/** 面板里的点击：用 DOM click，避免宿主窗口在后台时 Playwright 判定"不可见"而超时 */
async function domClick(page, selector, containsText) {
  return page.evaluate(({ selector, containsText }) => {
    const nodes = [...document.querySelectorAll(selector)]
    const hit = containsText
      ? nodes.find((n) => (n.textContent || '').replace(/\s+/g, '').includes(containsText))
      : nodes[0]
    if (!hit) return false
    hit.click()
    return true
  }, { selector, containsText })
}

;(async () => {
  fs.mkdirSync(SHOT_DIR, { recursive: true })
  const password = readPassword()

  const browser = await connectCDP()
  const ctx = browser.contexts()[0]
  const page = await ctx.newPage()
  await page.setViewportSize(WIDE)

  if (MOCK) await installMocks(page)
  // 环境垫片：宿主 Vite 依赖缓存自相矛盾 → 双份 Vue 响应式 → 前端路由整个不渲染。
  // 不装的话这个脚本一条都过不了（原因与自愈办法见 lib/dep-shim.cjs）
  const shim = installDepShim(page, { fetchBase: HOST_IP ? BASE.replace('//localhost', '//' + HOST_IP) : BASE })
  console.log(`模式：${MOCK ? 'MOCK_API（接口喂假数据，布局/交互/ref 全真跑）' : '真实后端'}  BASE=${BASE}\n`)

  // 每个页面各自的 console error 桶
  let bucket = []
  page.on('console', (m) => { if (m.type() === 'error') bucket.push(m.text().slice(0, 300)) })
  page.on('pageerror', (e) => bucket.push('pageerror: ' + String(e.message || e).slice(0, 300)))

  // CDP：清缓存，否则看到的是旧页面
  const cdp = await ctx.newCDPSession(page)
  await cdp.send('Network.enable')
  await cdp.send('Network.clearBrowserCache')

  const requests = []
  page.on('request', (r) => requests.push(r.url()))

  try {
    // ---------- 登录 ----------
    if (!MOCK) {
      if (!password) throw new Error('没读到 .env 里的 ADMIN_PASSWORD')
      await page.goto(BASE + '/admin/login', { waitUntil: 'domcontentloaded', timeout: 20000 })
      await page.waitForTimeout(1200)
      if (await page.evaluate(() => !!document.querySelector('input[type=password]'))) {
        await page.fill('input[type=password]', password)
        await Promise.all([
          page.waitForURL((u) => !String(u).includes('/login'), { timeout: 15000 }).catch(() => {}),
          page.evaluate(() => document.querySelector('form').requestSubmit()),
        ])
        await page.waitForTimeout(2000)
      }
      const loggedIn = !(await page.evaluate(() => !!document.querySelector('input[type=password]')))
      check('后台登录', loggedIn, loggedIn ? '已进入后台' : '仍在登录页')
    }

    // ---------- ① ② ③ 逐页检查 ----------
    for (const spec of PAGES) {
      bucket = []
      requests.length = 0
      await page.goto(BASE + spec.path, { waitUntil: 'domcontentloaded', timeout: 30000 })
      await page.waitForTimeout(2600)

      const snap = await page.evaluate(() => {
        const bars = [...document.querySelectorAll('.page-bar')]
        const first = bars[0]
        const header = document.querySelector('header.bar')
        const headerH = header ? Math.round(header.getBoundingClientRect().height) : 0
        const topAt0 = first ? Math.round(first.getBoundingClientRect().top) : null
        return {
          pageHead: document.querySelectorAll('.page-head').length,
          pageBar: bars.length,
          legacyToolbar: document.querySelectorAll('.panel-bar, .tab-actions').length,
          headerH,
          topAt0,
          hasTabs: !!document.querySelector('.page-bar .tabs'),
          loginForm: !!document.querySelector('input[type=password]'),
          barText: first ? (first.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 120) : '',
        }
      })

      check(`${spec.name}: 页面真的渲染出来了（未被弹回登录页）`, !snap.loginForm && snap.pageBar === 1,
        snap.loginForm ? '被弹回登录页' : `page-bar 存在，栏内文字「${snap.barText}」`)

      check(`${spec.name}: 无 page-head`, snap.pageHead === 0, `page-head 数 = ${snap.pageHead}`)

      // 工具栏唯一性：有内容的页面必须恰好一条 page-bar，且没有遗留的面板工具栏
      const expectBar = ['bot', 'kb', 'dashboard', 'records', 'logs', 'models', 'plaza', 'screen', 'pages'].includes(spec.name)
      check(`${spec.name}: 只有一条工具栏`,
        snap.pageBar === (expectBar ? 1 : 0) && snap.legacyToolbar === 0,
        `page-bar = ${snap.pageBar}，遗留 .panel-bar/.tab-actions = ${snap.legacyToolbar}`)

      // 吸顶：滚到底再量
      await page.evaluate(() => window.scrollTo(0, 99999))
      await page.waitForTimeout(400)
      const sticky = await page.evaluate(() => {
        const bar = document.querySelector('.page-bar')
        const header = document.querySelector('header.bar')
        return {
          top: bar ? Math.round(bar.getBoundingClientRect().top) : null,
          headerH: header ? Math.round(header.getBoundingClientRect().height) : 0,
          scrollY: Math.round(window.scrollY),
          scrollable: document.documentElement.scrollHeight > window.innerHeight + 2,
        }
      })
      const stickyOk = sticky.top !== null && sticky.top >= 0 && Math.abs(sticky.top - sticky.headerH) <= 2
      check(`${spec.name}: 滚到底仍吸顶`, stickyOk,
        `page-bar.top = ${sticky.top}（顶端导航高 ${sticky.headerH}，scrollY ${sticky.scrollY}${sticky.scrollable ? '' : '，本页不足一屏'}）`)

      perPage.push({
        page: spec.path, pageHead: snap.pageHead, pageBar: snap.pageBar,
        legacy: snap.legacyToolbar, navH: sticky.headerH, stickyTop: sticky.top,
        scrollY: sticky.scrollY, scrollable: sticky.scrollable, err: bucket.length,
      })

      check(`${spec.name}: 0 console error`, bucket.length === 0, bucket.length ? bucket[0] : '干净')

      await page.evaluate(() => window.scrollTo(0, 0))
      await page.waitForTimeout(300)
      await page.screenshot({ path: path.join(SHOT_DIR, `t9-${spec.name}.png`) })
    }

    // ---------- ② Bot 页：tabs 与按钮同一个父 ----------
    bucket = []
    await page.goto(BASE + '/admin/bot', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(2600)
    const sameParent = await page.evaluate(() => {
      const bar = document.querySelector('.page-bar')
      if (!bar) return { ok: false, why: '没有 .page-bar' }
      const tabs = bar.querySelector('.bar-tabs .tabs')
      const actions = bar.querySelector('.bar-actions')
      const btns = actions ? [...actions.querySelectorAll('button')] : []
      const tabsBox = tabs ? tabs.parentElement.parentElement : null   // .bar-tabs 的父 = .page-bar
      const actBox = actions ? actions.parentElement : null
      return {
        ok: !!tabs && !!actions && btns.length > 0 && tabsBox === bar && actBox === bar && bar.querySelectorAll('.page-bar').length === 0,
        barClass: bar.className,
        barHtml: bar.outerHTML.replace(/\s+/g, ' ').slice(0, 320),
        tabsParent: tabsBox ? `${tabsBox.tagName.toLowerCase()}.${tabsBox.className}` : '(无)',
        actionsParent: actBox ? `${actBox.tagName.toLowerCase()}.${actBox.className}` : '(无)',
        btnTexts: btns.map((b) => (b.textContent || '').trim()),
        pageHead: document.querySelectorAll('.page-head').length,
      }
    })
    check('bot: tabs 与按钮在同一个 .page-bar', sameParent.ok,
      `tabs 的父 = ${sameParent.tabsParent}；按钮组的父 = ${sameParent.actionsParent}；同一父元素 = ${sameParent.tabsParent === sameParent.actionsParent && sameParent.tabsParent !== '(无)'}`)
    console.log(`     父元素 class：${sameParent.barClass}`)
    console.log(`     按钮：${JSON.stringify(sameParent.btnTexts)}`)
    console.log(`     外层 HTML 片段：${sameParent.barHtml}`)
    await page.screenshot({ path: path.join(SHOT_DIR, 't9-bot-settings.png') })

    // ---------- ④ ref 真的能调到面板 ----------
    // A. 指令 tab：点页面栏「刷新」→ 面板必须真的重新取数，且按钮要立刻变成「读取中…」
    //    （mock 里给 /commands 故意加了 600ms 延迟，就是为了抓这个中间态 ——
    //     它证明 isLoading() 这类读数确实穿过了 ref 回流到页面栏）
    let ok = await domClick(page, '.page-bar .tab', '指令')
    await page.waitForTimeout(2200)
    requests.length = 0
    const cmdBefore = await page.evaluate(() => ({
      rows: document.querySelectorAll('table.tbl tbody tr').length,
      btn: [...document.querySelectorAll('.page-bar .bar-actions button')].map((b) => (b.textContent || '').trim()),
      disabled: [...document.querySelectorAll('.page-bar .bar-actions button')].map((b) => b.disabled),
    }))
    ok = ok && await domClick(page, '.page-bar .bar-actions button', '刷新')
    await page.waitForTimeout(150)
    const cmdMid = await page.evaluate(() => ({
      btn: [...document.querySelectorAll('.page-bar .bar-actions button')].map((b) => (b.textContent || '').trim()),
      disabled: [...document.querySelectorAll('.page-bar .bar-actions button')].map((b) => b.disabled),
    }))
    await page.waitForTimeout(2000)
    const cmdAfter = await page.evaluate(() => ({
      rows: document.querySelectorAll('table.tbl tbody tr').length,
      btn: [...document.querySelectorAll('.page-bar .bar-actions button')].map((b) => (b.textContent || '').trim()),
      disabled: [...document.querySelectorAll('.page-bar .bar-actions button')].map((b) => b.disabled),
    }))
    const cmdReqs = requests.filter((u) => u.includes('/admin/api/commands'))
    check('bot/指令: 页面栏「刷新」触发了面板取数', ok && cmdReqs.length > 0,
      `按钮点中=${ok}，/admin/api/commands 请求 ${cmdReqs.length} 个；面板表格 ${cmdBefore.rows} 行 → ${cmdAfter.rows} 行`)
    check('bot/指令: 读数真的从面板回流到页面栏（取数中按钮立刻禁用）',
      cmdMid.disabled[0] === true && cmdAfter.btn[0] === '刷新' && !cmdAfter.disabled[0],
      `点击前按钮=${JSON.stringify(cmdBefore.btn)}（disabled=${JSON.stringify(cmdBefore.disabled)}）；点击后 150ms 按钮=${JSON.stringify(cmdMid.btn)}（disabled=${JSON.stringify(cmdMid.disabled)}）—— 面板 isLoading() 经 ref 传到页面栏；取数完成后 disabled=${JSON.stringify(cmdAfter.disabled)}`)
    await page.screenshot({ path: path.join(SHOT_DIR, 't9-bot-commands.png') })

    // B. 切回设置（KeepAlive 重新激活）→ 点「全部展开」→ 折叠分组必须真的展开
    await domClick(page, '.page-bar .tab', '设置')
    await page.waitForTimeout(2200)
    const before = await page.evaluate(() => ({
      all: document.querySelectorAll('.panel--collapsible').length,
      closed: document.querySelectorAll('.panel--collapsible.panel--closed').length,
      fields: document.querySelectorAll('.panel-body .field, .panel-body .fld').length,
      bar: (document.querySelector('.page-bar') || {}).innerText || '',
    }))
    const clicked = await domClick(page, '.page-bar .bar-actions button', '全部展开')
    await page.waitForTimeout(900)
    const after = await page.evaluate(() => ({
      all: document.querySelectorAll('.panel--collapsible').length,
      closed: document.querySelectorAll('.panel--collapsible.panel--closed').length,
    }))
    check('bot/设置: 切回后页面栏「全部展开」仍能驱动面板',
      clicked && before.all > 0 && after.closed === 0 && before.closed === before.all,
      `点中=${clicked}，设置面板渲染出可折叠分组 ${before.all} 个（收起 ${before.closed} 个）→ 点击后收起 ${after.closed} 个`)
    await page.screenshot({ path: path.join(SHOT_DIR, 't9-bot-settings-expanded.png') })

    // B2. 在设置面板改一项 → 页面栏的「已改 N 项」徽标要出现（面板状态 → 页面栏）
    const dirty = await page.evaluate(() => {
      const sw = document.querySelector('.panel-body input[type=checkbox], .panel-body .switch')
      if (!sw) return { ok: false, why: '没找到可改控件' }
      sw.click()
      return { ok: true }
    })
    await page.waitForTimeout(600)
    const badge = await page.evaluate(() => {
      const bar = document.querySelector('.page-bar')
      const tag = [...bar.querySelectorAll('*')].find((n) => /^已改\s*\d+\s*项$/.test((n.textContent || '').trim()))
      return { text: tag ? tag.textContent.trim() : '' }
    })
    check('bot/设置: 面板改动计数出现在页面栏', dirty.ok && /已改\s*\d+\s*项/.test(badge.text),
      `改了 1 个开关后，页面栏出现「${badge.text || '(无)'}」`)
    await page.screenshot({ path: path.join(SHOT_DIR, 't9-bot-settings-dirty.png') })

    // C. 反向再切一次（设置 → 指令 → 设置），确认 ref 反复切换都跟得上，
    //    同时确认 KeepAlive 的改动缓冲没有因为切 tab 丢掉（这是不能删 KeepAlive 的理由）
    await domClick(page, '.page-bar .tab', '指令')
    await page.waitForTimeout(1500)
    requests.length = 0
    await domClick(page, '.page-bar .bar-actions button', '刷新')
    await page.waitForTimeout(2200)
    const cmdReqs2 = requests.filter((u) => u.includes('/admin/api/commands'))
    check('bot: 反复切换 tab 后 ref 仍指向当前面板', cmdReqs2.length > 0,
      `第二次「刷新」触发 /admin/api/commands 请求 ${cmdReqs2.length} 个`)

    await domClick(page, '.page-bar .tab', '设置')
    await page.waitForTimeout(1600)
    const kept = await page.evaluate(() => {
      const bar = document.querySelector('.page-bar')
      const tag = [...bar.querySelectorAll('*')].find((n) => /^已改\s*\d+\s*项$/.test((n.textContent || '').trim()))
      const sw = document.querySelector('.panel-body input[type=checkbox]')
      return { badge: tag ? tag.textContent.trim() : '', switchOn: !!(sw && sw.checked) }
    })
    check('bot: 切走再切回，未保存的改动仍在（KeepAlive 生效）',
      /已改\s*1\s*项/.test(kept.badge),
      `切到「指令」再切回「设置」后，页面栏仍显示「${kept.badge || '(无)'}」，刚才打开的开关状态 = ${kept.switchOn}`)

    check('bot: 0 console error', bucket.length === 0, bucket.length ? bucket[0] : '干净')

    // ---------- 知识库页：五个面板共用一个 ref 的抽查 ----------
    bucket = []
    await page.goto(BASE + '/admin/kb?tab=category', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(2600)
    requests.length = 0
    await domClick(page, '.page-bar .bar-actions button', '重新载入')
    await page.waitForTimeout(2200)
    const catReqs = requests.filter((u) => u.includes('/admin/api/kb/categories'))
    check('kb/分类: 页面栏「重新载入」触发了面板取数', catReqs.length > 0,
      `/admin/api/kb/categories 请求 ${catReqs.length} 个`)
    await page.screenshot({ path: path.join(SHOT_DIR, 't9-kb-category.png') })

    await domClick(page, '.page-bar .tab', '词条')
    await page.waitForTimeout(2000)
    const modeBtns = await page.evaluate(() =>
      [...document.querySelectorAll('.page-bar .bar-actions button')].map((b) => (b.textContent || '').trim()))
    await domClick(page, '.page-bar .bar-actions button', '核对模式')
    await page.waitForTimeout(1800)
    const reviewOn = await page.evaluate(() => {
      const bar = document.querySelector('.page-bar')
      const prog = bar ? (bar.innerText || '').replace(/\s+/g, '') : ''
      return { prog, card: !!document.querySelector('.card-en') }
    })
    check('kb/词条: 页面栏模式切换驱动面板（核对模式）',
      modeBtns.some((t) => t.includes('列表')) && reviewOn.card && reviewOn.prog.includes('核对进度'),
      `按钮=[${modeBtns.join(' | ')}]，切换后出现核对卡=${reviewOn.card}，栏内读数含「核对进度」=${reviewOn.prog.includes('核对进度')}`)
    await page.screenshot({ path: path.join(SHOT_DIR, 't9-kb-terms-review.png') })

    await domClick(page, '.page-bar .tab', '提案')
    await page.waitForTimeout(2200)
    requests.length = 0
    await domClick(page, '.page-bar .bar-actions button', '刷新')
    await page.waitForTimeout(2200)
    const propReqs = requests.filter((u) => u.includes('/admin/api/kb/proposals'))
    const propBar = await page.evaluate(() => (document.querySelector('.page-bar') || {}).innerText || '')
    check('kb/提案: 页面栏「刷新」触发了面板取数', propReqs.length > 0,
      `/admin/api/kb/proposals 请求 ${propReqs.length} 个；栏内读数含「待分析」=${propBar.includes('待分析')}`)
    await page.screenshot({ path: path.join(SHOT_DIR, 't9-kb-proposals.png') })

    await domClick(page, '.page-bar .tab', '资料')
    await page.waitForTimeout(2200)
    requests.length = 0
    await domClick(page, '.page-bar .bar-actions button', '刷新')
    await page.waitForTimeout(2200)
    const srcReqs = requests.filter((u) => u.includes('/admin/api/kb/overview'))
    check('kb/资料: 页面栏「刷新」触发了面板取数', srcReqs.length > 0,
      `/admin/api/kb/overview 请求 ${srcReqs.length} 个`)
    await page.screenshot({ path: path.join(SHOT_DIR, 't9-kb-source.png') })

    await domClick(page, '.page-bar .tab', '词条')
    await page.waitForTimeout(2200)
    const kwBar = await page.evaluate(() => {
      const bar = document.querySelector('.page-bar')
      return {
        select: !!bar.querySelector('select'),
        input: !!bar.querySelector('input'),
        btns: [...bar.querySelectorAll('button')].map((b) => (b.textContent || '').trim()),
      }
    })
    // 先回到列表模式，让这次搜索走 load() 而不是核对队列
    await domClick(page, '.page-bar .bar-actions button', '列表')
    await page.waitForTimeout(1200)
    requests.length = 0
    // 合并后的页面栏**没有「搜索」按钮**：搜索框回车即触发（@keyup.enter="tm.search()"）
    await page.evaluate(() => {
      const el = document.querySelector('.page-bar .bar-actions input')
      el.value = 'Flame'
      el.dispatchEvent(new Event('input', { bubbles: true }))
      el.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
    })
    await page.waitForTimeout(2200)
    const kwReqs = requests.filter((u) => u.includes('/admin/api/kb/terms?') && u.includes('Flame'))
    check('kb/词条: 页面栏筛选控件驱动面板',
      kwBar.select && kwBar.input && kwReqs.length > 0,
      `栏内 select=${kwBar.select} input=${kwBar.input} 按钮=[${kwBar.btns.join(' | ')}]，带 Flame 的检索请求 ${kwReqs.length} 个`)
    await page.screenshot({ path: path.join(SHOT_DIR, 't9-kb-terms-search.png') })

    check('kb: 0 console error', bucket.length === 0, bucket.length ? bucket[0] : '干净')

    // ---------- 吸顶实测：Bot 页（先展开全部分组，页面长到必然可滚） ----------
    await page.goto(BASE + '/admin/bot', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(2600)
    await domClick(page, '.page-bar .bar-actions button', '全部展开')
    await page.waitForTimeout(900)
    const beforeScroll = await page.evaluate(() => Math.round(document.querySelector('.page-bar').getBoundingClientRect().top))
    await page.evaluate(() => window.scrollTo(0, 99999))
    await page.waitForTimeout(500)
    const atBottom = await page.evaluate(() => ({
      top: Math.round(document.querySelector('.page-bar').getBoundingClientRect().top),
      headerH: Math.round(document.querySelector('header.bar').getBoundingClientRect().height),
      scrollY: Math.round(window.scrollY),
      docH: document.documentElement.scrollHeight,
      tabsVisible: !!document.querySelector('.page-bar .tabs'),
      saveVisible: [...document.querySelectorAll('.page-bar .bar-actions button')]
        .some((b) => (b.textContent || '').includes('保存并生效')),
    }))
    check('吸顶实测（Bot 页展开全部分组后滚到底）',
      atBottom.top >= 0 && Math.abs(atBottom.top - atBottom.headerH) <= 2 && atBottom.scrollY > 0,
      `滚动前 top=${beforeScroll}；滚到底 scrollY=${atBottom.scrollY}（页面总高 ${atBottom.docH}）时 top=${atBottom.top}，顶端导航高 ${atBottom.headerH} → 差 ${Math.abs(atBottom.top - atBottom.headerH)}px（没钻到导航底下）；栏内 tabs=${atBottom.tabsVisible}，保存按钮=${atBottom.saveVisible}`)
    await page.screenshot({ path: path.join(SHOT_DIR, 't9-bot-scrolled-bottom.png') })

    // ---------- 吸顶实测：知识库（40 条列表，页长可滚） ----------
    await page.goto(BASE + '/admin/kb', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(2600)
    await page.evaluate(() => window.scrollTo(0, 99999))
    await page.waitForTimeout(500)
    const kbBottom = await page.evaluate(() => ({
      top: Math.round(document.querySelector('.page-bar').getBoundingClientRect().top),
      headerH: Math.round(document.querySelector('header.bar').getBoundingClientRect().height),
      scrollY: Math.round(window.scrollY),
      docH: document.documentElement.scrollHeight,
      tabsInBar: !!document.querySelector('.page-bar .tabs'),
    }))
    check('吸顶实测（知识库页滚到底，tabs 仍在同一处）',
      kbBottom.scrollY > 0 && Math.abs(kbBottom.top - kbBottom.headerH) <= 2,
      `滚到底 scrollY=${kbBottom.scrollY}（页面总高 ${kbBottom.docH}）时 page-bar.top=${kbBottom.top}，导航高 ${kbBottom.headerH}，栏内 tabs=${kbBottom.tabsInBar}`)
    await page.screenshot({ path: path.join(SHOT_DIR, 't9-kb-scrolled-bottom.png') })

    // ---------- 按钮文案型读数：词条「刷新」→「读取中…」 ----------
    await page.goto(BASE + '/admin/kb?tab=terms', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(2600)
    requests.length = 0
    // 合并后的页面栏没有「搜索」按钮，取数中变文案的是「刷新」（tmLoading 经 ref 回流）
    await domClick(page, '.page-bar .bar-actions button', '刷新')
    await page.waitForTimeout(150)
    const midLabel = await page.evaluate(() => {
      const b = [...document.querySelectorAll('.page-bar .bar-actions button')]
        .find((x) => (x.textContent || '').includes('读取中') || (x.textContent || '').includes('刷新'))
      return { text: b ? b.textContent.trim() : '', disabled: b ? b.disabled : null }
    })
    await page.waitForTimeout(1800)
    const endLabel = await page.evaluate(() => {
      const b = [...document.querySelectorAll('.page-bar .bar-actions button')]
        .find((x) => (x.textContent || '').trim() === '刷新')
      return { text: b ? b.textContent.trim() : '', disabled: b ? b.disabled : null }
    })
    check('kb/词条: 取数中按钮文案变「读取中…」（面板 loading → 页面栏）',
      midLabel.text.includes('读取中') && midLabel.disabled === true && endLabel.text === '刷新' && endLabel.disabled === false,
      `点击「刷新」后 150ms 文案「${midLabel.text}」(disabled=${midLabel.disabled}) → 取数完成后「${endLabel.text}」(disabled=${endLabel.disabled})`)

    // ---------- 窄屏（390）：按钮不能被挤没 ----------
    await page.setViewportSize({ width: 390, height: 780 })
    for (const spec of [{ n: 'bot', p: '/admin/bot' }, { n: 'kb', p: '/admin/kb' }, { n: 'pages', p: '/admin/pages' }]) {
      bucket = []
      await page.goto(BASE + spec.p, { waitUntil: 'domcontentloaded', timeout: 30000 })
      await page.waitForTimeout(2200)
      const m = await page.evaluate(() => {
        const bar = document.querySelector('.page-bar')
        const w = window.innerWidth
        // 页面没渲染出来时（bar 为 null）也要给出一份可判定的读数，而不是抛异常把整轮跑挂
        if (!bar) return { w, clipped: [], btnCount: 0, btnLabels: [], barH: 0, tabsH: 0, overflow: false, barTop: null, missing: true }
        const btns = [...bar.querySelectorAll('.bar-actions button')]
        const clipped = btns.filter((b) => {
          const r = b.getBoundingClientRect()
          return r.width < 8 || r.height < 8 || r.right > w + 1 || r.left < -1
        }).map((b) => (b.textContent || '').trim())
        const tabs = bar.querySelector('.bar-tabs')
        return {
          w, clipped,
          btnCount: btns.length,
          btnLabels: btns.map((b) => (b.textContent || '').trim()),
          barH: Math.round(bar.getBoundingClientRect().height),
          tabsH: tabs ? Math.round(tabs.getBoundingClientRect().height) : 0,
          overflow: document.documentElement.scrollWidth > w + 1,
          barTop: Math.round(bar.getBoundingClientRect().top),
        }
      })
      check(`窄屏 390 /${spec.n}: 页面栏按钮都在可视区内`,
        !m.missing && m.clipped.length === 0 && !m.overflow && m.btnCount > 0,
        `视口宽 ${m.w}，按钮 ${m.btnCount} 个=[${m.btnLabels.join(' | ')}]，被裁掉 ${JSON.stringify(m.clipped)}，横向溢出=${m.overflow}，栏高=${m.barH}px（tabs 区 ${m.tabsH}px），栏顶=${m.barTop}`)
      await page.screenshot({ path: path.join(SHOT_DIR, `t9-mobile-${spec.n}.png`) })
    }
    await page.setViewportSize(WIDE)
  } catch (e) {
    check('脚本执行', false, String(e && e.message || e).slice(0, 300))
  } finally {
    const failed = results.filter((r) => !r.pass)
    console.log('\n================ 逐页实测数字 ================')
    console.log('  page                page-head  page-bar  遗留工具栏  导航高  滚到底 top  scrollY')
    for (const r of perPage) {
      console.log(`  ${r.page.padEnd(18)}  ${String(r.pageHead).padStart(8)}  ${String(r.pageBar).padStart(8)}  ${String(r.legacy).padStart(10)}  ${String(r.navH).padStart(6)}  ${String(r.stickyTop).padStart(10)}  ${String(r.scrollY).padStart(7)}${r.scrollable ? '' : '   (不足一屏)'}`)
    }
    console.log('\n================ 汇总 ================')
    console.log(`检查项 ${results.length}，失败 ${failed.length}`)
    for (const f of failed) console.log(`  ❌ ${f.name} — ${f.detail}`)
    console.log(`截图：${SHOT_DIR}`)
    await page.close().catch(() => {})
  }
  process.exit(results.some((r) => !r.pass) ? 1 : 0)
})()
