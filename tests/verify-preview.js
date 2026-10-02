/**
 * 「页面信息 → 缩略预览」真机验收脚本。
 *
 * 宿主后端（localhost:8080）现在真的在跑，所以**不打 mock**：
 *   - 数据来自真实的 GET /admin/api/pages
 *   - 页面来自后端正在服务的 server/web-dist 构建产物
 * 每次导航前 Network.clearBrowserCache —— 这个项目已经两次因为浏览器缓存
 * 读到旧 CSS 而误判「没修好」。
 *
 * 用法：ADMIN_PASSWORD=xxx node tests/verify-preview.js
 */
const fs = require('node:fs')
const path = require('node:path')
const { connectCDP } = require('/root/.playwright/cdp')

const ROOT = path.resolve(__dirname, '..')
const BASE = process.env.BASE || 'http://localhost:8080'
const PW = process.env.ADMIN_PASSWORD
const SHOT_DIR = path.join(ROOT, 'tests/screenshots/redesign')
const WIDE = { width: 1440, height: 900 }
const NARROW = { width: 390, height: 844 }

const results = []
function check(name, pass, evidence) {
  results.push({ name, pass: !!pass, evidence })
  console.log(`${pass ? '  ✅' : '  ❌'} ${name}\n      ${evidence}`)
}
const one = (s) => (s || '').replace(/\s+/g, ' ').trim()

/** 读预览区：关键文本 + 火色标记 + 舞台尺寸 */
const readPreview = (page) => page.evaluate(() => {
  const col = document.querySelector('.preview-col')
  if (!col) return null
  const one = (s) => (s || '').replace(/\s+/g, ' ').trim()
  const stage = col.querySelector('.stage')
  const marked = [...col.querySelectorAll('.stage .changed')]
  return {
    title: (col.querySelector('.preview-title') || {}).innerText || '',
    cap: (col.querySelector('.preview-cap') || {}).innerText || '',
    text: (stage || {}).innerText || '',
    html: (stage || {}).innerHTML || '',
    markedCount: marked.length,
    markedKeys: marked.map((m) => one(m.innerText).slice(0, 40)),
    markedShadow: marked.map((m) => getComputedStyle(m).boxShadow).slice(0, 3),
    links: [...col.querySelectorAll('.stage a')].map((a) => a.getAttribute('href') + '|' + one(a.innerText)),
    strongs: [...col.querySelectorAll('.stage strong')].map((s) => one(s.innerText)),
    rect: col.getBoundingClientRect().toJSON(),
    stageHeight: stage ? stage.getBoundingClientRect().height : 0,
  }
})

const editorValues = (page) => page.evaluate(() => {
  const one = (s) => (s || '').replace(/\s+/g, ' ').trim()
  return [...document.querySelectorAll('.panel-wrap .blk')].map((b) => ({
    label: one((b.querySelector('.blk-label') || {}).innerText || ''),
    value: (b.querySelector('textarea, input') || {}).value ?? '',
  }))
})

/** 在某个 label 的块里输入文本（用 DOM 事件，绕开"元素不可见"） */
const typeInto = (page, label, value) => page.evaluate(({ label, value }) => {
  const one = (s) => (s || '').replace(/\s+/g, ' ').trim()
  const blk = [...document.querySelectorAll('.panel-wrap .blk')]
    .find((b) => one((b.querySelector('.blk-label') || {}).innerText || '') === label)
  if (!blk) return false
  const el = blk.querySelector('textarea, input')
  const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement : HTMLInputElement
  Object.getOwnPropertyDescriptor(proto.prototype, 'value').set.call(el, value)
  el.dispatchEvent(new Event('input', { bubbles: true }))
  return true
}, { label, value })

