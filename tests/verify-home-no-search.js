/**
 * 公开站首页「不再提供搜索」的真机验收脚本。
 *
 * 2026-09-27：产品决定首页只做落地页，搜索统一放在「资料库」页。
 * 这个脚本逐条给出可观察证据：
 *   ① 首页渲染正常（大标题、副标语、怎么用三条）
 *   ② 首页**没有**搜索框：没有 input[type=search]、没有 .search、没有示例词 .chip
 *   ③ 「怎么用」第 2 条指向 /library（入口没有断，只是不再是搜索框）
 *   ④ 点导航「资料库」进去，**搜索框仍在** —— 删的是首页入口，不是搜索本身
 *   ⑤ 全程 0 console error
 *
 * 只走公开站，不需要登录，也不需要 mock：/api/public/** 是公开只读的。
 *
 * 用法：BASE=http://<宿主IP>:5173 node tests/verify-home-no-search.js
 *      （BASE 里出现网关 IP 时会自动换成 localhost —— Chrome 在宿主上）
 */
const fs = require('node:fs')
const path = require('node:path')
const { execSync } = require('node:child_process')
const { connectCDP } = require('/root/.playwright/cdp')
const { installDepShim } = require('./lib/dep-shim.cjs')

const HOST_IP = (() => {
  try {
    return execSync("getent ahostsv4 host.docker.internal | awk '{print $1}' | head -n1",
      { encoding: 'utf8' }).trim()
  } catch { return '' }
})()

const RAW_BASE = process.env.BASE || 'http://localhost:5173'
// 网关 IP 是容器视角的地址，宿主浏览器打不开 —— 一律换回 localhost
const BASE = HOST_IP ? RAW_BASE.split(HOST_IP).join('localhost') : RAW_BASE
const SHIM_BASE = HOST_IP
  ? BASE.replace('//localhost', '//' + HOST_IP).replace('//127.0.0.1', '//' + HOST_IP)
  : BASE

const SHOT_DIR = path.join(__dirname, 'screenshots/redesign')
const WIDE = { width: 1280, height: 900 }

let pass = 0
let fail = 0
const check = (name, ok, detail = '') => {
  if (ok) { pass++; console.log('  ✅ ' + name + (detail ? ' — ' + detail : '')) }
  else { fail++; console.log('  ❌ ' + name + (detail ? ' — ' + detail : '')) }
}

async function main() {
  const browser = await connectCDP()
  // 自己开一个上下文：宿主那份 Chrome 里可能开着用户正在用的标签页，不去碰它
  const ctx = await browser.newContext({ viewport: WIDE })
  const page = await ctx.newPage()

  const consoleErrors = []
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()) })
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message))

  const shim = installDepShim(page, { fetchBase: SHIM_BASE })

  console.log('BASE = ' + BASE + '\n')

  // ==================== ① 首页 ====================
  console.log('=== ① 首页渲染 ===')
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 30000 })
  await page.waitForTimeout(1200)

  const heroTitle = await page.locator('.hero-title').first().innerText().catch(() => '')
  check('首页大标题渲染出来了', heroTitle.replace(/\s/g, '').includes('灵火'), JSON.stringify(heroTitle))
  const heroSub = await page.locator('.hero-sub').first().innerText().catch(() => '')
  check('副标语渲染出来了', heroSub.length > 0, heroSub.slice(0, 40))

  // ==================== ② 没有搜索框 ====================
  console.log('\n=== ② 首页不该再有搜索 ======')
  const searchInputs = await page.locator('input[type="search"]').count()
  check('没有 input[type=search]', searchInputs === 0, 'count=' + searchInputs)
  const formSearch = await page.locator('form.search, .search, .search-input').count()
  check('没有 .search / .search-input 结构', formSearch === 0, 'count=' + formSearch)
  const chips = await page.locator('.chip, .examples').count()
  check('没有「试着问」示例词', chips === 0, 'count=' + chips)
  const bodyText = await page.locator('body').innerText()
  check('页面文字里不再出现「试着问」', !bodyText.includes('试着问'))
  check('页面文字里不再出现「上面的搜索框」', !bodyText.includes('上面的搜索框'))

  // ==================== ③ 怎么用第 2 条指向资料库 ====================
  console.log('\n=== ③ 「怎么用」第 2 条的入口 ===')
  const howtoLinks = await page.locator('.howto a').allInnerTexts().catch(() => [])
  const howtoHrefs = await page.locator('.howto a').evaluateAll(
    (els) => els.map((e) => e.getAttribute('href'))).catch(() => [])
  check('第 2 条里的「资料库」是可点的链接', howtoLinks.includes('资料库'), JSON.stringify(howtoLinks))
  check('它指向 /library', howtoHrefs.includes('/library'), JSON.stringify(howtoHrefs))

  await page.screenshot({ path: path.join(SHOT_DIR, 'home-no-search.png'), fullPage: true })

  // ==================== ④ 资料库的搜索没被误伤 ====================
  console.log('\n=== ④ 资料库页的搜索必须还在 ===')
  await page.locator('.nav a', { hasText: '资料库' }).first().click()
  await page.waitForTimeout(1500)
  check('导航进了 /library', page.url().includes('/library'), page.url())
  const libSearch = await page.locator('input').count()
  check('资料库页仍有输入框', libSearch > 0, 'input count=' + libSearch)
  const libText = await page.locator('body').innerText()
  check('资料库里没有报错文案', !libText.includes('无法连接服务端'))

  await page.screenshot({ path: path.join(SHOT_DIR, 'home-after-library.png'), fullPage: true })

  // ==================== ⑤ console ====================
  console.log('\n=== ⑤ console ===')
  check('全程 0 console error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))

  console.log('\n垫片：改写 ' + shim.rewritten + ' / 命中 ' + shim.seen + ' 个 deps 模块' +
    (shim.rewritten === 0 ? '（= 恒等变换，说明 Vite 缓存已自愈）' : '（说明宿主 Vite 缓存仍是坏的）'))

  await ctx.close()
  console.log('\n结果：通过 ' + pass + ' 项，失败 ' + fail + ' 项')
  console.log('截图：tests/screenshots/redesign/home-no-search.png、home-after-library.png')
  process.exit(fail === 0 ? 0 : 1)
}

main().catch((e) => { console.error('脚本异常：', e); process.exit(2) })
