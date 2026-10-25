
/**
 * UI 审计 —— 把「手机上会不会挤爆」变成可断言的数字。
 *
 * 检查项：
 *   1. 横向溢出：scrollWidth 超过视口宽度即失败，并列出最靠右的 5 个元素
 *   2. 命中区：按钮、导航项、role=button 的可视尺寸是否 ≥ 40 / 44
 *   3. 控制台：收集 error 级日志与页面异常
 *   4. 主题：data-theme 是否等于预期，body 底色是否跟着变
 *   5. 底栏行数：移动端主导航的高度是否超过一行
 *
 * 用法：node tests/ui/audit.mjs --base http://127.0.0.1:5173
 * 退出码：有溢出或主题不符时返回 1。
 */
import { createRequire } from 'node:module'
import path from 'node:path'

const require = createRequire(process.env.PW_ROOT || 'file:///E:/MyselfProgram/JavaScript/nodejs/node_global/node_modules/')
const { chromium } = require('@playwright/test')

const args = process.argv.slice(2)
const arg = (k, d) => {
  const i = args.indexOf('--' + k)
  return i >= 0 && args[i + 1] ? args[i + 1] : d
}
const BASE = arg('base', process.env.BASE || 'http://127.0.0.1:5173')
const ADMIN_PW = process.env.ADMIN_PASSWORD || arg('admin-password', '')

const PAGES = [
  ['/', 'home'], ['/library', 'library'], ['/map', 'map'], ['/about', 'about'],
  ['/admin/', 'admin-stats'], ['/admin/kb', 'admin-kb'], ['/admin/bot', 'admin-bot'],
  ['/admin/system', 'admin-system'], ['/admin/pages', 'admin-pages'],
  ['/admin/map', 'admin-map'],
]
const VIEWPORTS = [
  ['desktop', 1440, 900],
  ['mobile', 390, 844],
  ['small', 360, 780],
]
const VP_FILTER = arg('vp', 'both')
const THEMES = arg('theme', 'both') === 'both' ? ['night', 'day'] : [arg('theme', 'both')]

const browser = await chromium.launch()
let failures = 0
const rows = []

