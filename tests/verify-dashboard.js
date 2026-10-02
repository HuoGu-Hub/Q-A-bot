/**
 * 「数据看板」合并 + 最高余弦重做的真机验收脚本。
 *
 * 背景：原先「大屏」（/screen）和「看板」（/dashboard）8 个指标里 6 个重复、
 * 四块图完全一样。本次把两者并成一张页面，并重做了最高余弦分布。
 *
 * 本脚本逐条给可观察证据：
 *   ① 旧地址 /screen 仍指得到 —— 重定向到 /dashboard
 *   ② 导航不再有「大屏」这一项，看板高亮正确
 *   ③ 合并进来的能力还在：自动刷新开关 + 「更新于 HH:MM:SS」
 *      （SLOW=1 时真的等一个 60 秒周期，数一次接口调用）
 *   ④ 最高余弦：画出阈值线（0.45 落在横轴 45% 处）
 *   ⑤ 最高余弦：10 档按「在阈值哪一侧」着色（moss/flame/rust），并数出命中/被挡
 *   ⑥ 最高余弦：计数行 / 柱 / 刻度行三行严格对齐（列中心误差 ≤ 1px）
 *   ⑦ 最高余弦不再拉满整屏（≤ 620px），且它在阈值之前是空的这一段读得出来
 *   ⑧ 8 张 KPI 一张不漏、不出现「7+1」的孤儿换行
 *   ⑨ 每日提问量仍是折线（保留上一次的改动，别被这次重构弄回去）
 *   ⑩ 关键词排行仍是前 8
 *   ⑪ Guard 分布面板**已按需求移除**（前端不再请求 /guard/metrics）
 *   ⑫ 窄屏 390px 无横向溢出
 *   ⑭ 默认窗口是「全部」（days=0），不是最近 30 天
 *   ⑬ 全程 0 console error
 *
 * 走 mock 喂数据（宿主 Vite dev 5173 服务的正是本工作区源码），
 * 余弦分布用**真库里的形状**：0.0~0.1 是未命中，0.1~0.4 空，0.4~0.6 是主体。
 *
 * 用法：
 *   node tests/verify-dashboard.js
 *   SLOW=1 node tests/verify-dashboard.js     # 多等 62 秒，验自动刷新真的会重取
 */
const fs = require('node:fs')
const path = require('node:path')
const { execSync } = require('node:child_process')
const { connectCDP } = require('/root/.playwright/cdp')
const { installDepShim } = require('./lib/dep-shim.cjs')

const ROOT = path.resolve(__dirname, '..')
const HOST_IP = (() => {
  try {
    return execSync("getent ahostsv4 host.docker.internal | awk '{print $1}' | head -n1", { encoding: 'utf8' }).trim()
  } catch { return '' }
})()
const RAW_BASE = process.env.BASE || 'http://localhost:5173'
const BASE = HOST_IP ? RAW_BASE.split(HOST_IP).join('localhost') : RAW_BASE
const SHIM_BASE = HOST_IP ? RAW_BASE.split('localhost').join(HOST_IP) : RAW_BASE
const SHOT_DIR = path.join(ROOT, 'tests/screenshots/dashboard')
const WIDE = { width: 1440, height: 900 }
const SLOW = process.env.SLOW === '1'
const THRESHOLD = 0.45

const calls = []
const countOf = (frag) => calls.filter((c) => c.includes(frag)).length

// ==================== 假数据 ====================
const COUNTS = [12, 18, 9, 25, 31, 22, 17, 40, 35, 28, 19, 14, 23, 30, 44, 38, 26, 21, 33, 29, 16, 11, 27, 36, 41, 34, 24, 20, 15, 13]
const daily = COUNTS.map((count, i) => {
  const d = new Date(Date.UTC(2026, 7, 20) + i * 86400000)
  return { day: d.toISOString().slice(0, 10), count }
})

