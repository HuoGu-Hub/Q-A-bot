/**
 * 「模型」+「日志」→「系统」页（T10）的真机验收脚本。
 *
 * 检查的是本次重构承诺的**客观事实**：
 *   ① /system 两个 tab 都能渲染，旧路径 /models、/logs 重定向到对应 tab
 *   ② 顶端导航 8 项且含「系统」
 *   ③ 页面栏的「刷新」真的驱动了对应面板（发出请求 + 面板内容真的变了）
 *   ④ 面板状态真的回流到页面栏（缓冲读数、ERROR 计数、暂停/继续文案）
 *   ⑤ KeepAlive 生效：切走再切回，日志缓冲与 SSE 连接都没重建
 *   ⑥ 页面上搜不到 napcat / logger / baseUrl / 其他参数 / 接口地址
 *   ⑦ 模型 ID 只用共享 Select 呈现候选（没有原生 datalist）
 *   ⑧ 全程 0 console error
 *
 * 宿主机后端 8080 没在跑，所以走 MOCK_API 模式：用宿主的 Vite dev（5173，
 * 它服务的正是本工作区源码），把 /admin/api/** 全部拦下来喂假数据。
 * 浏览器、Vue 运行时、DOM、样式、事件全是真的 —— 只有数据是假的。
 *
 * 用法：node tests/verify-redesign-t10.js
 */
const fs = require('node:fs')
const path = require('node:path')
const { connectCDP } = require('/root/.playwright/cdp')

const ROOT = path.resolve(__dirname, '..')
const BASE = process.env.BASE || 'http://localhost:5173'
const SHOT_DIR = path.join(ROOT, 'tests/screenshots/redesign')
const WIDE = { width: 1440, height: 900 }

/** 接口调用计数：用来证明「点一下按钮真的取了一次数」 */
const calls = { models: 0, available: 0, status: 0, history: 0 }

/**
 * 假数据。
 * ⚠️ 刻意做成"第二次调用返回不同内容"：这样「刷新」之后面板/读数的变化
 * 就是可观察证据，而不是"看起来没报错"。
 */
function json(route, body) {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
}

async function installMocks(page) {
  // SSE 没法用 route 模拟成一条流，直接换成假的实现：注入 3 条日志
  // （含一条带堆栈的 ERROR，用来验异常块的锈色竖线）
  await page.addInitScript(() => {
    const LOGS = [
      { seq: 101, ts: '2026-09-20T10:00:01', level: 'INFO', message: '收到群消息' },
      { seq: 102, ts: '2026-09-20T10:00:02', level: 'WARN', message: '触发限流，稍后重试' },
      { seq: 103, ts: '2026-09-20T10:00:03', level: 'ERROR', message: '调用模型失败',
        throwable: 'java.net.ConnectException: Connection refused\n\tat com.example.qqbot.llm.LlmRouter.probe(LlmRouter.java:88)' },
    ]
    window.__esCreated = 0
    window.__esEvents = 0
    class FakeES {
      constructor(url) {
        this.url = String(url)
        this.onerror = null
        this._l = {}
        window.__esCreated++
        setTimeout(() => {
          for (const r of LOGS) {
            for (const fn of (this._l['log'] || [])) { window.__esEvents++; fn({ data: JSON.stringify(r) }) }
          }
        }, 50)
      }
      addEventListener(t, fn) { (this._l[t] = this._l[t] || []).push(fn) }
      close() {}
    }
    window.EventSource = FakeES
  })

  await page.route('**/admin/api/**', async (route) => {
    const u = new URL(route.request().url())
    const p = u.pathname.replace(/^\/admin\/api/, '')

    // 给两个"刷新"目标加延迟，好抓「读取中…」的中间态
    if (p === '/models' || p === '/logs/history') await new Promise((r) => setTimeout(r, 700))

    if (p === '/session') return json(route, { ok: true })

    if (p === '/models') {
      calls.models++
      const first = calls.models === 1
      return json(route, {
        defaultProvider: first ? 'opencode' : 'siliconflow',
        fallbackChain: first ? ['opencode', 'siliconflow'] : ['siliconflow', 'opencode'],
        note: '只有模型 ID 可以修改',
        providers: first
          ? [{ name: 'opencode', modelName: 'deepseek-v4.1-flash', configured: true, ready: true }]
          : [
              { name: 'opencode', modelName: 'deepseek-v4.1-pro', configured: true, ready: true },
              { name: 'siliconflow', modelName: 'Qwen3-8B', configured: true, ready: false },
            ],
      })
    }
    if (p === '/models/available') {
      calls.available++
      return json(route, { ok: true, models: ['deepseek-v4.1-flash', 'deepseek-v4.1-pro', 'deepseek-chat'], count: 3 })
    }
    if (p === '/logs/status') {
      calls.status++
      return json(route, {
        enabled: true, bufferSize: calls.status === 1 ? 120 : 777, capacity: 2000, totalWritten: 9000,
        dropped: calls.status === 1 ? 0 : 3, maskSensitive: true, currentSeq: 9000, activeStreams: 1,
      })
    }
    if (p === '/logs/history') {
      calls.history++
      return json(route, { entries: [], currentSeq: 9000 })
    }
    console.log(`   ⚠️ mock 未覆盖：${u.pathname}${u.search}`)
    return json(route, {})
  })
}