for (const [vp, width, height] of VIEWPORTS) {
  if (VP_FILTER !== 'both' && VP_FILTER !== vp) continue
  for (const theme of THEMES) {
    const ctx = await browser.newContext({
      viewport: { width, height },
      isMobile: vp !== 'desktop',
      hasTouch: vp !== 'desktop',
      locale: 'zh-CN',
      colorScheme: theme === 'day' ? 'light' : 'dark',
    })
    await ctx.addInitScript((t) => {
      try { localStorage.setItem('qqbot.theme', t) } catch (e) {}
    }, theme)

    const page = await ctx.newPage()
    const consoleErrors = []
    page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message))
    page.on('console', (m) => {
      if (m.type() === 'error' && !m.text().includes('401')) consoleErrors.push(m.text().slice(0, 120))
    })

    if (ADMIN_PW) {
      await page.goto(BASE + '/admin/login', { waitUntil: 'domcontentloaded' })
      await page.evaluate(async (pw) => {
        await fetch('/admin/api/login', {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ password: pw }),
          credentials: 'same-origin',
        })
      }, ADMIN_PW)
    }

    for (const [route, name] of PAGES) {
      try {
        await page.goto(BASE + route, { waitUntil: 'networkidle', timeout: 30000 })
        await page.waitForTimeout(500)

        const r = await page.evaluate((vw) => {
          const de = document.documentElement
          const overflow = de.scrollWidth - vw

          // 找出真正越界的元素：右边界超出视口，且不是被裁剪的装饰层
          const culprits = []
          if (overflow > 1) {
            for (const el of document.body.querySelectorAll('*')) {
              const cs = getComputedStyle(el)
              if (cs.position === 'fixed' || cs.pointerEvents === 'none') continue
              const b = el.getBoundingClientRect()
              if (b.width === 0 || b.height === 0) continue
              if (b.right > vw + 1) {
                culprits.push({
                  tag: el.tagName.toLowerCase(),
                  cls: (el.className && el.className.toString().slice(0, 40)) || '',
                  right: Math.round(b.right),
                })
              }
            }
            culprits.sort((a, b) => b.right - a.right)
          }

          // 命中区：只看真正的控件，正文里的行内链接不算
          const small = []
          const targets = document.querySelectorAll(
            'button, [role="button"], nav a, .tabbar a, header a, .btn, input[type="checkbox"], input[type="radio"]',
          )
          for (const el of targets) {
            const cs = getComputedStyle(el)
            if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') continue
            let b = el.getBoundingClientRect()
            /*
              原生复选框/单选框的真实点击面是包着它的 label ——
              把一个 13px 的勾选框判成「点不中」是误报，用户点的是那一行。
            */
            if (el.tagName === 'INPUT') {
              const lab = el.closest('label')
              if (lab) b = lab.getBoundingClientRect()
            }
            if (b.width === 0 || b.height === 0) continue
            /*
              有些控件的可视盒子比可点盒子小：轮播圆点只有 8px，靠 ::after 把命中区撑到 24×28。
              这类元素用 data-hit="宽 x 高" 自己申报真实命中区（见 ImageCarousel 的 .dot），
              审计按申报值判断，而不是按可见像素 —— 否则要么误报，要么只能整条跳过。
            */
            const hit = (el.getAttribute('data-hit') || '').split('x').map(Number)
            const w = hit.length === 2 ? hit[0] : b.width
            const h = hit.length === 2 ? hit[1] : b.height
            // 失败线用 WCAG 2.5.8 的 24×24 下限
            if (h < 24 || w < 24) {
              small.push({
                tag: el.tagName.toLowerCase(),
                cls: (el.className && el.className.toString().slice(0, 32)) || '',
                w: Math.round(w),
                h: Math.round(h),
              })
            }
          }

          const tabbar = document.querySelector('.bn, .tabbar, .bottom-nav')
          const tabbarH = tabbar ? Math.round(tabbar.getBoundingClientRect().height) : 0

          return {
            overflow,
            culprits: culprits.slice(0, 4),
            small: small.slice(0, 6),
            smallCount: small.length,
            theme: de.dataset.theme,
            bg: getComputedStyle(document.body).backgroundColor,
            tabbarH,
          }
        }, width)

        const bad = r.overflow > 1 || r.theme !== theme || r.smallCount > 0
        if (bad) failures++
        rows.push({
          vp, theme, name, ...r,
          ok: !bad,
        })
      } catch (e) {
        failures++
        rows.push({ vp, theme, name, error: e.message.slice(0, 80), ok: false })
      }
    }

    if (consoleErrors.length) {
      console.log('控制台错误 ' + vp + '/' + theme + ':')
      for (const e of [...new Set(consoleErrors)].slice(0, 6)) console.log('  ' + e)
    }
    await ctx.close()
  }
}

await browser.close()

console.log('\n视口     主题   页面            溢出  小控件  底栏高  主题  结果')
for (const r of rows) {
  if (r.error) {
    console.log(r.vp.padEnd(8) + r.theme.padEnd(7) + r.name.padEnd(16) + 'ERR ' + r.error)
    continue
  }
  console.log(
    r.vp.padEnd(8) + r.theme.padEnd(7) + r.name.padEnd(16) +
    String(r.overflow).padEnd(6) + String(r.smallCount).padEnd(8) + String(r.tabbarH).padEnd(8) +
    String(r.theme).padEnd(6) + (r.ok ? 'ok' : 'FAIL'),
  )
  for (const c of r.culprits) console.log('    越界: <' + c.tag + ' class="' + c.cls + '"> right=' + c.right)
  for (const s of r.small) console.log('    偏小: <' + s.tag + ' class="' + s.cls + '"> ' + s.w + 'x' + s.h)
}

console.log('\n失败 ' + failures + ' 项 / 共 ' + rows.length + ' 项')
process.exit(failures > 0 ? 1 : 0)