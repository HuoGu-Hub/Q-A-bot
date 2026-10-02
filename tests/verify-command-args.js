/**
 * 「指令支持参数 + 智能问答类」的管理端验收脚本。
 *
 * 本次升级（2026-09-28）：
 *   ① 指令支持参数：/触发词 参数… → 模板里用 {args} / {args.1}
 *   ② 新增「智能问答」类型：参数当问题交给模型，**走 Guard 与成本预算**
 *   ③ 回答模式：走知识库 / 不用知识库（纯模型）
 *
 * 本脚本验的是**管理端这一侧**能不能把那两样配出来、配得对不对：
 *   ① 列表里 agent 指令显示成「/联网 <问题>」并带「问答·…」标签
 *   ② 编辑话术指令时**没有**「回答模式」下拉（不给人配一个不起作用的选项）
 *   ③ 编辑 agent 指令时出现「回答模式」下拉，且值是对的
 *   ④ 可用变量里有 {args} / {args.1} / {args.2}
 *   ⑤ 点变量胶囊会插入模板；示例参数会随预览请求发出去
 *   ⑥ 预览真的把 {args} 替换成了示例参数（UI → 接口 → 渲染 整条链路）
 *   ⑦ 0 console error
 *
 * 走 mock 喂数据（宿主 Vite dev 5173 服务的正是本工作区源码）。
 * 后端侧的"必须走预算"由 MessageRouterCommandTest 单测保证，不在这里重复。
 *
 * 用法：node tests/verify-command-args.js
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
const SHOT_DIR = path.join(ROOT, 'tests/screenshots/command-args')
const WIDE = { width: 1440, height: 1000 }

const calls = []
const previewBodies = []

// ==================== 假数据 ====================
const COMMANDS = [
  {
    id: 1, trigger: 'help', reply: '{cmd.list}', description: '查看所有指令', scope: 'all',
    groupIds: [], minRole: 'member', enabled: true, sortOrder: 1, builtin: true,
    kind: 'template', mode: 'kb',
  },
  {
    id: 8, trigger: '报名', reply: '已记录：{args}', description: '活动报名', scope: 'all',
    groupIds: [], minRole: 'member', enabled: true, sortOrder: 2, builtin: false,
    kind: 'template', mode: 'kb',
  },
  {
    id: 9, trigger: '联网', reply: '用法：/联网 问题内容', description: '用实时资料回答', scope: 'all',
    groupIds: [], minRole: 'member', enabled: true, sortOrder: 3, builtin: false,
    kind: 'agent', mode: 'none',
  },
]

const VARIABLES = {
  variables: [
    { name: 'user', label: '提问者昵称', example: '示例用户', advanced: false },
    { name: 'group', label: '群名称', example: '示例玩家群', advanced: false },
    { name: 'args', label: '命令参数（全部）', example: '张三 18', advanced: false },
    { name: 'args.1', label: '第 1 个参数', example: '张三', advanced: false },
    { name: 'args.2', label: '第 2 个参数', example: '18', advanced: false },
  ],
  preview: {},
  allowUserIds: false,
}

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
    if (p === '/commands/variables') return json(route, VARIABLES)
    if (p === '/commands/stats') return json(route, { topUsed: [], unmatched: [], total: COMMANDS.length })
    if (p === '/commands/preview') {
      const b = JSON.parse(req.postData() || '{}')
      previewBodies.push(b)
      // 迷你渲染器：只实现 {args} / {args.1}，验证"示例参数真的传到了渲染这一层"
      const args = String(b.args || '')
      const rendered = String(b.template || '')
        .replace(/\{args\}/g, args)
        .replace(/\{args\.1\}/g, args.split(/\s+/)[0] || '')
      return json(route, { rendered, length: rendered.length })
    }
    if (p === '/commands' && req.method() === 'GET') {
      return json(route, {
        available: true, commands: COMMANDS,
        requireMention: true, requireSlash: true, rateLimitPerMinute: 10, allowUserIds: false,
      })
    }
    if (p === '/commands' && req.method() === 'POST') return json(route, { ok: true, id: 99 })
    if (p === '/commands/toggle' || p === '/commands/delete') return json(route, { ok: true })

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

/** 点某一行（按触发词找）里的某个按钮 */
const clickRowButton = (page, trigger, label) => page.evaluate(({ trigger, label }) => {
  const rows = [...document.querySelectorAll('table.tbl tbody tr')]
  const row = rows.find((r) => {
    const t = r.querySelector('td.trig')
    return t && t.textContent.trim().startsWith('/' + trigger)
  })
  if (!row) return false
  const btn = [...row.querySelectorAll('button')].find((b) => b.textContent.trim() === label)
  if (!btn) return false
  btn.click()
  return true
}, { trigger, label })