const overview = {
  total: 1163, questions: 412, hits: 366, hitRate: 0.888, users: 57, groups: 12,
  dropped: 63, fixedReplies: 18, commands: 41,
  p50RetrieveMs: 148, p95RetrieveMs: 720, p50TotalMs: 1890, p95TotalMs: 6400,
  guardActions: { pass: 412, drop: 63, block: 21, fixed: 18 },
  daily,
}

/** 故意给 12 条：请求里 limit=8 才应该是页面渲染的行数 */
const KEYWORD_POOL = [
  { zh: '灵火祭坛', en: 'Flame Altar', count: 88, missCount: 0 },
  { zh: '废料杯', en: 'Scrap Cup', count: 74, missCount: 0 },
  { zh: '灰烬宝库', en: 'Ember Vault', count: 66, missCount: 3 },
  { zh: '瘴气', en: 'Shroud', count: 58, missCount: 0 },
  { zh: '古代尖塔', en: 'Ancient Spire', count: 51, missCount: 2 },
  { zh: '装备', en: 'Equipment', count: 47, missCount: 0 },
  { zh: '武器亚型', en: 'Weapon Subtype', count: 39, missCount: 1 },
  { zh: '收集品', en: 'Collectible', count: 33, missCount: 0 },
  { zh: '生存者', en: 'Survivor', count: 28, missCount: 0 },
  { zh: '熔炉', en: 'Kiln', count: 24, missCount: 0 },
  { zh: '灵火', en: 'Flame', count: 19, missCount: 0 },
  { zh: '迷雾', en: 'Fog', count: 15, missCount: 0 },
]

/** 真库形状（server/data/qa/qqbot.sqlite，guard_action='pass'）：未命中 9、空档 0.1~0.4、主体 0.5~0.6 */
const COSINE_COUNTS = [9, 0, 0, 0, 18, 50, 6, 0, 0, 0]
const cosine = COSINE_COUNTS.map((count, i) => ({ range: `${(i / 10).toFixed(1)}~${((i + 1) / 10).toFixed(1)}`, count }))

const misses = {
  withKeyword: [
    { zh: '瘴气', en: 'Shroud', count: 9, bestCosineRaw: 0.412, samples: [] },
    { zh: '熔炉', en: 'Kiln', count: 4, bestCosineRaw: 0.238, samples: [] },
  ],
  unmatched: [{ ts: '2026-09-27T10:00:00', question: '灰烬宝库在哪？', bestCosineRaw: 0.19 }],
}

const sources = {
  sources: [
    { source: 'vector', count: 288, hits: 250, hitRate: 0.868 },
    { source: 'keyword', count: 96, hits: 88, hitRate: 0.917 },
    { source: 'none', count: 28, hits: 0, hitRate: 0 },
  ],
  verdicts: [{ verdict: 'good', count: 40 }, { verdict: 'bad', count: 6 }],
}

const visits = { total: 231, visitors: 44, daily: [], paths: [] }

function json(route, body) {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
}

async function installMocks(page) {
  await page.route('**/admin/api/**', async (route) => {
    const req = route.request()
    const u = new URL(req.url())
    const p = u.pathname.startsWith('/admin/api') ? u.pathname.slice('/admin/api'.length) : u.pathname
    calls.push(u.pathname + u.search)

    if (p === '/session') return json(route, { ok: true })
    if (p === '/overview') return json(route, overview)
    if (p === '/keywords') {
      // 和后端一样按 limit 截断（QaAnalytics#keywords 是 ORDER BY count DESC LIMIT ?）
      const limit = Number(u.searchParams.get('limit') || KEYWORD_POOL.length)
      return json(route, KEYWORD_POOL.slice(0, limit))
    }
    if (p === '/misses') return json(route, misses)
    if (p === '/cosine') return json(route, cosine)
    if (p === '/sources') return json(route, sources)
    if (p === '/visits') return json(route, visits)
    // ⚠️ 没有 /guard/metrics 的 mock：看板 2026-09-28 起不再请求它。
    //    真去请求就会落到下面的「mock 未覆盖」，检查 ⑪ 也会数出来。
    if (p === '/log/status' || p === '/system/status') return json(route, {})

    console.log('   ⚠️ mock 未覆盖：' + u.pathname + u.search)
    return json(route, {})
  })
}

