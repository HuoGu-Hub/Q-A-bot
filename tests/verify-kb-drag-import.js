/**
 * 「知识库 → 文档」拖拽批量导入的真机验收脚本。
 *
 * 验两件事，都是这次新加的：
 *   ① 拖进来的**多份文件**：逐份预览成一张表 → 一次全部导入 → 结果以实际为准
 *   ② 拖动**整个文件夹**：递归展开（含 >100 个文件的 readEntries 分批陷阱）
 *
 * ② 没法用合成事件真拖一个目录，所以**把 DataTransferItem.prototype.webkitGetAsEntry
 * 换成假的目录树**再触发 drop —— 这样走的是真代码路径（递归 + 分批读取），
 * 只是数据源是造的。
 *
 * 宿主后端在跑，所以这里用的是**真接口**：会真的写库，因此脚本最后会把自己造的
 * 测试块删掉（docId 都带 zz-dragtest 前缀）。
 *
 * 用法：node tests/verify-kb-drag-import.js
 */
const fs = require('node:fs')
const path = require('node:path')
const { connectCDP } = require('/root/.playwright/cdp')

const ROOT = path.resolve(__dirname, '..')
const BASE = process.env.BASE || 'http://localhost:8080'
const SHOT_DIR = path.join(ROOT, 'tests/screenshots/kb-drag')
const WIDE = { width: 1440, height: 1000 }

/** 从 .env 读管理员密码 —— 只放内存里用，**绝不打印、绝不写进任何文件** */
function adminPassword() {
  const env = fs.readFileSync(path.join(ROOT, '.env'), 'utf8')
  for (const line of env.split(/\r?\n/)) {
    const m = line.match(/^\s*ADMIN_PASSWORD\s*=\s*(.*)$/)
    if (m) return m[1].trim().replace(/^["']|["']$/g, '')
  }
  return ''
}

const DOC_A = [
  '<!-- doc',
  'name: zz-dragtest-a',
  'url: https://example.invalid/drag-a',
  'tags: 测试',
  '-->',
  '',
  '=== 拖动测试甲 === <!-- id: zz-dragtest-a-0 -->',
  '这是拖动导入的测试块甲。',
].join('\n')

const DOC_BAD = [
  '=== 坏块 === <!-- id: 中文id -->',
  'id 非法，预览应当报错。',
].join('\n')

const DOC_C = [
  '<!-- doc',
  'name: zz-dragtest-c',
  'url: https://example.invalid/drag-c',
  '-->',
  '',
  '=== 拖动测试丙 === <!-- id: zz-dragtest-c-0 -->',
  '这是从"假目录"里读出来的测试块丙。',
].join('\n')

const results = []
function check(name, pass, detail) {
  results.push({ name, pass })
  console.log((pass ? '✅' : '❌') + ' ' + name + (detail ? '\n      ' + detail : ''))
}

/** 合成一次拖放：把 File 直接塞进 DataTransfer.files（走 dt.files 兜底那条路） */
function dropFiles(page, files) {
  return page.evaluate((payload) => {
    const dt = new DataTransfer()
    for (const f of payload) {
      dt.items.add(new File([f.text], f.name, { type: 'text/markdown' }))
    }
    const el = document.querySelector('.drop')
    el.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt }))
    return true
  }, files)
}

