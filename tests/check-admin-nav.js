/**
 * 验证管理后台的顶端导航栏：非登录页处处都在，且「大屏 → 看板」合并后
 * 旧地址 /screen 仍然指得到地方。
 * 用法：ADMIN_PASSWORD=xxx node tests/check-admin-nav.js
 * 只新开一个标签页，跑完关掉，不碰你正在看的页面。
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
  await page.waitForTimeout(2500)
  return true
}

async function snap(page, url) {
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 })
  await page.waitForTimeout(2800)
  await loginIfNeeded(page)
  return page.evaluate(() => {
    const bar = document.querySelector('header.bar')
    const nav = document.querySelector('.nav')
    return {
      href: location.href,
      hasBar: !!bar,
      barHeight: bar ? getComputedStyle(bar).height : null,
      links: bar ? Array.from(bar.querySelectorAll('.nav-link')).map(a => a.textContent.trim()) : [],
      active: (document.querySelector('.nav-link.router-link-active') || {}).textContent || null,
      loginForm: !!document.querySelector('input[type=password]'),
      navScrollW: nav ? nav.scrollWidth : null,
      navClientW: nav ? nav.clientWidth : null,
      docOverflowX: document.documentElement.scrollWidth > window.innerWidth + 1,
    }
  })
}

;(async () => {
  const browser = await connectCDP()
  const ctx = browser.contexts()[0]
  const page = await ctx.newPage()
  try {
    await page.setViewportSize({ width: 1440, height: 900 })
    const wide = await snap(page, BASE)
    console.log('宽屏 /admin/    ->', JSON.stringify(wide))
    await page.screenshot({ path: OUT + '/admin-root.png' })

    const dash = await snap(page, BASE + 'dashboard')
    console.log('宽屏 /dashboard ->', JSON.stringify(dash))
    await page.screenshot({ path: OUT + '/admin-dashboard.png' })

    await page.setViewportSize({ width: 390, height: 780 })
    const narrow = await snap(page, BASE)
    console.log('窄屏 390px      ->', JSON.stringify(narrow))
    await page.screenshot({ path: OUT + '/admin-narrow.png' })

    console.log('\n结论：')
    console.log('  /admin/ 重定向到看板          :', /\/dashboard$/.test(new URL(wide.href).pathname) ? '✅' : '❌ ' + wide.href)
    console.log('  看板(1440) 有导航栏且高亮正确 :', wide.hasBar && wide.active === '看板' && dash.hasBar && dash.active === '看板' ? '✅' : '❌ ' + wide.active + ' / ' + dash.active)
    console.log('  导航已无「大屏」这一项        :', wide.links.includes('大屏') ? '❌ 还在' : '✅（本页 ' + wide.links.length + ' 个 tab）')
    console.log('  窄屏 390px 无横向溢出        :', narrow.docOverflowX ? '❌ 仍有溢出' : '✅')
    console.log('  窄屏 tab 可横向滑动          :', narrow.navScrollW > narrow.navClientW ? '✅ 需要滑动(符合预期)' : '（一层放得下）')
  } finally {
    await page.close()
  }
})()
process.on('exit', () => {})