/** 二级目录按【可见文字】点（Tabs 组件不挂 data-key，只有 label + count） */
const TAB_LABEL = {
  layout: '全站', home: '首页', about: '关于',
  library: '资料库', plaza: '问答广场', notfound: '404',
}
const switchPage = async (page, key) => {
  const want = TAB_LABEL[key]
  const clicked = await page.evaluate((label) => {
    const one = (s) => (s || '').replace(/\s+/g, ' ').trim()
    const tab = [...document.querySelectorAll('.page-bar .tab')]
      .find((t) => one(t.innerText).startsWith(label))
    if (!tab) return false
    tab.click()
    return true
  }, want)
  await page.waitForTimeout(1500)
  return clicked
}

;(async () => {
  if (!PW) throw new Error('需要 ADMIN_PASSWORD')
  fs.mkdirSync(SHOT_DIR, { recursive: true })
  const browser = await connectCDP(60000)
  const ctx = browser.contexts()[0]
  const page = await ctx.newPage()
  await page.setViewportSize(WIDE)

  const errors = []
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text().slice(0, 240)) })
  page.on('pageerror', (e) => errors.push('pageerror: ' + String(e.message || e).slice(0, 240)))

  const cdp = await ctx.newCDPSession(page)
  await cdp.send('Network.enable')

  // ---------- 登录（真接口） ----------
  await cdp.send('Network.clearBrowserCache')
  await page.goto(BASE + '/admin/login', { waitUntil: 'domcontentloaded', timeout: 40000 })
  await page.waitForTimeout(1500)
  const login = await page.evaluate(async (pw) => {
    const r = await fetch('/admin/api/login', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      credentials: 'same-origin', body: JSON.stringify({ password: pw }),
    })
    return r.status
  }, PW)
  check('真接口登录成功（未打 mock）', login === 200, `POST /admin/api/login → ${login}`)

  // ---------- 打开页面信息 ----------
  await cdp.send('Network.clearBrowserCache')
  await page.goto(BASE + '/admin/pages', { waitUntil: 'domcontentloaded', timeout: 40000 })
  await page.waitForTimeout(3000)

  const reqs = await page.evaluate(() =>
    performance.getEntriesByType('resource').map((e) => e.name))
  check('打到真后端的 /admin/api/pages（无 route mock）',
    reqs.some((u) => u.includes('/admin/api/pages')),
    `接口请求：${reqs.filter((u) => u.includes('/admin/api/')).slice(0, 3).join(', ')}`)

  const boot = await page.evaluate(() => {
    const one = (s) => (s || '').replace(/\s+/g, ' ').trim()
    return {
      tabs: [...document.querySelectorAll('.page-bar .tab')].map((t) => one(t.innerText)),
      cols: document.querySelectorAll('.cols').length,
      previewCols: document.querySelectorAll('.preview-col').length,
    }
  })
  check('两栏布局存在，预览区已渲染', boot.cols === 1 && boot.previewCols === 1,
    `二级目录=[${boot.tabs.join(' / ')}]；.cols=${boot.cols}，.preview-col=${boot.previewCols}`)

  // ---------- 逐页预览 ----------
  const pages = ['layout', 'home', 'about', 'library', 'plaza', 'notfound']
  // 期望值用【默认文案】里稳定存在的片段。⚠️ 不能用 about.what_title / source_title：
  // 真库里这两块有覆盖值（「飘雪喵」「资料来源」），预览渲染的是**生效值**而不是默认值。
  const EXPECT = {
    layout: ['资料来源于', '飘雪喵'],
    home: ['雾中', '灵火', '怎么用', '在群里 @ 我提问'],
    about: ['关于', '回答是怎么产生的', '把问题变成向量', '隐私'],
    library: ['资料库', 'Wiki 的中文索引', '没找到相关资料'],
    plaza: ['问答广场', '群友们问过'],
    notfound: ['迷失在雾里了', '回到首页'],
  }
  const toggled = []
  for (const k of pages) {
    const clicked = await switchPage(page, k)
    const active = await page.evaluate(() =>
      ((document.querySelector('.page-bar .tab.active') || {}).innerText || '').replace(/\s+/g, ' ').trim())
    toggled.push(`${k}→点「${TAB_LABEL[k]}」${clicked ? '成功' : '失败'}，激活=「${active}」`)
    const pv = await readPreview(page)
    const missing = EXPECT[k].filter((f) => !(pv.text || '').includes(f))
    check(`「${k}」预览渲染出文案`, pv && pv.text.trim().length > 0 && missing.length === 0,
      `预览关键文本：${one(pv.text).slice(0, 160)}${missing.length ? ` ⚠️缺=${missing.join(',')}` : ''}`)
    await page.screenshot({ path: path.join(SHOT_DIR, `preview-page-${k}.png`) })
  }
  console.log(`      （切页方式：${toggled.join(', ')}）`)

  // ---------- 占位符 ----------
  await switchPage(page, 'home')
  const homeText = (await readPreview(page)).text
  await switchPage(page, 'layout')
  const layoutText = (await readPreview(page)).text
  const literal = [homeText, layoutText].filter((t) => t.includes('{kb}') || t.includes('{year}'))
  const year = String(new Date().getFullYear())
  check('预览里搜不到字面 {kb} / {year}，且能看到替换后的值',
    literal.length === 0 && homeText.includes('4,131') && layoutText.includes(year),
    `字面 {kb}/{year} 命中=${literal.length}；首页预览含「4,131」=${homeText.includes('4,131')}；` +
    `页脚预览含年份 ${year}=${layoutText.includes(year)}；页脚预览=${one(layoutText)}`)

  // ---------- 实时性 ----------
  await switchPage(page, 'home')
  const before = (await editorValues(page)).find((b) => b.label === '副标语')
  const pvBefore = await readPreview(page)
  const NEW_TAGLINE = '实时性验证：改一个字，预览立刻跟着变'
  await typeInto(page, '副标语', NEW_TAGLINE)
  await page.waitForTimeout(400)
  const pvAfter = await readPreview(page)
  check('实时性：编辑框改字后预览立刻变（未保存）',
    pvAfter.text.includes(NEW_TAGLINE) && !pvAfter.text.includes('在《雾锁王国》的迷雾里'),
    `改前预览含原句=${pvBefore.text.includes('在《雾锁王国》的迷雾里')}；` +
    `改后预览含新句「${NEW_TAGLINE}」=${pvAfter.text.includes(NEW_TAGLINE)}；` +
    `改后预览片段=${one(pvAfter.text).slice(0, 100)}`)

  // ---------- 改动标记 ----------
  check('改动标记：改过的块在预览里有可观察区分',
    pvAfter.markedCount === 1 && pvAfter.markedShadow.some((s) => s.includes('232, 160, 76')),
    `改后 .changed 数=${pvAfter.markedCount}（改前=${pvBefore.markedCount}），` +
    `命中块=${JSON.stringify(pvAfter.markedKeys)}，box-shadow=${JSON.stringify(pvAfter.markedShadow)}`)
  check('预览顶部只有一句极短说明',
    one(pvAfter.cap) === '火色标记 = 已改动',
    `说明文本=「${one(pvAfter.cap)}」`)

  // ---------- 行内标记 ----------
  const RICH = '换 [文字](https://example.com) 与 **加粗** 试试'
  await typeInto(page, '副标语', RICH)
  await page.waitForTimeout(400)
  const pvRich = await readPreview(page)
  check('行内标记：预览里确实出现 <a> 与 <strong>',
    pvRich.links.length > 0 && pvRich.links[0].includes('https://example.com') &&
    pvRich.strongs.includes('加粗'),
    `a=${JSON.stringify(pvRich.links)}；strong=${JSON.stringify(pvRich.strongs)}`)

  // 还原
  await typeInto(page, '副标语', before.value)
  await page.waitForTimeout(400)
  const restored = await readPreview(page)
  check('还原后改动标记消失', restored.markedCount === 0,
    `还原后 .changed 数=${restored.markedCount}`)

  // ---------- 布局：宽屏 sticky ----------
  const sticky = await page.evaluate(async () => {
    const col = document.querySelector('.preview-col')
    const style = getComputedStyle(col)
    const top0 = col.getBoundingClientRect().top
    const scroller = document.scrollingElement
    scroller.scrollTop = scroller.scrollHeight
    await new Promise((r) => setTimeout(r, 800))
    const r1 = col.getBoundingClientRect()
    return {
      position: style.position, top0,
      topBottom: r1.top, bottomBottom: r1.bottom,
      viewportH: innerHeight, scrollTop: scroller.scrollTop,
      scrollHeight: scroller.scrollHeight, colWidth: r1.width,
    }
  })
  check('宽屏：预览 position:sticky，滚到底仍在视口内',
    sticky.position === 'sticky' && sticky.topBottom >= 0 && sticky.bottomBottom <= sticky.viewportH + 1,
    `position=${sticky.position}；初始 top=${sticky.top0.toFixed(1)}；滚到底(${sticky.scrollTop}/${sticky.scrollHeight}) ` +
    `时 top=${sticky.topBottom.toFixed(1)}、bottom=${sticky.bottomBottom.toFixed(1)}，视口高=${sticky.viewportH}`)
  check('宽屏：预览宽度在 360~420px',
    sticky.colWidth >= 360 && sticky.colWidth <= 420,
    `预览列宽=${sticky.colWidth.toFixed(1)}px`)
  await page.screenshot({ path: path.join(SHOT_DIR, 'preview-wide.png') })

  // ---------- 布局：窄屏堆叠 ----------
  await page.setViewportSize(NARROW)
  await page.waitForTimeout(900)
  const narrow = await page.evaluate(() => {
    const col = document.querySelector('.preview-col')
    const wrap = document.querySelector('.panel-wrap')
    const st = getComputedStyle(col)
    const s = document.querySelector('.preview-col .stage')
    return {
      position: st.position,
      wrapperBottom: wrap.getBoundingClientRect().bottom,
      colTop: col.getBoundingClientRect().top,
      docW: document.documentElement.scrollWidth,
      viewW: innerWidth,
      stageOverflow: s ? s.scrollWidth - s.clientWidth : -1,
      colWidth: col.getBoundingClientRect().width,
    }
  })
  check('窄屏：预览在编辑区下方堆叠且不 sticky',
    narrow.position === 'static' && narrow.colTop >= narrow.wrapperBottom - 1,
    `position=${narrow.position}；编辑区 bottom=${narrow.wrapperBottom.toFixed(1)}，` +
    `预览 top=${narrow.colTop.toFixed(1)}，预览在下方=${narrow.colTop >= narrow.wrapperBottom - 1}；预览宽=${narrow.colWidth.toFixed(1)}`)
  check('窄屏无横向溢出',
    narrow.docW <= narrow.viewW + 1 && narrow.stageOverflow <= 1,
    `documentElement.scrollWidth=${narrow.docW} ≤ innerWidth=${narrow.viewW}；` +
    `舞台 scrollWidth-clientWidth=${narrow.stageOverflow}`)
  await page.screenshot({ path: path.join(SHOT_DIR, 'preview-narrow.png'), fullPage: false })

  // ---------- 0 console error ----------
  check('全程 0 console error', errors.length === 0,
    errors.length ? `${errors.length} 条，首条：${errors[0]}` : '干净')

  console.log('\n================ 汇总 ================')
  const pass = results.filter((r) => r.pass).length
  console.log(`  ${pass} / ${results.length} 项通过`)
  for (const r of results.filter((x) => !x.pass)) console.log(`  ❌ ${r.name} — ${r.evidence}`)
  console.log(`  截图目录: ${SHOT_DIR}（前缀 preview-）`)
  await page.close()
  await browser.close()
  process.exit(pass === results.length ? 0 : 1)
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1) })