/** 表单快照 */
const formSnap = (page) => page.evaluate(() => {
  const selects = [...document.querySelectorAll('select.inp')]
  const find = (optText) => selects.find((s) => [...s.options].some((o) => o.textContent.includes(optText)))
  const kindEl = find('智能问答')
  const modeEl = find('不用知识库')
  const chips = [...document.querySelectorAll('.vchip')].map((b) => b.textContent.replace(/\s+/g, ''))
  const rows = [...document.querySelectorAll('table.tbl tbody tr')].map((r) => ({
    trig: ((r.querySelector('td.trig') || {}).textContent || '').trim(),
    tags: [...r.querySelectorAll('.tag')].map((t) => t.textContent.trim()),
  }))
  const ta = document.querySelector('textarea')
  return {
    selects: selects.length,
    kindValue: kindEl ? kindEl.value : null,
    kindOptions: kindEl ? [...kindEl.options].map((o) => o.textContent.trim()) : [],
    hasMode: !!modeEl,
    modeValue: modeEl ? modeEl.value : null,
    chips,
    rows,
    replyValue: ta ? ta.value : null,
    preview: ((document.querySelector('.preview') || {}).textContent || '').trim(),
  }
})

// ==================== 主流程 ====================
async function main() {
  fs.mkdirSync(SHOT_DIR, { recursive: true })
  const browser = await connectCDP()
  // 隔离 context + 新页：不碰宿主正在用的标签页
  const ctx = await browser.newContext({ viewport: WIDE })
  const page = await ctx.newPage()
  const errors = []
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
  page.on('pageerror', (e) => errors.push(String(e)))
  await installDepShim(page, { fetchBase: SHIM_BASE })
  await installMocks(page)

  console.log('==> 打开 ' + BASE + '/admin/bot?tab=commands')
  await page.goto(BASE + '/admin/bot?tab=commands', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('table.tbl tbody tr', { timeout: 20000 })
  await page.waitForTimeout(600)

  // ---------- ① 列表：agent 显示成 /联网 <问题> + 标签 ----------
  let s = await formSnap(page)
  const net = s.rows.find((r) => r.trig.startsWith('/联网'))
  const signup = s.rows.find((r) => r.trig.startsWith('/报名'))
  check('① 列表里智能问答指令显示成「/联网 <问题>」并带「问答·…」标签',
    !!net && net.trig === '/联网 <问题>' && net.tags.some((t) => t.includes('问答')),
    '联网行 =「' + (net && net.trig) + '」，标签=[' + (net ? net.tags.join(' / ') : '') + ']')

  check('①b 话术指令不带「问答」标签，也不会被写成 /报名 <问题>',
    !!signup && signup.trig === '/报名' && !signup.tags.some((t) => t.includes('问答')),
    '报名行 =「' + (signup && signup.trig) + '」，标签=[' + (signup ? signup.tags.join(' / ') : '') + ']')

  // ---------- ② 编辑话术指令：没有「回答模式」 ----------
  const editSignup = await clickRowButton(page, '报名', '编辑')
  await page.waitForTimeout(400)
  s = await formSnap(page)
  check('② 编辑话术指令：类型=话术，且**没有**「回答模式」下拉',
    editSignup && s.kindValue === 'template' && !s.hasMode,
    '类型 = ' + s.kindValue + '，选项=' + JSON.stringify(s.kindOptions) + '，有模式下拉 = ' + s.hasMode)

  // ---------- ③ 编辑 agent 指令：出现「回答模式」，值正确 ----------
  const editNet = await clickRowButton(page, '联网', '编辑')
  await page.waitForTimeout(400)
  s = await formSnap(page)
  check('③ 编辑智能问答指令：类型=agent，且出现「回答模式」下拉（值=不用知识库）',
    editNet && s.kindValue === 'agent' && s.hasMode && s.modeValue === 'none',
    '类型 = ' + s.kindValue + '，回答模式 = ' + s.modeValue)

  // ---------- ④ 变量胶囊 ----------
  check('④ 可用变量里有 {args} / {args.1} / {args.2}',
    s.chips.includes('{args}') && s.chips.includes('{args.1}') && s.chips.includes('{args.2}'),
    '变量胶囊 = ' + s.chips.join(' '))

  // ---------- ⑤ 点胶囊插入 + 示例参数随预览发出 ----------
  const beforeReply = s.replyValue || ''
  await page.evaluate(() => {
    const b = [...document.querySelectorAll('.vchip')].find(
      (x) => x.textContent.replace(/\s+/g, '') === '{args}')
    if (b) b.click()
  })
  await page.waitForTimeout(300)
  const afterInsert = await page.evaluate(() => {
    const ta = document.querySelector('textarea')
    return ta ? ta.value : ''
  })
  // 填示例参数 → 预览应该把 {args} 替换掉
  await page.evaluate(() => {
    const inputs = [...document.querySelectorAll('input.inp, input')]
    const el = inputs.find((i) => (i.getAttribute('aria-label') || '') === '示例参数')
    if (!el) return false
    el.value = '张三 18'
    el.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })
  await page.waitForTimeout(600)
  s = await formSnap(page)
  const previewBody = previewBodies[previewBodies.length - 1] || {}
  check('⑤ 点变量胶囊会插进模板，示例参数会随预览请求发出去',
    afterInsert.includes('{args}') && previewBody.args === '张三 18',
    '插入后模板含 {args} = ' + afterInsert.includes('{args}') + '；'
    + '预览请求 body = ' + JSON.stringify({ template: previewBody.template, args: previewBody.args }))

  check('⑥ 预览真的把 {args} 换成了示例参数（UI → 接口 → 渲染 整条链路）',
    s.preview.includes('张三 18'),
    '预览显示 =「' + s.preview.replace(/\n/g, ' ⏎ ') + '」')

  await page.screenshot({ path: path.join(SHOT_DIR, 'commands-agent-edit.png'), fullPage: true })

  // ---------- ⑦ 新建指令默认是话术（不会误配成花钱的） ----------
  await page.evaluate(() => {
    const b = [...document.querySelectorAll('.page-bar button')].find(
      (x) => x.textContent.includes('新建指令'))
    if (b) b.click()
  })
  await page.waitForTimeout(500)
  s = await formSnap(page)
  check('⑦ 新建指令默认是「话术」（默认不花钱），要调模型得手动切',
    s.kindValue === 'template' && !s.hasMode,
    '新建时类型 = ' + s.kindValue + '，有模式下拉 = ' + s.hasMode)

  check('⑧ 全程 0 console error', errors.length === 0,
    errors.length ? errors.slice(0, 3).join(' | ') : '干净')

  const failed = results.filter((r) => !r.pass)
  console.log('\n===== ' + (results.length - failed.length) + '/' + results.length + ' 通过 =====')
  for (const f of failed) console.log('  ❌ ' + f.name)
  console.log('截图：' + SHOT_DIR)
  await ctx.close().catch(() => {})
  // ⚠️ 绝不能 browser.close()：对 CDP 连接的浏览器，那会连宿主正在用的 Chrome 一起关掉
  process.exit(failed.length ? 1 : 0)
}

main().catch((e) => { console.error('验收脚本异常：' + (e && e.stack || e)); process.exit(2) })
