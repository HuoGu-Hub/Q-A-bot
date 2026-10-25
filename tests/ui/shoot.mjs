
/**
 * UI 截图工具 —— 重构期间的「眼睛」。
 *
 * 用法：
 *   node tests/ui/shoot.mjs --out tests/screenshots/ui-after --tag after --theme both
 *
 * 参数：
 *   --theme night|day|both   默认 both
 *   --only <name>            只拍名字里包含该串的页面
 *   --vp desktop|mobile|both 默认 both
 *
 * 环境变量：BASE 覆盖地址，默认 http://127.0.0.1:5173；ADMIN_PASSWORD 用于后台截图。
 *
 * 实现要点：
 *   · 主题用 addInitScript 预写 localStorage，等价于「用户上次选了日间」，
 *     同时也验证了首帧不会先画夜间再翻色；
 *   · 后台先打一次登录接口，cookie 留在 context 里；
 *   · 文件名 after-{viewport}-{page}-{theme}.png，与 ui-before 一一对应。
 */
import { createRequire } from 'node:module'
import { mkdirSync } from 'node:fs'
import path from 'node:path'

const require = createRequire(process.env.PW_ROOT || 'file:///E:/MyselfProgram/JavaScript/nodejs/node_global/node_modules/')
const { chromium } = require('@playwright/test')

const args = process.argv.slice(2)
const arg = (k, d) => {
  const i = args.indexOf('--' + k)
  return i >= 0 && args[i + 1] ? args[i + 1] : d
}

const BASE = process.env.BASE || 'http://127.0.0.1:5173'
const OUT = path.resolve(arg('out', 'tests/screenshots/ui-after'))
const TAG = arg('tag', 'shot')
const THEME_ARG = arg('theme', 'both')
const VP_ARG = arg('vp', 'both')
const ONLY = arg('only', '')
const ADMIN_PW = process.env.ADMIN_PASSWORD || arg('admin-password', '')

const PAGES = [
  ['/', 'home'],
  ['/library', 'library'],
  ['/map', 'map'],
  ['/about', 'about'],
  ['/admin/', 'admin-stats'],
  ['/admin/kb', 'admin-kb'],
  ['/admin/bot', 'admin-bot'],
  ['/admin/system', 'admin-system'],
  ['/admin/pages', 'admin-pages'],
  ['/admin/map', 'admin-map'],
]

const VIEWPORTS = [
  ['desktop', { width: 1440, height: 900 }],
  ['mobile', { width: 390, height: 844 }],
]

const THEMES = THEME_ARG === 'both' ? ['night', 'day'] : [THEME_ARG]

mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch()
const errors = []

for (const [vpName, viewport] of VIEWPORTS) {
  if (VP_ARG !== 'both' && VP_ARG !== vpName) continue

  for (const theme of THEMES) {
    const ctx = await browser.newContext({
      viewport,
      deviceScaleFactor: 1,
      isMobile: vpName === 'mobile',
      hasTouch: vpName === 'mobile',
      locale: 'zh-CN',
      colorScheme: theme === 'day' ? 'light' : 'dark',
    })

    // 先写偏好再加载：这才是真实用户第二次访问的情形
    await ctx.addInitScript((t) => {
      try { localStorage.setItem('qqbot.theme', t) } catch (e) {}
    }, theme)

    const page = await ctx.newPage()
    page.on('pageerror', (e) => errors.push(vpName + '/' + theme + ' pageerror: ' + e.message))
    page.on('console', (m) => {
      if (m.type() === 'error' && !m.text().includes('401')) {
        errors.push(vpName + '/' + theme + ' console: ' + m.text().slice(0, 160))
      }
    })

    if (ADMIN_PW) {
      await page.goto(BASE + '/admin/login', { waitUntil: 'domcontentloaded' })
      const ok = await page.evaluate(async (pw) => {
        const r = await fetch('/admin/api/login', {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ password: pw }),
          credentials: 'same-origin',
        })
        return r.ok
      }, ADMIN_PW)
      if (!ok) console.log('login FAILED')
    }

    for (const [route, name] of PAGES) {
      if (ONLY && !name.includes(ONLY)) continue
      try {
        await page.goto(BASE + route, { waitUntil: 'networkidle', timeout: 30000 })
        await page.waitForTimeout(700)
        const file = path.join(OUT, TAG + '-' + vpName + '-' + name + '-' + theme + '.png')
        await page.screenshot({ path: file, fullPage: false })
        const info = await page.evaluate(() => ({
          w: document.documentElement.scrollWidth,
          h: document.documentElement.scrollHeight,
          theme: document.documentElement.dataset.theme,
          bg: getComputedStyle(document.body).backgroundColor,
        }))
        const overflow = info.w > viewport.width + 1 ? ' OVERFLOW +' + (info.w - viewport.width) : ''
        console.log(
          'OK  ' + vpName.padEnd(7) + theme.padEnd(5) + name.padEnd(14) +
          ' ' + info.w + 'x' + info.h + ' bg=' + info.bg + ' theme=' + info.theme + overflow,
        )
      } catch (e) {
        console.log('ERR ' + vpName + '/' + theme + ' ' + route + ' → ' + e.message.slice(0, 100))
      }
    }
    await ctx.close()
  }
}

await browser.close()
if (errors.length) {
  console.log('\n浏览器报错 ' + errors.length + ' 条：')
  for (const e of [...new Set(errors)].slice(0, 20)) console.log('  ' + e)
} else {
  console.log('\n无浏览器报错')
}
