/**
 * 「知识库 → 文档」面板的真机验收脚本。
 *
 * 这是新存储（SQLite 块表，**按块 id**）的管理端入口：
 *   ① 概览：块数 / 文档数 / 已下架 / 有向量
 *   ② 导入：**先预览**（会新增几条、覆盖几条、库里哪些没被碰）→ 确认再导
 *   ③ 文档列表 → 展开看块 → 改正文（会重算向量）/ 下架 / 真删除
 *
 * 宿主后端跑的还是没有这些接口的旧代码，所以一律走 mock（宿主 Vite dev 5173
 * 服务的正是本工作区源码）。浏览器、Vue、DOM、样式、事件都是真的，只有数据是假的。
 *
 * 用法：node tests/verify-kb-docs-panel.js
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
const SHOT_DIR = path.join(ROOT, 'tests/screenshots/kb-docs')
const WIDE = { width: 1440, height: 1000 }

const calls = []
const posts = []

const STATS = { available: true, blocks: 3, retired: 1, documents: 2, vectors: 3 }
const DOCS = [
  { docId: 'flame-altar', blocks: 2, retired: 1 },
  { docId: 'kiln', blocks: 1, retired: 0 },
]
const BLOCKS = {
  'flame-altar': [
    { id: 'flame-altar-0', docId: 'flame-altar', title: '灵火祭坛', text: '祭坛是复活点。',
      url: 'https://w/Flame_Altar', tags: ['基础'], source: 'doc', retired: false, updatedAt: '2026-09-29T10:00:00Z' },
    { id: 'flame-altar-1', docId: 'flame-altar', title: '升级材料', text: '需要 5 个瘴气木。',
      url: 'https://w/Flame_Altar', tags: ['基础'], source: 'doc', retired: true, updatedAt: '2026-09-29T10:00:00Z' },
  ],
  kiln: [
    { id: 'kiln-0', docId: 'kiln', title: '熔炉', text: '熔炉用来烧制。',
      url: 'https://w/Kiln', tags: ['制作'], source: 'doc', retired: false, updatedAt: '2026-09-29T10:00:00Z' },
  ],
}

const PREVIEW = {
  ok: true, added: 2, updated: 1, unchanged: 0, untouchedExisting: 3,
  addedIds: ['kiln-1', 'kiln-2'], updatedIds: ['flame-altar-0'],
  warnings: ['新增块「熔炉」的标题，和库里 id=kiln-0 的块完全相同 —— 请确认 id。'],
}
const IMPORT = { ok: true, added: 2, updated: 1, unchanged: 0, untouched: 3, warnings: [] }

function json(route, body) {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
}

async function installMocks(page) {
  await page.addInitScript(() => {
    window.__confirms = []
    window.confirm = (msg) => { window.__confirms.push(String(msg)); return true }
  })
  await page.route('**/admin/api/**', async (route) => {
    const req = route.request()
    const u = new URL(req.url())
    const p = u.pathname.startsWith('/admin/api') ? u.pathname.slice('/admin/api'.length) : u.pathname
    calls.push(p + u.search)
    if (req.method() === 'POST') {
      let b = null
      try { b = JSON.parse(req.postData() || 'null') } catch { b = null }
      posts.push({ path: p, body: b })
    }

    if (p === '/session') return json(route, { ok: true })
    if (p === '/kb/blocks/stats') return json(route, STATS)
    if (p === '/kb/blocks/docs') return json(route, { available: true, documents: DOCS })
    if (p === '/kb/blocks') {
      const doc = u.searchParams.get('doc') || ''
      return json(route, { doc, blocks: BLOCKS[doc] || [] })
    }
    if (p === '/kb/blocks/preview') return json(route, PREVIEW)
    if (p === '/kb/blocks/import') return json(route, IMPORT)
    if (p === '/kb/blocks/update') {
      return json(route, { ok: true, block: { ...BLOCKS['flame-altar'][0], text: '新正文' } })
    }
    if (p === '/kb/blocks/retire' || p === '/kb/blocks/delete'
      || p === '/kb/blocks/retire-doc' || p === '/kb/blocks/clear') {
      return json(route, { ok: true, affected: 1 })
    }
    console.log('   ⚠️ mock 未覆盖：' + p + u.search)
    return json(route, {})
  })
}

// ==================== 断言 ====================
const results = []
function check(name, pass, detail) {
  results.push({ name, pass })
  console.log((pass ? '✅' : '❌') + ' ' + name + (detail ? '\n      ' + detail : ''))
}

const clickText = (page, sel, text) => page.evaluate(({ sel, text }) => {
  const nodes = [...document.querySelectorAll(sel)]
  const hit = nodes.find((n) => (n.textContent || '').replace(/\s+/g, '').includes(text))
  if (!hit) return false
  hit.click()
  return true
}, { sel, text })

const snap = (page) => page.evaluate(() => {
  const txt = (s) => { const n = document.querySelector(s); return n ? (n.innerText || '').replace(/\s+/g, ' ').trim() : '' }
  return {
    activeTab: ((document.querySelector('.tabs button.active') || document.querySelector('.tabs .active') || {}).textContent || '').trim(),
    tabs: [...document.querySelectorAll('.tabs button, .tabs a')].map((t) => (t.textContent || '').trim()),
    bodyText: (document.body.innerText || '').replace(/\s+/g, ' ').trim(),
    hasTextarea: !!document.querySelector('textarea'),
    docNames: [...document.querySelectorAll('.docname')].map((b) => (b.textContent || '').trim()),
    blockIds: [...document.querySelectorAll('.blk .id')].map((b) => (b.textContent || '').trim()),
    confirms: window.__confirms || [],
  }
})

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

  console.log('==> 打开 ' + BASE + '/admin/kb')
  await page.goto(BASE + '/admin/kb', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.docname', { timeout: 20000 })
  await page.waitForTimeout(600)

  let s = await snap(page)

  // ---------- ① 默认落在「文档」tab ----------
  check('① 知识库页默认落在「文档」tab（导入是新模型的主要入口）',
    s.tabs.includes('文档') && s.activeTab.includes('文档'),
    'tab = [' + s.tabs.join(' / ') + ']，当前 =「' + s.activeTab + '」')

  // ---------- ② 概览 ----------
  check('② 概览显示块数 / 文档数 / 已下架 / 有向量',
    s.bodyText.includes('3 块') && s.bodyText.includes('2 份文档')
    && s.bodyText.includes('1 已下架') && s.bodyText.includes('3 有条向量'),
    '页面文本片段：「' + (s.bodyText.match(/块存储.{0,60}/) || [''])[0] + '」')

  // ---------- ③ 预览（只算不改）----------
  await page.evaluate(() => {
    const ta = document.querySelector('textarea')
    ta.value = '=== 熔炉 === <!-- id: kiln-1 -->\n正文'
    ta.dispatchEvent(new Event('input', { bubbles: true }))
  })
  await page.waitForTimeout(200)
  const clicked = await clickText(page, 'button', '预览会改什么')
  await page.waitForTimeout(600)
  s = await snap(page)
  const previewCall = calls.filter((c) => c.includes('/kb/blocks/preview')).length
  const importCallBefore = calls.filter((c) => c.includes('/kb/blocks/import')).length

  check('③ 「预览会改什么」：调的是 preview（不是 import），并把"会改多少条"显示出来',
    clicked && previewCall === 1 && importCallBefore === 0
    && s.bodyText.includes('新增 2') && s.bodyText.includes('覆盖 1')
    && s.bodyText.includes('无变化 0') && s.bodyText.includes('库里未被触及 3'),
    'preview 调用 ' + previewCall + ' 次，import ' + importCallBefore + ' 次；'
    + '摘要「' + (s.bodyText.match(/新增 2.{0,50}/) || [''])[0] + '」')

  check('④ 预览里的提醒会显示出来（id 写变会静默变重复块，必须让人看见）',
    s.bodyText.includes('请确认 id'),
    '提醒文案命中 = ' + s.bodyText.includes('请确认 id'))

  await page.screenshot({ path: path.join(SHOT_DIR, 'docs-preview.png'), fullPage: true })

  // ---------- ⑤ 确认导入 ----------
  await clickText(page, 'button', '确认导入')
  await page.waitForTimeout(800)
  s = await snap(page)
  const importBody = (posts.find((p) => p.path === '/kb/blocks/import') || {}).body

  check('⑤ 「确认导入」：把文档全文 POST 给 import，并回报结果',
    !!importBody && String(importBody.text).includes('kiln-1')
    && s.bodyText.includes('导入完成'),
    'POST body.text 长度 = ' + (importBody ? String(importBody.text).length : 'null')
    + '；提示 =「' + (s.bodyText.match(/导入完成.{0,60}/) || [''])[0] + '」')

  // ---------- ⑥ 文档列表 + 展开块 ----------
  check('⑥ 文档列表列出每份文档的块数',
    s.docNames.join('/') === 'flame-altar/kiln',
    '文档 = [' + s.docNames.join(' / ') + ']')

  await clickText(page, '.docname', 'flame-altar')
  await page.waitForTimeout(600)
  s = await snap(page)
  check('⑦ 展开某文档 → 列出它的块（含已下架的那一块）',
    s.blockIds.join('/') === 'flame-altar-0/flame-altar-1',
    '块 = [' + s.blockIds.join(' / ') + ']')

  // ---------- ⑦ 改正文 ----------
  await clickText(page, 'button', '编辑')
  await page.waitForTimeout(400)
  const editOpened = await page.evaluate(() => !!document.querySelector('.edit-cell textarea'))
  await page.evaluate(() => {
    const ta = document.querySelector('.edit-cell textarea')
    ta.value = '被管理端改过的正文'
    ta.dispatchEvent(new Event('input', { bubbles: true }))
  })
  await clickText(page, '.edit-cell button', '保存')
  await page.waitForTimeout(700)
  const updateBody = (posts.find((p) => p.path === '/kb/blocks/update') || {}).body

  check('⑧ 改正文：POST update 带上 id 与旧正文之外的新文本（后端会重算向量）',
    editOpened && !!updateBody && updateBody.id === 'flame-altar-0'
    && updateBody.text === '被管理端改过的正文',
    'POST body = ' + JSON.stringify(updateBody))

  // ---------- ⑧ 下架 / 删除 ----------
  await clickText(page, 'button', '下架')
  await page.waitForTimeout(600)
  const retireBody = (posts.find((p) => p.path === '/kb/blocks/retire') || {}).body
  check('⑨ 「下架」：POST retire 带 id 与 retired 标记',
    !!retireBody && retireBody.id === 'flame-altar-0' && retireBody.retired === true,
    'POST body = ' + JSON.stringify(retireBody))

  await clickText(page, 'button', '删除')
  await page.waitForTimeout(600)
  s = await snap(page)
  const delBody = (posts.find((p) => p.path === '/kb/blocks/delete') || {}).body
  check('⑩ 「删除」是物理删除，且**先弹确认**（不能一点就没）',
    !!delBody && delBody.id === 'flame-altar-0'
    && s.confirms.some((c) => c.includes('物理删除') && c.includes('不能恢复')),
    'confirm =「' + (s.confirms[s.confirms.length - 1] || '').replace(/\n+/g, ' ⏎ ') + '」')

  // ---------- ⑨ 清空要二次确认 ----------
  await clickText(page, 'button', '清空全部块')
  await page.waitForTimeout(500)
  s = await snap(page)
  check('⑪ 「清空全部块」也要二次确认，并写明不可逆',
    s.confirms.some((c) => c.includes('不可逆')) && calls.some((c) => c.includes('/kb/blocks/clear')),
    'confirm 命中「不可逆」= ' + s.confirms.some((c) => c.includes('不可逆')))

  await page.screenshot({ path: path.join(SHOT_DIR, 'docs-blocks.png'), fullPage: true })

  check('⑫ 全程 0 console error', errors.length === 0,
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