const results = []
function check(name, pass, detail) {
  results.push({ name, pass })
  console.log(`${pass ? '✅' : '❌'} ${name}${detail ? '  — ' + detail : ''}`)
}

/** 宿主窗口在后台时 Playwright 的 click 会因为「不可见」超时，统一用 DOM click */
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

const barText = (page) => page.evaluate(() =>
  (document.querySelector('.page-bar') || {}).innerText || '')
const barButtons = (page) => page.evaluate(() =>
  [...document.querySelectorAll('.page-bar .bar-actions button')].map((b) => ({
    t: (b.textContent || '').trim(), d: b.disabled,
  })))

;(async () => {
  fs.mkdirSync(SHOT_DIR, { recursive: true })
  const browser = await connectCDP()
  const ctx = browser.contexts()[0]
  const page = await ctx.newPage()
  await page.setViewportSize(WIDE)
  await installMocks(page)

  const errors = []
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text().slice(0, 240)) })
  page.on('pageerror', (e) => errors.push('pageerror: ' + String(e.message || e).slice(0, 240)))

  const requests = []
  page.on('request', (r) => requests.push(r.url()))
  const countReq = (frag) => requests.filter((u) => u.includes(frag)).length

  // CDP：清缓存，否则看到的可能是旧页面
  const cdp = await ctx.newCDPSession(page)
  await cdp.send('Network.enable')
  await cdp.send('Network.clearBrowserCache')

  try {
    // ---------- ① 顶端导航 ----------
    await page.goto(BASE + '/admin/system?tab=models', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(2600)
    const nav = await page.evaluate(() => {
      const links = [...document.querySelectorAll('header.bar .nav .nav-link')]
      return { n: links.length, labels: links.map((a) => (a.textContent || '').trim()), href: links.map((a) => a.getAttribute('href')) }
    })
    check('顶端导航是 8 项且含「系统」',
      nav.n === 8 && nav.labels.includes('系统') && !nav.labels.includes('模型') && !nav.labels.includes('日志'),
      `${nav.n} 项：${nav.labels.join(' / ')}`)

    // ---------- ② 模型 tab 渲染 ----------
    const models = await page.evaluate(() => {
      const tabs = [...document.querySelectorAll('.page-bar .tabs .tab')].map((t) => (t.textContent || '').trim())
      const titles = [...document.querySelectorAll('.panel .panel-title .head-text')].map((t) => (t.textContent || '').trim())
      return {
        tabs,
        active: (document.querySelector('.page-bar .tab.active') || {}).innerText || '',
        titles,
        chain: [...document.querySelectorAll('.chain .node')].map((n) => (n.textContent || '').replace(/\s+/g, ' ').trim()),
        modelInput: (document.querySelector('.edit-box input') || {}).value || '',
        selects: document.querySelectorAll('select').length,
        datalists: document.querySelectorAll('datalist').length,
        pageBar: document.querySelectorAll('.page-bar').length,
        bodyHasRo: /接口地址|其他参数/.test(document.body.innerText),
      }
    })
    check('/system?tab=models 渲染出模型面板（tabs + 面板 + 调用链）',
      models.tabs.join('/') === '模型/日志' && models.active.includes('模型') &&
      models.titles.includes('当前调用顺序') && models.chain.length === 2 && models.pageBar === 1,
      `tabs=[${models.tabs}]，激活=「${models.active}」，面板标题=${JSON.stringify(models.titles)}，调用链=${JSON.stringify(models.chain)}`)
    check('模型面板没有只读项网格（接口地址/其他参数已删）',
      !models.bodyHasRo && models.datalists === 0 && models.modelInput === 'deepseek-v4.1-flash',
      `正文含只读项标题=${models.bodyHasRo}，datalist 数=${models.datalists}，模型 ID 输入框=${models.modelInput}`)

    // ---------- ③ 点页面栏「刷新」真的驱动模型面板 ----------
    const before = { models: countReq('/admin/api/models') }
    const btnBefore = await barButtons(page)
    const clicked = await domClick(page, '.page-bar .bar-actions button', '刷新')
    await page.waitForTimeout(150)
    const btnMid = await barButtons(page)
    await page.waitForTimeout(2200)
    const after = await page.evaluate(() => ({
      modelInput: (document.querySelector('.edit-box input') || {}).value || '',
      chain: [...document.querySelectorAll('.chain .node')].map((n) => (n.textContent || '').replace(/\s+/g, ' ').trim()),
      providers: [...document.querySelectorAll('.panel .panel-title .head-text')].map((t) => (t.textContent || '').trim()),
    }))
    const btnAfter = await barButtons(page)
    check('/system/模型：页面栏「刷新」真的驱动了面板（取数 + 内容真的变了）',
      clicked && countReq('/admin/api/models') > before.models &&
      btnMid[0] && btnMid[0].t === '读取中…' && btnMid[0].d === true &&
      after.modelInput === 'deepseek-v4.1-pro' && after.chain[0].includes('siliconflow'),
      `点中=${clicked}，/admin/api/models 请求 ${before.models} → ${countReq('/admin/api/models')} 个；` +
      `点击后 150ms 按钮=${JSON.stringify(btnMid)}；模型 ID「deepseek-v4.1-flash」→「${after.modelInput}」；` +
      `调用链首节点「${models.chain[0]}」→「${after.chain[0]}」；面板标题=${JSON.stringify(after.providers)}；` +
      `取数完按钮=${JSON.stringify(btnAfter)}；点击前=${JSON.stringify(btnBefore)}`)
    await page.screenshot({ path: path.join(SHOT_DIR, 't10-models.png') })

    // ---------- ④ 模型 ID 候选：共享 Select，不是原生输入建议 ----------
    const fetched = await domClick(page, '.edit-box button', '拉取列表')
    await page.waitForTimeout(1200)
    const pick = await page.evaluate(() => {
      const sel = document.querySelector('.pick-row select')
      if (!sel) return { ok: false }
      const cs = getComputedStyle(sel)
      return {
        ok: true,
        cls: sel.className,
        opts: [...sel.options].map((o) => o.value),
        bg: cs.backgroundColor,
        radius: cs.borderRadius,
        datalists: document.querySelectorAll('datalist').length,
      }
    })
    // 选一个候选：必须写回模型 ID 输入框（Select 真的连到面板状态）
    const picked = await page.evaluate(() => {
      const sel = document.querySelector('.pick-row select')
      const inp = document.querySelector('.edit-box input')
      sel.value = 'deepseek-chat'
      sel.dispatchEvent(new Event('change', { bubbles: true }))
      return { sel: sel.value }
    })
    await page.waitForTimeout(400)
    const pickedVal = await page.evaluate(() => (document.querySelector('.edit-box input') || {}).value || '')
    // 手输一个候选列表里没有的 ID：Select 必须把它补成第一项，而不是落到别的 ID 上
    await page.evaluate(() => {
      const inp = document.querySelector('.edit-box input')
      inp.value = 'my-custom-model'
      inp.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await page.waitForTimeout(400)
    const opts2 = await page.evaluate(() => [...document.querySelector('.pick-row select').options].map((o) => o.value))
    check('模型面板：候选模型用共享 Select 呈现，无原生 datalist',
      fetched && pick.ok && pick.cls.includes('inp') && pick.opts.length === 3 && pick.datalists === 0 &&
      pick.opts.includes('deepseek-v4.1-pro') && pickedVal === 'deepseek-chat' && opts2[0] === 'my-custom-model',
      `拉取列表点中=${fetched}，/admin/api/models/available 请求 ${countReq('/admin/api/models/available')} 个；` +
      `Select class=「${pick.cls}」选项=${JSON.stringify(pick.opts)} 背景=${pick.bg} 圆角=${pick.radius}；datalist=${pick.datalists}；` +
      `选中 deepseek-chat → 输入框「${pickedVal}」；手输 my-custom-model → 选项首项「${opts2[0]}」`)
    await page.screenshot({ path: path.join(SHOT_DIR, 't10-models-select.png') })

    // ---------- ⑤ 切到日志 tab ----------
    const okTab = await domClick(page, '.page-bar .tab', '日志')
    await page.waitForTimeout(2200)
    const logs = await page.evaluate(() => ({
      active: (document.querySelector('.page-bar .tab.active') || {}).innerText || '',
      lines: [...document.querySelectorAll('.logbox .line')].map((l) => (l.textContent || '').replace(/\s+/g, ' ').trim()),
      cols: getComputedStyle(document.querySelector('.logbox .line')).gridTemplateColumns,
      tb: (() => { const e = document.querySelector('.logbox .tb'); if (!e) return null
        const cs = getComputedStyle(e); return { w: cs.borderLeftWidth, c: cs.borderLeftColor } })(),
      bar: (document.querySelector('.page-bar') || {}).innerText || '',
      selects: document.querySelectorAll('.page-bar select').length,
      url: location.search,
    }))
    check('/system?tab=logs 渲染出日志流（SSE 注入 3 条 + 异常块）',
      okTab && logs.active.includes('日志') && logs.lines.length === 3 && logs.lines[2].includes('调用模型失败') && !!logs.tb,
      `激活=「${logs.active}」，URL${logs.url}，日志行 ${logs.lines.length} 条：${JSON.stringify(logs.lines)}；` +
      `异常块 border-left=${logs.tb ? logs.tb.w + ' ' + logs.tb.c : '(无)'}；行网格列=${logs.cols}`)
    check('日志面板没有 logger 列（行内只剩 时间/级别/消息）',
      !/com\.example\.qqbot\.media/.test(logs.lines.join(' ')) && logs.cols.trim().split(/\s+/).length === 3,
      `网格列 = ${logs.cols}（原来 4 列：62px 46px 150px 1fr）`)
    check('页面栏显示面板状态（缓冲读数 + ERROR 计数 + 暂停按钮）',
      /缓冲\s*120\s*\/\s*2,?000/.test(logs.bar) && /已脱敏/.test(logs.bar) && /ERROR\s*1/.test(logs.bar) && /暂停/.test(logs.bar),
      `页面栏文字「${logs.bar.replace(/\s+/g, ' ').trim().slice(0, 120)}」`)
    await page.screenshot({ path: path.join(SHOT_DIR, 't10-logs.png') })

    // ---------- ⑥ 点页面栏「刷新」驱动日志面板 ----------
    const stBefore = countReq('/admin/api/logs/status')
    const hisBefore = countReq('/admin/api/logs/history')
    const ok2 = await domClick(page, '.page-bar .bar-actions button', '刷新')
    await page.waitForTimeout(2000)
    const afterBar = await barText(page)
    check('/system/日志：页面栏「刷新」真的驱动了面板（重取状态 + 读数变了）',
      ok2 && countReq('/admin/api/logs/status') > stBefore && countReq('/admin/api/logs/history') > hisBefore &&
      /缓冲\s*777\s*\/\s*2,?000/.test(afterBar) && /已丢\s*3/.test(afterBar),
      `/logs/status ${stBefore} → ${countReq('/admin/api/logs/status')} 个，/logs/history ${hisBefore} → ${countReq('/admin/api/logs/history')} 个；` +
      `读数「缓冲 120/2,000 · 已脱敏」→「${afterBar.replace(/\s+/g, ' ').trim().slice(0, 90)}」`)

    // ---------- ⑦ 级别过滤 / 搜索 / 暂停 ----------
    const lvlChanged = await page.evaluate(() => {
      const sel = document.querySelector('.page-bar .bar-actions select')
      if (!sel) return false
      sel.value = 'ERROR'
      sel.dispatchEvent(new Event('change', { bubbles: true }))
      return true
    })
    await page.waitForTimeout(1200)
    const lvlReq = requests.filter((u) => u.includes('/admin/api/logs/history') && u.includes('level=ERROR')).length
    const paused0 = (await barButtons(page)).map((b) => b.t)
    await domClick(page, '.page-bar .bar-actions button', '暂停')
    await page.waitForTimeout(400)
    const paused1 = (await barButtons(page)).map((b) => b.t)
    check('日志：级别过滤与暂停/继续都由页面栏驱动',
      lvlChanged && lvlReq > 0 && paused0.includes('暂停') && paused1.includes('继续'),
      `切到 ERROR 后带 level=ERROR 的 /logs/history 请求 ${lvlReq} 个；按钮 ${JSON.stringify(paused0)} → ${JSON.stringify(paused1)}`)
    await domClick(page, '.page-bar .bar-actions button', '继续')
    await page.waitForTimeout(300)

    // ---------- ⑧ 搜索只搜消息/异常 ----------
    const kwOk = await page.evaluate(() => {
      const inp = document.querySelector('.page-bar input')
      if (!inp) return false
      inp.value = '限流'
      inp.dispatchEvent(new Event('input', { bubbles: true }))
      return true
    })
    await page.waitForTimeout(500)
    const kwLines = await page.evaluate(() => [...document.querySelectorAll('.logbox .line')].map((l) => (l.textContent || '').replace(/\s+/g, ' ').trim()))
    check('日志搜索对消息生效（3 行 → 1 行）',
      kwOk && kwLines.length === 1 && kwLines[0].includes('限流'),
      `搜「限流」后剩 ${kwLines.length} 行：${JSON.stringify(kwLines)}`)
    await page.screenshot({ path: path.join(SHOT_DIR, 't10-logs-filtered.png') })

    // ---------- ⑨ KeepAlive：带着过滤状态切走再切回 ----------
    // 计数在"日志面板已挂载且级别已切过一次"之后取，才说明是切 tab 造成的差异
    const esBefore = await page.evaluate(() => window.__esCreated)
    await domClick(page, '.page-bar .tab', '模型')
    await page.waitForTimeout(1000)
    await domClick(page, '.page-bar .tab', '日志')
    await page.waitForTimeout(1000)
    const keep = await page.evaluate(() => ({
      es: window.__esCreated,
      lines: document.querySelectorAll('.logbox .line').length,
      kw: (document.querySelector('.page-bar input') || {}).value || '',
      paused: (() => {
        const b = [...document.querySelectorAll('.page-bar .bar-actions button')].map((x) => (x.textContent || '').trim())
        return b
      })(),
      url: location.search,
    }))
    check('切走再切回：日志缓冲/过滤状态留存、SSE 未重建（KeepAlive 生效）',
      keep.es === esBefore && keep.lines === 1 && keep.kw === '限流' && keep.url.includes('tab=logs'),
      `EventSource 实例数 ${esBefore} → ${keep.es}（切回后搜索框仍是「${keep.kw}」、仍只剩 ${keep.lines} 行、按钮=${JSON.stringify(keep.paused)}，URL${keep.url}）`)

    // 收尾：清掉搜索，给截图一个完整日志流
    await page.evaluate(() => {
      const inp = document.querySelector('.page-bar input')
      inp.value = ''
      inp.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await page.waitForTimeout(400)

    // ---------- ⑩ 旧路径重定向 ----------
    await page.goto(BASE + '/admin/models', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(2000)
    const r1 = await page.evaluate(() => ({
      path: location.pathname + location.search,
      active: (document.querySelector('.page-bar .tab.active') || {}).innerText || '',
      chain: document.querySelectorAll('.chain .node').length,
    }))
    await page.goto(BASE + '/admin/logs', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(1500)
    const r2 = await page.evaluate(() => ({
      path: location.pathname + location.search,
      active: (document.querySelector('.page-bar .tab.active') || {}).innerText || '',
      box: !!document.querySelector('.logbox'),
    }))
    check('/models、/logs 重定向到 /system 对应 tab',
      r1.path.includes('/admin/system') && r1.path.includes('tab=models') && r1.active.includes('模型') && r1.chain === 2 &&
      r2.path.includes('/admin/system') && r2.path.includes('tab=logs') && r2.active.includes('日志') && r2.box,
      `/admin/models → ${r1.path}（激活「${r1.active}」，调用链 ${r1.chain} 节点）；/admin/logs → ${r2.path}（激活「${r2.active}」）`)

    // ---------- ⑪ 页面里搜不到被清理的词 ----------
    const html = await page.evaluate(() => document.documentElement.outerHTML)
    const inner = await page.evaluate(() => document.body.innerText)
    const banned = ['napcat', 'logger', 'baseurl', '其他参数', '接口地址']
    const hits = banned.filter((w) => (html + '\n' + inner).toLowerCase().includes(w))
    check('页面上搜不到 napcat / logger / baseUrl / 其他参数 / 接口地址',
      hits.length === 0, hits.length ? `命中：${hits.join(', ')}` : `已检查 ${banned.length} 个词，均未出现（含 DOM 源码与正文）`)

    // ---------- ⑫ 0 console error ----------
    check('全程 0 console error', errors.length === 0,
      errors.length ? `${errors.length} 条，首条：${errors[0]}` : '干净')

    // ---------- ⑬ 不带 tab 的裸路径落到默认 tab；窄屏下页面栏不塌 ----------
    await page.goto(BASE + '/admin/system', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(2000)
    const bare = await page.evaluate(() => ({
      path: location.pathname + location.search,
      active: (document.querySelector('.page-bar .tab.active') || {}).innerText || '',
      chain: document.querySelectorAll('.chain .node').length,
    }))
    check('裸 /system 落到默认 tab（模型）',
      bare.path === '/admin/system' && bare.active.includes('模型') && bare.chain === 2,
      `${bare.path} → 激活「${bare.active}」，调用链 ${bare.chain} 节点`)

    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(BASE + '/admin/system?tab=logs', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(2000)
    const narrow = await page.evaluate(() => ({
      overflow: Math.round(document.documentElement.scrollWidth - window.innerWidth),
      bar: document.querySelectorAll('.page-bar').length,
      tabs: (document.querySelector('.page-bar .tab.active') || {}).innerText || '',
    }))
    check('窄屏（390px）页面栏不塌、doc 无横向溢出',
      narrow.bar === 1 && narrow.overflow <= 1 && narrow.tabs.includes('日志'),
      `page-bar=${narrow.bar}，激活「${narrow.tabs}」，横向溢出 ${narrow.overflow}px`)
    await page.screenshot({ path: path.join(SHOT_DIR, 't10-mobile-system.png'), fullPage: false })
  } catch (e) {
    check('脚本无异常', false, String(e && e.stack || e))
  } finally {
    await page.close()
    browser.close?.()
  }

  console.log('\n================ 汇总 ================')
  const bad = results.filter((r) => !r.pass)
  console.log(`${results.length - bad.length}/${results.length} 通过`)
  if (bad.length) { console.log('失败项：'); for (const b of bad) console.log('  ✗ ' + b.name) }
  process.exit(bad.length ? 1 : 0)
})()
