/**
 * 验证「页面信息」这一轮改造（2026-09-27 的三个需求）：
 *
 *   1. 关于页的块标签只剩**角色**（标题 / 正文），段落名退到同一行右侧的浅色 hint
 *      —— 原来是「标题 · 是什么」这种挤在一起的字符串
 *   2. 首页「怎么用」与关于页正文改成**同一种编辑方式**：一块多行、一行一条
 *      —— 原来是 howto_1/2/3 三个独立输入框
 *   3. 工具栏上那行「本页 N 块」计数已删，换成「填写说明」按钮 → 点开是弹窗
 *
 * 用法：ADMIN_PASSWORD=xxx node tests/check-pages-info.js
 * 只新开一个标签页，跑完关掉，不碰你正在看的页面。
 * 截图落在 $SHOT_DIR（默认 /tmp）。
 */
const { connectCDP } = require('/root/.playwright/cdp')

const BASE = process.env.ADMIN_BASE || 'http://localhost:5173/admin/'
const OUT = process.env.SHOT_DIR || '/tmp'
const PW = process.env.ADMIN_PASSWORD || ''

async function loginIfNeeded(page) {
  const need = await page.evaluate(() => !!document.querySelector('input[type=password]'))
  if (!need) return false
  if (!PW) { console.log('（未提供 ADMIN_PASSWORD，跳过登录）'); return false }
  await page.fill('input[type=password]', PW)
  await Promise.all([
    page.waitForURL(u => !String(u).includes('/login'), { timeout: 20000 }).catch(() => {}),
    page.evaluate(() => document.querySelector('form').requestSubmit()),
  ])
  await page.waitForTimeout(2000)
  return true
}

/** 打开某个页面；刚登录完会被重定向，所以登录过就再回一次目标地址 */
async function open(page, url) {
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 })
  await page.waitForTimeout(2200)
  if (await loginIfNeeded(page)) {
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(2200)
  }
}

/** 页面上的文案块 + 工具栏现状 */
function readPage(page) {
  return page.evaluate(() => ({
    href: location.href,
    blocks: Array.from(document.querySelectorAll('.blk')).map((b) => {
      const ta = b.querySelector('textarea')
      const label = b.querySelector('.blk-label')
      const hint = b.querySelector('.blk-hint')
      return {
        label: label ? label.textContent.trim() : '',
        hint: hint ? hint.textContent.trim() : '',
        multiline: !!ta,
        lines: ta ? ta.value.split('\n').map((s) => s.trim()).filter(Boolean).length : 0,
      }
    }),
    actions: Array.from(document.querySelectorAll('.bar-actions button')).map((b) => b.textContent.trim()),
    tabs: Array.from(document.querySelectorAll('.bar-tabs button')).map((b) => b.textContent.trim()),
    /** 那行计数文案还在不在（「本页 2 块」这种） */
    countNote: /本页\s*\d+\s*块/.test(document.body.innerText),
  }))
}

;(async () => {
  const browser = await connectCDP()
  const ctx = browser.contexts()[0]
  const page = await ctx.newPage()
  let ok = true
  const check = (name, pass, detail) => {
    console.log('  ' + (pass ? '✅' : '❌') + ' ' + name + (detail ? '  → ' + detail : ''))
    if (!pass) ok = false
  }
  try {
    await page.setViewportSize({ width: 1440, height: 950 })

    // ---------- 首页：怎么用 = 一块多行 ----------
    await open(page, BASE + 'pages?p=home')
    const home = await readPage(page)
    await page.screenshot({ path: OUT + '/pages-home.png' })
    const howto = home.blocks.find((b) => b.label === '条目') || home.blocks.find((b) => b.multiline)
    console.log('\n首页（' + home.href + '）');
    console.log('  块：' + home.blocks.map((b) => b.label + (b.multiline ? '(多行' + b.lines + ')' : '')).join(' / '))
    console.log('  工具栏按钮：' + home.actions.join(' / '))
    check('「怎么用」是一个多行框且拆成 3 行', !!howto && howto.multiline && howto.lines === 3,
      howto ? 'label=' + howto.label + ' 行数=' + howto.lines : '没找到多行块')
    check('工具栏不再显示「本页 N 块」', !home.countNote)
    check('工具栏出现「填写说明」按钮', home.actions.some((t) => t.includes('填写说明')), home.actions.join('/'))

    // ---------- 填写说明弹窗 ----------
    const opened = await page.evaluate(() => {
      const b = Array.from(document.querySelectorAll('.bar-actions button'))
        .find((x) => x.textContent.includes('填写说明'))
      if (!b) return false
      b.click()
      return true
    })
    await page.waitForTimeout(700)
    const help = await page.evaluate(() => {
      const d = document.querySelector('[role=dialog]')
      return d ? { title: (d.querySelector('.title') || {}).textContent || '', text: d.innerText } : null
    })
    if (help) await page.screenshot({ path: OUT + '/pages-help.png' })
    console.log('\n填写说明弹窗：' + (help ? JSON.stringify(help.text.slice(0, 80)) + '…' : '没打开'))
    check('点「填写说明」弹出弹窗', opened && !!help)
    check('弹窗里有占位符说明 {kb}/{year}', !!help && help.text.includes('{kb}') && help.text.includes('{year}'))
    check('弹窗里有行内标记说明', !!help && help.text.includes('[文字](链接)') && help.text.includes('**文字**'))
    await page.keyboard.press('Escape')
    await page.waitForTimeout(400)
    const closed = await page.evaluate(() => !document.querySelector('[role=dialog]'))
    check('Esc 能关掉弹窗', closed)

    // ---------- 关于：标签只剩角色 ----------
    await open(page, BASE + 'pages?p=about')
    const about = await readPage(page)
    await page.screenshot({ path: OUT + '/pages-about.png' })
    console.log('\n关于页块标签：')
    for (const b of about.blocks) console.log('  ' + b.label + '  hint=' + b.hint + (b.multiline ? '  (多行)' : ''))
    check('标签里不再有「·」（如「标题 · 是什么」）',
      about.blocks.length > 0 && about.blocks.every((b) => !b.label.includes('·')),
      about.blocks.map((b) => b.label).join('/'))
    check('标题 4 块 + 正文 4 块',
      about.blocks.filter((b) => b.label === '标题').length === 4 &&
      about.blocks.filter((b) => b.label === '正文').length === 4)
    check('「关于」里的注释小字已清空（hint 全空）', about.blocks.every((b) => !b.hint),
      about.blocks.map((b) => b.hint).filter(Boolean).join('/') || '（全空）')

    console.log('\n' + (ok ? '全部通过 ✅' : '有失败项 ❌') + '  截图：' + OUT + '/pages-{home,help,about}.png')
  } finally {
    await page.close()
  }
  process.exit(ok ? 0 : 1)
})()
process.on('exit', () => {})