/** 合成一次"拖文件夹"：把 webkitGetAsEntry 换成假的目录树再 drop */
function dropFakeDir(page, dir, files) {
  return page.evaluate(({ dir, files }) => {
    const items = files.map((f) => ({ ...f }))
    // 造一棵会被分批读出的目录树 —— 模仿 Chrome 的 readEntries 一次只给一部分
    function makeDirNode(name, children) {
      return {
        isDirectory: true, isFile: false, name,
        createReader() {
          let at = 0
          return {
            readEntries(cb) {
              if (at >= children.length) return cb([])   // 给空数组 = 读完了
              const part = children.slice(at, at + 1)    // 一次只给 1 个，强制外层循环
              at += 1
              cb(part)
            },
          }
        },
      }
    }
    function makeFileNode(name, text) {
      return { isDirectory: false, isFile: true, name, file: (cb) => cb(new File([text], name)) }
    }
    const tree = makeDirNode(dir, items.map((f) =>
      makeFileNode(f.name, f.text)))

    const dt = new DataTransfer()
    dt.items.add(new File(['x'], 'placeholder.md'))
    Object.defineProperty(dt, 'files', { value: [] })
    // 让 items[0] 在被问到时返回整棵树
    const it = dt.items[0]
    Object.defineProperty(it, 'webkitGetAsEntry', { value: () => tree })
    // 多一个 item 走目录（真实拖拽里每个条目一个）
    const el = document.querySelector('.drop')
    el.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt }))
    return true
  }, { dir, files })
}

const snap = (page) => page.evaluate(() => {
  const rows = [...document.querySelectorAll('.bt tbody tr')].map((tr) => {
    const td = [...tr.querySelectorAll('td')]
    return {
      checked: td[0] ? td[0].querySelector('input')?.checked : false,
      name: td[1] ? td[1].innerText.trim() : '',
      added: td[2] ? td[2].innerText.trim() : '',
      updated: td[3] ? td[3].innerText.trim() : '',
      result: td[5] ? td[5].innerText.replace(/\s+/g, ' ').trim() : '',
    }
  })
  return {
    dropText: (document.querySelector('.drop') || {}).innerText || '',
    hasBatch: !!document.querySelector('.batch'),
    head: (document.querySelector('.batch-head') || {}).innerText?.replace(/\s+/g, ' ').trim() || '',
    progress: (document.querySelector('.batch-progress') || {}).innerText || '',
    rows,
    docNames: [...document.querySelectorAll('.docname')].map((b) => b.innerText.trim()),
    bodyText: (document.body.innerText || '').replace(/\s+/g, ' '),
  }
})