// ==================== 断言 ====================
const results = []
function check(name, pass, detail) {
  results.push({ name, pass })
  console.log((pass ? '✅' : '❌') + ' ' + name + (detail ? '\n      ' + detail : ''))
}

async function tagPanels(page) {
  await page.evaluate(() => {
    for (const sec of document.querySelectorAll('section.panel')) {
      const t = (sec.querySelector('.head-text') || {}).textContent || ''
      sec.setAttribute('data-panel', t.trim())
    }
  })
}

const snap = (page) => page.evaluate((THRESHOLD) => {
  const panel = (name) => [...document.querySelectorAll('section.panel')]
    .find((s) => (s.getAttribute('data-panel') || '') === name) || null

  const dailyPanel = panel('每日提问量')
  const kwPanel = panel('关键词排行')
  const cosPanel = panel('最高余弦分布')
  const guardPanel = panel('Guard 分布')

  const line = dailyPanel && dailyPanel.querySelector('.line-path')
  const cosGrid = cosPanel && cosPanel.querySelector('.cosine')
  const histFig = cosPanel && cosPanel.querySelector('.hist-fig')
  const thr = cosPanel && cosPanel.querySelector('.hist-thr')

  const centerOf = (el) => { const r = el.getBoundingClientRect(); return r.left + r.width / 2 }

  const vals = cosPanel ? [...cosPanel.querySelectorAll('.hist-val')] : []
  const bars = cosPanel ? [...cosPanel.querySelectorAll('.h-bar')] : []
  const lbls = cosPanel ? [...cosPanel.querySelectorAll('.h-lbl')] : []
  const drift = bars.map((b, i) => Math.max(
    Math.abs(centerOf(b) - centerOf(vals[i])),
    Math.abs(centerOf(b) - centerOf(lbls[i])),
  ))

  const sideOf = (cls) => bars.filter((b) => b.classList.contains(cls)).length
  const legendText = cosPanel ? [...cosPanel.querySelectorAll('.legend .lg')].map((n) => (n.textContent || '').replace(/\s+/g, ' ').trim()) : []

  const kpis = [...document.querySelectorAll('.kpis .stat')]
  const kpiTops = [...new Set(kpis.map((k) => Math.round(k.getBoundingClientRect().top)))]

  return {
    // 每日提问量
    hasPolyline: !!line,
    polyPoints: line ? (line.getAttribute('points') || '').trim().split(/\s+/).filter(Boolean).length : 0,
    dots: dailyPanel ? dailyPanel.querySelectorAll('.pt').length : 0,
    oldBars: dailyPanel ? dailyPanel.querySelectorAll('.col-bar').length : -1,

    // 余弦
    bucketCount: bars.length,
    valCount: vals.length,
    lblCount: lbls.length,
    maxDrift: drift.length ? Math.max(...drift) : -1,
    thrLeftPct: thr ? (thr.getBoundingClientRect().left - thr.parentElement.getBoundingClientRect().left)
      / thr.parentElement.getBoundingClientRect().width * 100 : -1,
    thrText: thr ? (thr.textContent || '').trim() : '',
    keptBars: sideOf('side-kept'),
    edgeBars: sideOf('side-edge'),
    blockedBars: sideOf('side-blocked'),
    legendText,
    histW: histFig ? histFig.getBoundingClientRect().width : -1,
    bodyW: cosPanel ? cosPanel.querySelector('.panel-body').getBoundingClientRect().width : -1,
    cosGridCols: cosGrid ? getComputedStyle(cosGrid).gridTemplateColumns.split(' ').length : -1,
    cosHint: cosPanel ? ((cosPanel.querySelector('.head-hint') || {}).textContent || '').trim() : '',
    valTexts: vals.map((v) => (v.textContent || '').trim()),

    // KPI / 其余
    kpiCount: kpis.length,
    kpiRows: kpiTops.length,
    kwRows: kwPanel ? kwPanel.querySelectorAll('.bar-row').length : -1,
    guardFound: !!guardPanel,
    navLinks: [...document.querySelectorAll('.nav-link')].map((a) => a.textContent.trim()),
    activeNav: ((document.querySelector('.nav-link.router-link-active') || {}).textContent || '').trim(),
    autoSwitch: !!document.querySelector('.auto input[type=checkbox]'),
    autoLabel: ((document.querySelector('.auto') || {}).textContent || '').replace(/\s+/g, ' ').trim(),
    stamp: ((document.querySelector('.stamp') || {}).textContent || '').replace(/\s+/g, ' ').trim(),
    // 日期轴：可见标签数 + 相邻标签的最小间距（< 0 就是叠在一起了）
    axis: (() => {
      const rs = [...document.querySelectorAll('.axis-lbl')]
        .filter((n) => n.offsetParent !== null)
        .map((n) => n.getBoundingClientRect())
      let minGap = Infinity
      for (let i = 1; i < rs.length; i++) minGap = Math.min(minGap, rs[i].left - rs[i - 1].right)
      return { n: rs.length, minGap: rs.length > 1 ? minGap : 0 }
    })(),
    docOverflowX: document.documentElement.scrollWidth > window.innerWidth + 1,
    // 溢出时点名「是谁撑破的」—— 只说"溢出了"没法修
    overflowing: (() => {
      const w = window.innerWidth
      const out = []
      for (const el of document.querySelectorAll('body *')) {
        const r = el.getBoundingClientRect()
        if (r.width < 1) continue
        if (r.right > w + 1 || r.left < -1) {
          const cls = typeof el.className === 'string' ? el.className.split(/\s+/).filter(Boolean).slice(0, 2).join('.') : ''
          out.push(el.tagName.toLowerCase() + (cls ? '.' + cls : '') + ' [' + Math.round(r.left) + '→' + Math.round(r.right) + ']')
          if (out.length >= 6) break
        }
      }
      return out
    })(),
    threshold: THRESHOLD,
  }
}, THRESHOLD)

