/**
 * 地图功能的人工验收辅助脚本 —— 机械的部分交给它，好不好看由人来看。
 *
 * 它做四件事：
 *   1. 打开公开站 /map，等底图与点位画出来，截图；
 *   2. 搜一个地点，确认给出了「相对某个地标的方位」，截图；
 *   3. 拖一下地图，确认拖得动且没报错；
 *   4. 登录管理端，打开 /admin/map，截图。
 *
 * 同时收集控制台报错与 4xx/5xx —— 这两类问题是"页面看着还行但其实坏了"的主要来源。
 *
 * 用法：
 *   BASE=http://127.0.0.1:8094 node tests/verify-map.js
 *
 * 依赖 Playwright。本机装在全局，所以默认从 NODE_PATH 找；
 * 找不到时用 PW_MODULE 指一个绝对路径。
 */
const fs = require('node:fs')
const path = require('node:path')

function loadPlaywright() {
  const candidates = [
    process.env.PW_MODULE,
    '@playwright/test',
    'playwright',
    'playwright-core',
  ].filter(Boolean)
  for (const name of candidates) {
    try {
      return require(name)
    } catch (e) {
      // 继续试下一个
    }
  }
  throw new Error('找不到 Playwright。设置 PW_MODULE 指向它，或把全局 node_modules 加进 NODE_PATH')
}

const { chromium } = loadPlaywright()

const BASE = process.env.BASE || 'http://127.0.0.1:8080'
const OUT = path.join(__dirname, 'screenshots')
const ROOT = path.resolve(__dirname, '..')

/** 从仓库根的 .env 里取管理员密码 —— 脚本不硬编码口令 */
function adminPassword() {
  if (process.env.ADMIN_PASSWORD) return process.env.ADMIN_PASSWORD
  try {
    const text = fs.readFileSync(path.join(ROOT, '.env'), 'utf8')
    for (const line of text.split(/\r?\n/)) {
      const m = /^\s*ADMIN_PASSWORD\s*=\s*(.*)$/.exec(line)
      if (m) return m[1].trim().replace(/^["']|["']$/g, '')
    }
  } catch (e) {
    // 没有 .env 就跳过管理端那一步
  }
  return ''
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  const problems = []
  const notes = []

  const browser = await chromium.launch()
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await ctx.newPage()

  page.on('console', (m) => {
    if (m.type() === 'error') problems.push('console: ' + m.text().slice(0, 200))
  })
  page.on('pageerror', (e) => problems.push('pageerror: ' + String(e).slice(0, 200)))
  page.on('requestfailed', (r) => {
    // 底图瓦片偶尔会被浏览器取消（切档位时正常），只记非图片的失败
    if (!/\.(jpg|jpeg|png|webp)/i.test(r.url())) {
      problems.push('requestfailed: ' + r.url().slice(0, 160))
    }
  })
  page.on('response', (r) => {
    if (r.status() >= 400) problems.push('HTTP ' + r.status() + ' ' + r.url().slice(0, 160))
  })

  // ---------- 1. 公开站地图 ----------
  await page.goto(BASE + '/map', { waitUntil: 'domcontentloaded', timeout: 45000 })
  await page.waitForSelector('.map-host canvas', { timeout: 30000 })
  await page.waitForTimeout(4000) // 让瓦片与图标落地

  const canvasBox = await page.locator('.map-host').boundingBox()
  notes.push('map canvas box: ' + JSON.stringify(canvasBox))
  notes.push('可见点位：' + (await page.locator('.map-host .sr-only').first().textContent() || '').trim())
  notes.push('图层开关数：' + (await page.locator('.rail .layer').count()))
  notes.push('地图 chip 数：' + (await page.locator('.rail .chip').count()))
  await page.screenshot({ path: path.join(OUT, 'map-1-viewer.png') })

  // ---------- 2. 找地方 ----------
  const box = page.locator('.find input').first()
  await box.fill('远古尖塔')
  await page.locator('.find button[type=submit]').click()
  await page.waitForTimeout(2500)
  const hits = await page.locator('.hits .hit').count()
  notes.push('locate 命中条数：' + hits)
  if (hits > 0) {
    notes.push('第一条：' + (await page.locator('.hits .hit').first().innerText()).replace(/\s+/g, ' ').slice(0, 160))
  }
  await page.screenshot({ path: path.join(OUT, 'map-2-locate.png') })

  // ---------- 3. 拖动 ----------
  // 落点选在**左上四分之一**：详情卡在右下、缩放按钮在左上角，
  // 从正中间拖会先撞上详情卡 —— 那不是"拖不动"，是"没拖到地图上"。
  const cx = canvasBox.x + canvasBox.width * 0.35
  const cy = canvasBox.y + canvasBox.height * 0.35
  await page.mouse.move(cx, cy)
  await page.mouse.down()
  await page.mouse.move(cx - 220, cy - 130, { steps: 24 })
  await page.mouse.up()
  await page.waitForTimeout(1200)
  await page.screenshot({ path: path.join(OUT, 'map-3-panned.png') })

  // 缩一下再放大，顺便看瓦片档位切换有没有炸
  await page.mouse.move(cx, cy)
  await page.mouse.wheel(0, -600)
  await page.waitForTimeout(1200)
  await page.screenshot({ path: path.join(OUT, 'map-4-zoomed.png') })

  // ---------- 4. 管理端 ----------
  const pwd = adminPassword()
  if (!pwd) {
    notes.push('跳过管理端：没读到 ADMIN_PASSWORD')
  } else {
    await page.goto(BASE + '/admin/login', { waitUntil: 'domcontentloaded', timeout: 45000 })
    await page.fill('input[type=password]', pwd)
    await page.locator('button[type=submit]').first().click()
    await page.waitForTimeout(2500)
    await page.goto(BASE + '/admin/map', { waitUntil: 'domcontentloaded', timeout: 45000 })
    await page.waitForTimeout(3500)
    notes.push('管理端 URL：' + page.url())
    notes.push('管理端卡片数：' + (await page.locator('.card').count()))
    await page.screenshot({ path: path.join(OUT, 'map-5-admin.png'), fullPage: false })

    // 切到状态页看一眼任务面板
    await page.locator('.tabs button', { hasText: '同步与状态' }).first().click().catch(() => {})
    await page.waitForTimeout(1500)
    await page.screenshot({ path: path.join(OUT, 'map-6-admin-status.png') })
  }

  await browser.close()

  console.log(JSON.stringify({
    base: BASE,
    problems: problems.slice(0, 25),
    problemCount: problems.length,
    notes,
  }, null, 2))
}

main().catch((e) => {
  console.error('FAILED: ' + (e && e.stack ? e.stack : e))
  process.exit(1)
})
