
/**
 * 主题切换验收。
 *
 * 检查四件事：
 *   ① 没有存储时跟随系统偏好
 *   ② 点一次按钮即切换，并写进 localStorage
 *   ③ 刷新后首帧就是上次选的模式（内联脚本生效，不会先闪一下夜间）
 *   ④ 换页、进后台都保持不变；theme-color 跟着页面底走
 */
import { createRequire } from 'node:module'
const require = createRequire(process.env.PW_ROOT || 'file:///E:/MyselfProgram/JavaScript/nodejs/node_global/node_modules/')
const { chromium } = require('@playwright/test')

const BASE = process.env.BASE || 'http://127.0.0.1:5173'
const PW = process.env.ADMIN_PASSWORD || '1234567800'
const browser = await chromium.launch()
let fails = 0
const ok = (name, cond, extra = '') => {
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (extra ? '  ' + extra : ''))
  if (!cond) fails++
}

/* ① 系统偏好 */
for (const [scheme, want] of [['dark', 'night'], ['light', 'day']]) {
  const ctx = await browser.newContext({ colorScheme: scheme })
  const p = await ctx.newPage()
  await p.goto(BASE + '/', { waitUntil: 'domcontentloaded' })
  const got = await p.evaluate(() => document.documentElement.dataset.theme)
  ok('首次访问跟随系统 ' + scheme, got === want, 'got=' + got)
  await ctx.close()
}

/* ② 切换 + ③ 持久化 + ④ 跨页 */
const ctx = await browser.newContext({ colorScheme: 'dark' })
const p = await ctx.newPage()
await p.goto(BASE + '/', { waitUntil: 'networkidle' })

const before = await p.evaluate(() => document.documentElement.dataset.theme)
await p.click('button.tt')
await p.waitForTimeout(420)
const after = await p.evaluate(() => ({
  theme: document.documentElement.dataset.theme,
  stored: localStorage.getItem('qqbot.theme'),
  meta: document.querySelector('meta[name="theme-color"]')?.getAttribute('content'),
  bg: getComputedStyle(document.body).backgroundColor,
}))
ok('点击后切到日间', before === 'night' && after.theme === 'day')
ok('写入 localStorage', after.stored === 'day', String(after.stored))
ok('theme-color 跟着变', after.meta === '#e9e1cf', String(after.meta))
ok('页面底色变浅', after.bg === 'rgb(233, 225, 207)', after.bg)

/* 刷新后首帧：domcontentloaded 时 data-theme 已经是 day */
await p.reload({ waitUntil: 'domcontentloaded' })
const firstPaint = await p.evaluate(() => document.documentElement.dataset.theme)
ok('刷新首帧就是日间', firstPaint === 'day', 'got=' + firstPaint)

await p.goto(BASE + '/library', { waitUntil: 'networkidle' })
ok('换页保持日间', (await p.evaluate(() => document.documentElement.dataset.theme)) === 'day')

await p.goto(BASE + '/admin/login', { waitUntil: 'networkidle' })
ok('后台登录页保持日间', (await p.evaluate(() => document.documentElement.dataset.theme)) === 'day')
await p.evaluate(async (pw) => {
  await fetch('/admin/api/login', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ password: pw }), credentials: 'same-origin',
  })
}, PW)
await p.goto(BASE + '/admin/', { waitUntil: 'networkidle' })
ok('后台内页保持日间', (await p.evaluate(() => document.documentElement.dataset.theme)) === 'day')
await p.click('button.tt')
await p.waitForTimeout(360)
ok('后台也能切回夜间', (await p.evaluate(() => document.documentElement.dataset.theme)) === 'night')

await ctx.close()
await browser.close()
console.log('\n失败 ' + fails + ' 项')
process.exit(fails ? 1 : 0)