// ==================== 主流程 ====================
async function main() {
  fs.mkdirSync(SHOT_DIR, { recursive: true })
  const browser = await connectCDP()
  // ⚠️ 新建隔离 context + 新页：不动宿主正在用的那个标签页
  const ctx = await browser.newContext({ viewport: WIDE })
  const page = await ctx.newPage()
  const errors = []
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
  page.on('pageerror', (e) => errors.push(String(e)))
  await installDepShim(page, { fetchBase: SHIM_BASE })
  await installMocks(page)

  // ---------- ① 旧地址重定向 ----------
  console.log('==> 打开旧地址 ' + BASE + '/admin/screen')
  await page.goto(BASE + '/admin/screen', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.kpis .stat', { timeout: 20000 })
  await page.waitForTimeout(700)
  const landed = await page.evaluate(() => location.pathname)
  await tagPanels(page)
  const s = await snap(page)
  check('① 旧地址 /screen 仍指得到：重定向到 /dashboard',
    landed === '/admin/dashboard' || landed.endsWith('/dashboard'),
    '最终地址 ' + landed)

  // ---------- ② 导航 ----------
  check('② 导航里「大屏」已收掉、看板高亮正确',
    !s.navLinks.includes('大屏') && s.navLinks.includes('看板') && s.activeNav === '看板',
    'tab = [' + s.navLinks.join(' / ') + ']，当前高亮「' + s.activeNav + '」')

  // ---------- ③ 自动刷新 / 更新于 ----------
  const before = countOf('/overview')
  const toggled = await page.evaluate(() => {
    const cb = document.querySelector('.auto input[type=checkbox]')
    if (!cb) return false
    cb.checked = true
    cb.dispatchEvent(new Event('change', { bubbles: true }))
    return true
  })
  await page.waitForTimeout(300)
  check('③ 合并进来的能力还在：自动刷新开关 + 「更新于 HH:MM:SS」',
    s.autoSwitch && toggled && /自动刷新/.test(s.autoLabel) && /更新于 \d{2}:\d{2}:\d{2}/.test(s.stamp),
    '开关「' + s.autoLabel + '」；' + s.stamp)

  if (SLOW) {
    console.log('   … 等一个 60 秒周期（SLOW=1）')
    await page.waitForTimeout(62_000)
    const after = countOf('/overview')
    check('③b 自动刷新真的会重取（一个周期后 /overview 多请求了）',
      after > before, '周期前 ' + before + ' 次 → 周期后 ' + after + ' 次')
  } else {
    console.log('   （跳过 60 秒周期实测；加 SLOW=1 可验）')
  }

  // ---------- ④⑤⑥⑦ 余弦 ----------
  const expectedKept = COSINE_COUNTS.slice(5).reduce((a, b) => a + b, 0)   // 全是 ≥0.45 的档
  const expectedEdge = COSINE_COUNTS[4]                                    // 0.4~0.5 跨阈值
  const expectedBlocked = COSINE_COUNTS.slice(0, 4).reduce((a, b) => a + b, 0)
  check('④ 余弦图画出阈值线，且线就在 0.45 该在的位置',
    Math.abs(s.thrLeftPct - s.threshold * 100) < 1.5 && /0\.45/.test(s.thrText),
    '虚线在横轴 ' + s.thrLeftPct.toFixed(1) + '%（0.45 应为 45%），标注「' + s.thrText + '」')

  check('⑤ 余弦按「在阈值哪一侧」着色，并数出命中 / 跨档 / 被挡',
    s.keptBars === 5 && s.edgeBars === 1 && s.blockedBars === 4 &&
    s.legendText.join('|').includes(String(expectedKept)) &&
    s.legendText.join('|').includes(String(expectedEdge)) &&
    s.legendText.join('|').includes(String(expectedBlocked)),
    s.keptBars + ' 档达标 / ' + s.edgeBars + ' 档跨阈值 / ' + s.blockedBars + ' 档被挡；'
    + '图例「' + s.legendText.join('  ') + '」\n      '
    + '逐档计数 = ' + s.valTexts.join(', ') + '（真库形状 ' + COSINE_COUNTS.join(', ') + '）')

  check('⑥ 计数 / 柱 / 刻度三行严格对齐（列中心误差 ≤ 1px）',
    s.bucketCount === 10 && s.valCount === 10 && s.lblCount === 10 && s.maxDrift <= 1,
    '三行各 ' + s.bucketCount + ' 列，最大中心偏差 ' + s.maxDrift.toFixed(2) + 'px')

  check('⑦ 余弦不再拉满整屏，且不占一屏宽',
    s.cosGridCols === 2 && s.histW > 0 && s.histW <= 640 && s.histW < s.bodyW * 0.75,
    '图宽 ' + s.histW.toFixed(0) + 'px / 面板内容 ' + s.bodyW.toFixed(0) + 'px，'
    + '网格列数 ' + s.cosGridCols + '，标题提示「' + s.cosHint + '」')

  // ---------- ⑧ KPI ----------
  check('⑧ 8 张 KPI 一张不漏、不出现「7+1」的孤儿换行',
    s.kpiCount === 8 && s.kpiRows === 1,
    s.kpiCount + ' 张，占 ' + s.kpiRows + ' 行（1440px 宽下应一行铺满）')

  // ---------- ⑨ 每日提问量折线 ----------
  check('⑨ 每日提问量仍是折线（没有被这次重构弄回柱状）',
    s.hasPolyline && s.polyPoints === 21 && s.dots === 21 && s.oldBars === 0,
    'polyline ' + s.polyPoints + ' 点，圆点 ' + s.dots + ' 个，旧 .col-bar ' + s.oldBars + ' 个')

  // ---------- ⑩ 关键词 ----------
  const kwCall = calls.find((c) => c.includes('/keywords'))
  check('⑩ 关键词排行仍是前 8',
    !!kwCall && /limit=8(&|$)/.test(kwCall) && s.kwRows === 8,
    '请求 ' + kwCall + '；数据池 12 条 → 渲染 ' + s.kwRows + ' 行')

  // ---------- ⑪ Guard（2026-09-28 起前端不再显示）----------
  check('⑪ Guard 分布面板已移除', !s.guardFound, 'Guard 分布面板存在 = ' + s.guardFound +
    '；/guard/metrics 请求数 = ' + countOf('/guard/metrics'))

  // ---------- ⑭ 默认窗口 = 全部（days=0），不是最近 30 天 ----------
  const ovCall = calls.find((c) => c.includes('/overview'))
  check('⑭ 默认窗口是「全部」', !!ovCall && /days=0(&|$)/.test(ovCall), '请求 ' + ovCall)

  // ---------- 截图 ----------
  await page.screenshot({ path: path.join(SHOT_DIR, 'dashboard-full.png'), fullPage: true })
  const shotPanel = async (name, title) => {
    const h = await page.evaluateHandle((t) => [...document.querySelectorAll('section.panel')]
      .find((x) => (x.getAttribute('data-panel') || '') === t), title)
    const el = h.asElement()
    if (el) await el.screenshot({ path: path.join(SHOT_DIR, name) })
  }
  await shotPanel('cosine.png', '最高余弦分布')
  await shotPanel('daily-line.png', '每日提问量')
  await shotPanel('keywords.png', '关键词排行')

  // ---------- ⑫ 窄屏 ----------
  await page.setViewportSize({ width: 390, height: 780 })
  await page.waitForTimeout(600)
  const narrow = await snap(page)
  await page.screenshot({ path: path.join(SHOT_DIR, 'dashboard-narrow.png'), fullPage: true })
  check('⑫ 窄屏 390px 无横向溢出', !narrow.docOverflowX,
    narrow.docOverflowX
      ? '仍溢出，撑破的元素：' + narrow.overflowing.join(' | ')
      : '无溢出（KPI 与余弦图都退成单列）')

  check('⑫b 窄屏下日期轴标签不打架（相邻最小间距 ≥ 0）',
    narrow.axis.n > 0 && narrow.axis.n <= 8 && narrow.axis.minGap >= 0,
    '可见标签 ' + narrow.axis.n + ' 个，相邻最小间距 ' + narrow.axis.minGap.toFixed(1) + 'px')
  await page.setViewportSize(WIDE)

  // ---------- ⑬ 控制台 ----------
  check('⑬ 全程 0 console error', errors.length === 0,
    errors.length ? errors.slice(0, 3).join(' | ') : '干净')

  const failed = results.filter((r) => !r.pass)
  console.log('\n===== ' + (results.length - failed.length) + '/' + results.length + ' 通过 =====')
  for (const f of failed) console.log('  ❌ ' + f.name)
  console.log('截图：' + SHOT_DIR)
  await ctx.close().catch(() => {})
  // ⚠️ 绝不能 browser.close()：对 CDP 连接的浏览器，那会把宿主正在用的 Chrome 一起关掉。
  process.exit(failed.length ? 1 : 0)
}

main().catch((e) => { console.error('验收脚本异常：' + (e && e.stack || e)); process.exit(2) })