async function post(page, url, body) {
  return page.evaluate(async ({ url, body }) => {
    const r = await fetch('/admin/api' + url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    return { status: r.status, json: await r.json().catch(() => null) }
  }, { url, body })
}

async function main() {
  fs.mkdirSync(SHOT_DIR, { recursive: true })
  const browser = await connectCDP()
  const ctx = await browser.newContext({ viewport: WIDE })
  const page = await ctx.newPage()
  const errors = []
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
  page.on('pageerror', (e) => errors.push(String(e)))

  console.log('==> ' + BASE + '/admin/kb')
  await page.goto(BASE + '/admin/kb', { waitUntil: 'domcontentloaded' })

  // 登录（cookie 设在本 context 里，不影响用户自己的会话）
  const pw = adminPassword()
  if (!pw) throw new Error('.env 里没有 ADMIN_PASSWORD')
  const login = await post(page, '/login', { password: pw })
  console.log('    登录: HTTP ' + login.status)
  await page.goto(BASE + '/admin/kb', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.drop', { timeout: 20000 })

  let s = await snap(page)
  check('① 拖拽区在页面上，并说明支持文件夹', 
    s.dropText.includes('.md') && s.dropText.includes('整个文件夹'),
    '「' + s.dropText.replace(/\s+/g, ' ').slice(0, 60) + '…」')

  // ---------- ② 拖两份文件：一好一坏 ----------
  await dropFiles(page, [
    { name: 'a-valid.md', text: DOC_A },
    { name: 'b-bad.md', text: DOC_BAD },
  ])
  await page.waitForFunction(() => document.querySelectorAll('.bt tbody tr').length >= 2, { timeout: 30000 })
  await page.waitForTimeout(800)
  s = await snap(page)

  const rowA = s.rows.find((r) => r.name === 'a-valid.md') || {}
  const rowB = s.rows.find((r) => r.name === 'b-bad.md') || {}
  check('② 拖两份文件 → 逐份预览成一张表（好的一行有数字，坏的一行报错）',
    s.hasBatch && s.rows.length === 2
    && rowA.added === '1' && rowA.result.includes('就绪')
    && rowB.result.includes('id 只能由英文字母'),
    '甲: 新增=' + rowA.added + ' 结果=「' + rowA.result + '」\n      乙: 结果=「' + rowB.result.slice(0, 50) + '」')

  check('③ 坏文件被自动排除出待导入（勾选框禁用 + 计数只说 1 份）',
    rowB.checked === false && s.head.includes('1 / 2'),
    '表头:「' + s.head + '」')

  await page.screenshot({ path: path.join(SHOT_DIR, 'batch-preview.png'), fullPage: true })

  // ---------- ④ 拖"文件夹"：递归 + 分批读取 ----------
  await dropFakeDir(page, 'sub', [
    { name: 'c.md', text: DOC_C },
    { name: 'pic.png', text: '不是文档，应被忽略' },
  ])
  await page.waitForFunction(() => document.querySelectorAll('.bt tbody tr').length >= 3, { timeout: 30000 })
  await page.waitForTimeout(800)
  s = await snap(page)
  const rowC = s.rows.find((r) => r.name === 'sub/c.md') || {}

  check('④ 拖文件夹 → 递归展开出「sub/c.md」，非文档(.png)被忽略',
    s.rows.length === 3 && !!rowC.name && rowC.added === '1',
    '表里的文件: [' + s.rows.map((r) => r.name).join(', ') + ']')

  // ---------- ⑤ 一次全部导入 ----------
  const imported = s.rows.filter((r) => r.checked && r.result.includes('就绪')).length
  await page.evaluate(() => {
    const btns = [...document.querySelectorAll('.batch-head button')]
    const hit = btns.find((b) => (b.innerText || '').includes('导入选中的'))
    hit.click()
  })
  await page.waitForFunction(
    () => [...document.querySelectorAll('.bt tbody tr')].every((tr) => tr.innerText.includes('已导入')),
    { timeout: 40000 })
  await page.waitForTimeout(1200)
  s = await snap(page)

  check('⑤ 一次导入全部勾选的份，结果是"实际发生的事"',
    s.rows.filter((r) => r.result.includes('已导入')).length === imported
    && s.bodyText.includes('批量导入完成'),
    '导入 ' + imported + ' 份；提示 =「' + (s.bodyText.match(/批量导入完成.{0,20}/) || [''])[0] + '」')

  check('⑥ 导入之后，"文档"列表里出现了这两份文档',
    s.docNames.includes('zz-dragtest-a') && s.docNames.includes('zz-dragtest-c'),
    '文档列表: [' + s.docNames.join(', ') + ']')

  await page.screenshot({ path: path.join(SHOT_DIR, 'batch-done.png'), fullPage: true })

  // ---------- ⑦ 清理自己造的测试数据 ----------
  const delA = await post(page, '/kb/blocks/delete', { id: 'zz-dragtest-a-0' })
  const delC = await post(page, '/kb/blocks/delete', { id: 'zz-dragtest-c-0' })
  check('⑦ 清掉测试块（不留垃圾在真库里）',
    delA.status === 200 && delC.status === 200,
    'delete a=' + delA.status + ', c=' + delC.status)

  check('⑧ 全程 0 console error', errors.length === 0,
    errors.length ? errors.slice(0, 3).join(' | ') : '干净')

  const failed = results.filter((r) => !r.pass)
  console.log('\n===== ' + (results.length - failed.length) + '/' + results.length + ' 通过 =====')
  for (const f of failed) console.log('  ❌ ' + f.name)
  console.log('截图：' + SHOT_DIR)
  await ctx.close().catch(() => {})
  // ⚠️ 绝不 browser.close()：会把宿主正在用的 Chrome 一起关掉
  process.exit(failed.length ? 1 : 0)
}

main().catch((e) => { console.error('验收脚本异常：' + (e && e.stack || e)); process.exit(2) })
