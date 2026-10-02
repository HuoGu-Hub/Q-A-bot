/**
 * 「知识库 → 词条」（T11）的真机验收脚本。
 *
 * 「关键词」与「术语表」两个旧面板合并成一个「词条」面板、后端从
 * 「chunks 投影 + TSV 文件」收成 kb_term 单表之后，这个脚本验的就是
 * 「合并后的这一个面板真的把两边的活都干了」这件事，逐条给出可观察证据：
 *   ① 知识库页只剩 4 个二级目录（词条 / 分类 / 提案 / 资料），默认落在「词条」
 *   ② 列表一次渲染出三种行：有中文名的 / 没有中文名的（status:''）/ 没有正文的（chunkCount:0）
 *   ③ 视图预设是唯一筛选维度：切一下就带着 view= 参数重新取数
 *   ④ 核对模式（原逐条流水线）保留：卡片 + 快捷键说明 + 勾选 + 批量改状态
 *   ⑤ 点状态标签 → 三选一 → POST /kb/terms（一次 upsert 同时带 zh 与 status）
 *   ⑥ 改中文名（就地编辑）→ POST /kb/terms，body 只有 en/zh/status，**不再回填 page/category**
 *   ⑦ 清空中文名 → POST /kb/terms/clear
 *   ⑧ 抽屉 = 列表 + 抽屉：名字区 / 板块区 / 正文区
 *   ⑨ 块级下架恢复 → POST /kb/terms/chunk/retire；保存这一块 → /kb/terms/chunk；追加 → /kb/terms/chunk/add
 *   ⑩ 整条下架恢复 → POST /kb/terms/retire（确认框写明影响几个块）
 *   ⑪ 改板块走「原始分类 → 大类」→ POST /kb/categories/set，界面写明影响面
 *   ⑫ 批量改状态 → POST /kb/terms/batch
 *   ⑬ Excel 旁路导出 → GET /kb/terms/export
 *   ⑭ 提案（接口没变）：先改再采纳；「删除」用拒绝表达
 *   ⑮ 加载的是本工作区的新面板；全程 0 console error
 *
 * 宿主后端 8080 上跑的是改造前的旧代码，所以一律走 mock：用宿主 Vite dev
 * （5173，服务的正是本工作区源码）把 /admin/api/** 全拦下来喂假数据。
 * 浏览器、Vue、DOM、样式、事件都是真的，只有数据是假的。
 *
 * ⚠️ mock 里 GET /kb/terms/chunks 返回的是 {title, chunks:[...]} —— 这是前端
 * （@shared/api/types 的 KbTermChunksResponse）与本次任务说明约定的形状。
 * 工作区里 KbTermController#chunks 目前仍直接返回裸数组，两者对不上；
 * 本脚本按**前端契约**喂数据（要验的是前端行为），后端那处不一致见回报。
 *
 * 用法：
 *   node tests/verify-redesign-t11.js
 *   BASE=http://<宿主IP>:5173 node tests/verify-redesign-t11.js
 */
const fs = require('node:fs')
const path = require('node:path')
const { execSync } = require('node:child_process')
const { connectCDP } = require('/root/.playwright/cdp')
const { installDepShim } = require('./lib/dep-shim.cjs')

const ROOT = path.resolve(__dirname, '..')
/** 容器里没有 Vite：能打开宿主 5173 的是宿主网关 IP（见 AGENTS.md） */
const HOST_IP = (() => {
  try {
    return execSync("getent ahostsv4 host.docker.internal | awk '{print $1}' | head -n1", { encoding: 'utf8' }).trim()
  } catch { return '' }
})()
/**
 * BASE 是给**宿主浏览器**打开用的 —— 网关 IP 是容器视角的地址，宿主用它会
 * ERR_CONNECTION_TIMED_OUT（实测）。所以 BASE 里出现网关 IP 时一律换回 localhost；
 * 垫片要自己取源码时再用回网关 IP（见下面 SHIM_BASE）。
 */
const RAW_BASE = process.env.BASE || 'http://localhost:5173'
const BASE = HOST_IP ? RAW_BASE.split(HOST_IP).join('localhost') : RAW_BASE
const SHOT_DIR = path.join(ROOT, 'tests/screenshots/redesign')
const WIDE = { width: 1440, height: 900 }

/** 接口调用记录：用来证明「点一下真的发了什么请求、body 是什么」 */
const calls = []
const postBodies = []
const callCount = (frag) => calls.filter((u) => u.includes(frag)).length
const bodiesOf = (frag) => postBodies.filter((b) => b.url.includes(frag)).map((b) => b.body)

function json(route, body) {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
}

// ==================== 假数据（可变：写完再读真的会看到新值）====================

const BOARDS = [
  { key: 'crafting', label: '制作', icon: '⚒', desc: '制作材料与工坊' },
  { key: 'combat', label: '战斗', icon: '⚔', desc: '战斗与敌人' },
  { key: 'system', label: '系统', icon: '⚙', desc: '玩法机制' },
  { key: 'other', label: '其他', icon: '📦', desc: '还没归类的' },
]

/**
 * 五条词条刻意覆盖三种行：
 *   Scrap Cup / Flame Altar —— 有中文名、有正文（Flame Altar 还带多别名）
 *   Ancient Vault          —— 有正文但没有中文名（status 是空串 ''）
 *   Equipment              —— 有中文名但**没有正文**（chunkCount 0）
 *   Weapon Subtype         —— 既没中文名也没正文：默认视图把它挡在外面
 */
const db = {
  terms: [
    { en: 'Scrap Cup', zh: '废料杯', status: 'draft', board: 'crafting', boardLabel: '⚒ 制作', cats: ['Crafting Materials'], chunkCount: 3, chars: 640, url: 'https://enshrouded.wiki.gg/wiki/Scrap_Cup', retired: false },
    { en: 'Flame Altar', zh: '灵火祭坛、火焰祭坛', status: 'verified', board: 'crafting', boardLabel: '⚒ 制作', cats: ['Crafting Materials'], chunkCount: 2, chars: 520, url: 'https://enshrouded.wiki.gg/wiki/Flame_Altar', retired: false },
    { en: 'Ancient Vault', zh: '', status: '', board: 'combat', boardLabel: '⚔ 战斗', cats: ['Points of Interest'], chunkCount: 1, chars: 9, url: 'https://enshrouded.wiki.gg/wiki/Ancient_Vault', retired: false },
    { en: 'Equipment', zh: '装备', status: 'draft', board: 'system', boardLabel: '⚙ 系统', cats: ['Gameplay'], chunkCount: 0, chars: 0, url: 'https://enshrouded.wiki.gg/wiki/Equipment', retired: false },
    { en: 'Weapon Subtype', zh: '', status: '', board: 'other', boardLabel: '📦 其他', cats: [], chunkCount: 0, chars: 0, url: '', retired: false },
  ],
  chunks: {
    'Scrap Cup': [
      { i: 11, text: '废料杯由废料合成。', retired: false },
      { i: 12, text: '需要 5 个废料。', retired: false },
      { i: 13, text: '用于制作熔炉。', retired: false },
    ],
    'Flame Altar': [
      { i: 30, text: '灵火祭坛是复活点。', retired: false },
      { i: 31, text: '可以在祭坛升级。', retired: false },
    ],
    'Ancient Vault': [{ i: 50, text: '古代宝库里有宝箱。', retired: false }],
    Equipment: [],
    'Weapon Subtype': [],
  },
  nextChunk: 60,
}

const findTerm = (en) => db.terms.find((t) => t.en === en)
const aliasesOf = (zh) => (zh || '').split(/[、/｜|]/).map((s) => s.trim()).filter(Boolean)

function computeCounts() {
  const all = db.terms.length
  let withChunks = 0, unnamed = 0, main = 0, draft = 0, verified = 0, rejected = 0
  for (const t of db.terms) {
    if (t.chunkCount > 0) withChunks++
    if (!t.zh) unnamed++
    if (t.chunkCount > 0 || t.zh) main++
    if (t.status === 'draft') draft++
    else if (t.status === 'verified') verified++
    else if (t.status === 'rejected') rejected++
  }
  return { all, main, withChunks, noChunk: all - withChunks, draft, verified, rejected, unnamed }
}

function computeBoards() {
  return BOARDS.map((b) => ({
    ...b,
    terms: db.terms.filter((t) => t.board === b.key).length,
    chunks: db.terms.filter((t) => t.board === b.key).reduce((s, t) => s + t.chunkCount, 0),
  }))
}

/** 与后端 KbTermService.matchesView 对齐 */
function matchesView(t, view) {
  switch (view) {
    case 'all': return true
    case 'main': return t.chunkCount > 0 || !!t.zh
    case 'unnamed': return !t.zh
    case 'nochunk': return t.chunkCount === 0
    case 'draft':
    case 'verified':
    case 'rejected': return t.status === view
    default: return true
  }
}

const viewOf = (t) => ({
  en: t.en, zh: t.zh, aliases: aliasesOf(t.zh), status: t.status,
  board: t.board, boardLabel: t.boardLabel, cats: t.cats,
  chunkCount: t.chunkCount, chars: t.chars, url: t.url, retired: t.retired,
})

function pageOf(u) {
  const q = (u.searchParams.get('q') || '').trim().toLowerCase()
  const view = (u.searchParams.get('view') || 'main').toLowerCase()
  const board = u.searchParams.get('board') || ''
  const limit = Number(u.searchParams.get('limit') || 100)
  const offset = Number(u.searchParams.get('offset') || 0)
  const items = db.terms
    .filter((t) => matchesView(t, view))
    .filter((t) => !board || t.board === board)
    .filter((t) => !q
      || t.en.toLowerCase().includes(q)
      || (t.zh || '').toLowerCase().includes(q)
      || aliasesOf(t.zh).some((a) => a.toLowerCase().includes(q)))
    .sort((a, b) => a.en.localeCompare(b.en))
  return {
    total: items.length,
    items: items.slice(offset, offset + limit).map(viewOf),
    counts: computeCounts(),
    boards: computeBoards(),
    available: true,
  }
}

const proposals = [
  {
    id: 7, createdAt: '2026-09-26T10:00:00', status: 'pending', kind: 'overwrite',
    title: 'Scrap Cup', question: '废料杯怎么做？',
    currentText: '废料杯由废料合成。',
    proposedText: '废料杯由 5 个废料在熔炉合成。',
    reason: '原文本缺数量', sourceStatId: 88, reviewedAt: null, reviewedBy: null,
  },
  {
    id: 8, createdAt: '2026-09-26T11:00:00', status: 'pending', kind: 'new',
    title: 'Ember Vault', question: '灰烬宝库在哪？',
    currentText: '', proposedText: '灰烬宝库位于灰烬谷北侧。',
    reason: '知识库里没有这个词条', sourceStatId: 89, reviewedAt: null, reviewedBy: null,
  },
]

async function installMocks(page) {
  await page.addInitScript(() => {
    // window.confirm 的文案要能抓下来 —— 影响面必须写在确认框里
    window.__confirms = []
    window.confirm = (msg) => { window.__confirms.push(String(msg)); return true }
    // 导出会造 Blob + 点一个 download 链接：把这两步拦成可观察的计数，
    // 免得真往宿主机下载目录里写文件
    window.__downloads = []
    const origCreate = URL.createObjectURL
    URL.createObjectURL = (blob) => { window.__downloads.push('blob'); return 'blob:mock-download' }
    void origCreate
    const origClick = HTMLAnchorElement.prototype.click
    HTMLAnchorElement.prototype.click = function () {
      if (this.hasAttribute('download')) { window.__downloads.push('anchor:' + this.getAttribute('download')); return }
      return origClick.call(this)
    }
  })

  await page.route('**/admin/api/**', async (route) => {
    const req = route.request()
    const u = new URL(req.url())
    const p = u.pathname.startsWith('/admin/api') ? u.pathname.slice('/admin/api'.length) : u.pathname
    const method = req.method()
    calls.push(u.pathname + u.search)
    if (method === 'POST') {
      let body = null
      try { body = JSON.parse(req.postData() || 'null') } catch { body = req.postData() }
      postBodies.push({ url: u.pathname + u.search, body })
    }

    if (p === '/session') return json(route, { ok: true })

    // ---------- 词条层 ----------
    if (p === '/kb/terms' && method === 'GET') return json(route, pageOf(u))

    if (p === '/kb/terms/chunks' && method === 'GET') {
      const title = u.searchParams.get('title') || ''
      const chunks = (db.chunks[title] || []).map((c) => ({ i: c.i, text: c.text, url: '', chars: c.text.length, retired: c.retired }))
      return json(route, { title, chunks })
    }

    if (p === '/kb/terms' && method === 'POST') {
      const b = JSON.parse(req.postData() || '{}')
      let t = findTerm(b.en)
      if (!t) {
        t = { en: String(b.en || ''), zh: '', status: 'draft', board: 'other', boardLabel: '📦 其他', cats: [], chunkCount: 0, chars: 0, url: '', retired: false }
        db.terms.push(t)
      }
      t.zh = String(b.zh == null ? '' : b.zh)
      t.status = String(b.status == null ? (t.zh ? 'verified' : 'draft') : b.status)
      return json(route, { ok: true, size: db.terms.length })
    }

    if (p === '/kb/terms/batch' && method === 'POST') {
      const b = JSON.parse(req.postData() || '{}')
      const items = Array.isArray(b.items) ? b.items : []
      const status = String(b.status || '')
      let updated = 0, unchanged = 0
      const missing = []
      for (const en of items) {
        const t = findTerm(en)
        if (!t) { missing.push(en); continue }
        if (t.status === status) unchanged++
        else { t.status = status; updated++ }
      }
      // counts 是前端 KbTermBatchResponse 要的字段（真后端目前没返回，见回报）
      return json(route, { ok: true, updated, unchanged, missing, size: db.terms.length, counts: computeCounts() })
    }

    if (p === '/kb/terms/clear' && method === 'POST') {
      const b = JSON.parse(req.postData() || '{}')
      const t = findTerm(b.en)
      if (!t) return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: '没有这条词条' }) })
      t.zh = ''
      t.status = 'draft'
      return json(route, { ok: true, size: db.terms.length })
    }

    if (p === '/kb/terms/retire' && method === 'POST') {
      const b = JSON.parse(req.postData() || '{}')
      const retired = !!b.retired
      const t = findTerm(b.title)
      let n = 0
      if (t) {
        t.retired = retired
        for (const c of (db.chunks[t.en] || [])) { if (c.retired !== retired) n++; c.retired = retired }
      }
      return json(route, { ok: true, affected: n, message: (retired ? '已下架 ' : '已恢复 ') + n + ' 个文本块' })
    }

    if (p === '/kb/terms/chunk/retire' && method === 'POST') {
      const b = JSON.parse(req.postData() || '{}')
      const retired = !!b.retired
      for (const list of Object.values(db.chunks)) {
        const c = list.find((x) => x.i === b.i)
        if (c) {
          c.retired = retired
          return json(route, { ok: true, chunk: { i: c.i, text: c.text, url: '', chars: c.text.length, retired }, message: retired ? '已下架，检索不会再用到它' : '已恢复，检索会重新用到它' })
        }
      }
      return json(route, { ok: true, message: retired ? '已下架' : '已恢复' })
    }

    if (p === '/kb/terms/chunk/add' && method === 'POST') {
      const b = JSON.parse(req.postData() || '{}')
      const title = String(b.title || '')
      let t = findTerm(title)
      if (!t) {
        t = { en: title, zh: '', status: 'draft', board: 'other', boardLabel: '📦 其他', cats: [], chunkCount: 0, chars: 0, url: '', retired: false }
        db.terms.push(t)
      }
      const list = (db.chunks[title] = db.chunks[title] || [])
      const i = db.nextChunk++
      list.push({ i, text: String(b.text || ''), retired: false })
      t.chunkCount = list.length
      t.chars = list.reduce((s, c) => s + c.text.length, 0)
      return json(route, { ok: true, i, message: '已追加为新块（第 ' + i + ' 块），检索立刻能用' })
    }

    if (p === '/kb/terms/chunk' && method === 'POST') {
      const b = JSON.parse(req.postData() || '{}')
      for (const list of Object.values(db.chunks)) {
        const c = list.find((x) => x.i === b.i)
        if (c) {
          c.text = String(b.text == null ? '' : b.text)
          return json(route, { ok: true, chunk: { i: c.i, text: c.text, url: '', chars: c.text.length, retired: c.retired } })
        }
      }
      return json(route, { ok: true, chunk: { i: b.i, text: String(b.text || ''), url: '', chars: 0, retired: false } })
    }

    if (p === '/kb/terms/export' && method === 'GET') {
      const lines = ['# 中英术语对照表', '# 列：英文名\t中文名\twiki页面\t类别\t状态']
      for (const t of db.terms) lines.push([t.en, t.zh, t.en.replace(/ /g, '_'), t.board, t.status].join('\t'))
      return json(route, { ok: true, tsv: lines.join('\n'), count: db.terms.length })
    }

    if (p === '/kb/terms/import' && method === 'POST') {
      return json(route, { ok: true, created: 0, updated: 0, skipped: 0 })
    }

    // ---------- 分类 ----------
    if (p === '/kb/categories' && method === 'GET') {
      return json(route, {
        customCount: 0,
        groups: BOARDS.map((b) => ({ key: b.key, label: b.label, icon: b.icon })),
        stats: [],
        items: [
          { raw: 'Crafting Materials', groupKey: 'crafting', labelZh: '', custom: false, count: 22 },
          { raw: 'Points of Interest', groupKey: 'combat', labelZh: '', custom: false, count: 10 },
          { raw: 'Gameplay', groupKey: 'system', labelZh: '', custom: false, count: 6 },
        ],
      })
    }
    if (p === '/kb/categories/set' && method === 'POST') return json(route, { ok: true })

    // ---------- 提案（接口没变）----------
    if (p === '/kb/proposals/stats') {
      return json(route, {
        threshold: 5, pendingGood: 9, canAnalyze: true,
        pending: proposals.filter((x) => x.status === 'pending').length,
        approved: proposals.filter((x) => x.status === 'approved').length,
        rejected: proposals.filter((x) => x.status === 'rejected').length,
        available: true,
      })
    }
    if (p === '/kb/proposals' && method === 'GET') {
      const st = u.searchParams.get('status') || 'pending'
      return json(route, { status: st, rows: proposals.filter((x) => x.status === st) })
    }
    if (p === '/kb/proposals/analyze' && method === 'POST') {
      return json(route, { ok: true, created: 0, message: '没有可分析的新内容' })
    }
    if (p === '/kb/proposals/review' && method === 'POST') {
      const b = JSON.parse(req.postData() || '{}')
      const hit = proposals.find((x) => x.id === b.id)
      if (hit) hit.status = b.approve ? 'approved' : 'rejected'
      return json(route, {
        ok: true,
        message: b.approve ? '已覆盖词条「Scrap Cup」（只重算了这一块的向量）' : '已拒绝，知识库未改动',
        stats: {
          threshold: 5, pendingGood: 9, canAnalyze: true,
          pending: proposals.filter((x) => x.status === 'pending').length,
          approved: proposals.filter((x) => x.status === 'approved').length,
          rejected: proposals.filter((x) => x.status === 'rejected').length,
          available: true,
        },
      })
    }

    console.log('   ⚠️ mock 未覆盖：' + u.pathname + u.search)
    return json(route, {})
  })
}

// ==================== 断言与 DOM 工具 ====================

const results = []
function check(name, pass, detail) {
  results.push({ name, pass })
  console.log((pass ? '✅' : '❌') + ' ' + name + (detail ? '\n      ' + detail : ''))
}

/** 宿主窗口在后台时 Playwright 的 click 会因「不可见」超时，统一用 DOM click */
async function domClick(page, selector, containsText) {
  return page.evaluate(({ selector, containsText }) => {
    const nodes = [...document.querySelectorAll(selector)]
    const hit = containsText
      ? nodes.find((n) => (n.textContent || '').replace(/\s+/g, '').includes(containsText))
      : nodes[0]
    if (!hit) return false
    hit.click()
    return true
  }, { selector, containsText })
}

/** 只在「英文名 === en」的那一行里点东西 */
async function rowClick(page, en, selector, containsText) {
  return page.evaluate(({ en, selector, containsText }) => {
    const tr = [...document.querySelectorAll('table.tbl tbody tr.row')].find((r) => {
      const e = r.querySelector('td.t .en')
      return e && e.textContent.trim() === en
    })
    if (!tr) return false
    const nodes = [...tr.querySelectorAll(selector)]
    const hit = containsText
      ? nodes.find((n) => (n.textContent || '').replace(/\s+/g, '').includes(containsText))
      : nodes[0]
    if (!hit) return false
    hit.click()
    return true
  }, { en, selector, containsText })
}

/** 只读一行 */
const rowSnap = (page, en) => page.evaluate((en) => {
  const tr = [...document.querySelectorAll('table.tbl tbody tr.row')].find((r) => {
    const e = r.querySelector('td.t .en')
    return e && e.textContent.trim() === en
  })
  if (!tr) return null
  const zhBtn = tr.querySelector('td.zh button.zhv')
  return {
    en: (tr.querySelector('td.t .en') || {}).textContent || '',
    tags: [...tr.querySelectorAll('td.t .tag')].map((t) => (t.textContent || '').trim()),
    zh: zhBtn ? (zhBtn.innerText || '').replace(/\s+/g, ' ').trim() : '',
    zhEmpty: !!(zhBtn && zhBtn.classList.contains('empty')),
    status: zhBtn ? ((tr.querySelector('td.st button.trig') || {}).innerText || '').replace(/\s+/g, '').trim() : '',
    board: ((tr.querySelector('td.bd') || {}).innerText || '').trim(),
    chunks: ((tr.querySelector('td.num') || {}).innerText || '').trim(),
    editing: !!tr.querySelector('td.zh .edit'),
    menu: [...tr.querySelectorAll('td.st .menu button.opt')].map((b) => (b.textContent || '').trim()),
  }
}, en)

const rowEns = (page) => page.evaluate(() =>
  [...document.querySelectorAll('table.tbl tbody tr.row')].map((r) => {
    const e = r.querySelector('td.t .en')
    return e ? e.textContent.trim() : ''
  }))

/** 抽屉里第 n 个块的只读状态（n 从 0 起） */
const chunkSnap = (page, n) => page.evaluate((n) => {
  const c = [...document.querySelectorAll('.drawer .chunk')][n]
  if (!c) return null
  return {
    num: ((c.querySelector('.cnum') || {}).innerText || '').trim(),
    tag: ((c.querySelector('.tag') || {}).innerText || '').trim(),
    ops: [...c.querySelectorAll('.chead button')].map((b) => (b.textContent || '').trim()),
    opacity: getComputedStyle(c).opacity,
    text: (c.querySelector('textarea') || {}).value || '',
  }
}, n)

const drawerSnap = (page) => page.evaluate(() => {
  const d = document.querySelector('.drawer')
  if (!d) return null
  return {
    aria: d.getAttribute('aria-label') || '',
    en: ((d.querySelector('.dr-en') || {}).innerText || '').trim(),
    sections: [...d.querySelectorAll('.dr-h')].map((h) => (h.textContent || '').replace(/\s+/g, ' ').trim().split(' ')[0]),
    bodyText: (d.innerText || '').replace(/\s+/g, ' ').trim(),
    zhValue: (d.querySelector('.dr-sec input') || {}).value || '',
    statusOptions: [...d.querySelectorAll('.dr-status option')].map((o) => (o.textContent || '').trim()),
    chunkCount: d.querySelectorAll('.chunk').length,

    foot: [...d.querySelectorAll('.dr-foot button')].map((b) => (b.textContent || '').trim()),
  }
})

const setInput = (page, selector, value) => page.evaluate(({ selector, value }) => {
  const el = document.querySelector(selector)
  if (!el) return false
  el.value = value
  el.dispatchEvent(new Event('input', { bubbles: true }))
  return true
}, { selector, value })

/** 页面栏里那个 view 预设的 select */
const viewSelect = (page) => page.evaluate(() => {
  const s = document.querySelector('.page-bar .bar-actions select')
  if (!s) return null
  return {
    value: s.value,
    options: [...s.options].map((o) => ({ value: o.value, label: (o.textContent || '').trim() })),
  }
})

const pickView = (page, v) => page.evaluate((v) => {
  const s = document.querySelector('.page-bar .bar-actions select')
  if (!s) return false
  s.value = v
  s.dispatchEvent(new Event('change', { bubbles: true }))
  return true
}, v)

;(async () => {
  fs.mkdirSync(SHOT_DIR, { recursive: true })
  const browser = await connectCDP()
  const ctx = browser.contexts()[0]
  const page = await ctx.newPage()
  await page.setViewportSize(WIDE)
  await installMocks(page)
  // 环境垫片（宿主 Vite 依赖缓存自相矛盾 → 双份 Vue 响应式）：原因见 lib/dep-shim.cjs
  // 浏览器用 BASE（宿主 localhost），垫片自己去取源码时要用容器能到达的地址
  const SHIM_BASE = HOST_IP ? BASE.replace('//localhost', '//' + HOST_IP).replace('//127.0.0.1', '//' + HOST_IP) : BASE
  const shim = installDepShim(page, { fetchBase: SHIM_BASE })

  const errors = []
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text().slice(0, 240)) })
  page.on('pageerror', (e) => errors.push('pageerror: ' + String(e.message || e).slice(0, 240)))

  const cdp = await ctx.newCDPSession(page)
  await cdp.send('Network.enable')
  await cdp.send('Network.clearBrowserCache')

  console.log('BASE = ' + BASE + '\n')

  try {
    // ---------- ① 打开「词条」面板 ----------
    await page.goto(BASE + '/admin/kb?tab=terms', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(1800)
    const boot = await page.evaluate(() => ({
      path: location.pathname + location.search,
      tabs: [...document.querySelectorAll('.page-bar .tab')].map((t) => (t.textContent || '').trim()),
      active: (document.querySelector('.page-bar .tab.active') || {}).innerText || '',
      heads: [...document.querySelectorAll('table.tbl thead th')].map((t) => (t.textContent || '').trim()),
      btns: [...document.querySelectorAll('.page-bar .bar-actions button')].map((b) => (b.textContent || '').trim()),
      selects: document.querySelectorAll('.page-bar select').length,
      inputs: document.querySelectorAll('.page-bar input').length,
      pageBar: document.querySelectorAll('.page-bar').length,
      boards: [...document.querySelectorAll('.boards .board')].map((b) => (b.textContent || '').replace(/\s+/g, ' ').trim()),
      note: ((document.querySelector('.panel-wrap > p.note') || {}).innerText || '').replace(/\s+/g, ' ').trim(),
    }))
    check('① /admin/kb?tab=terms 打开的是「词条」面板，知识库只剩 4 个二级目录',
      boot.path.includes('/admin/kb') && boot.active.includes('词条') &&
      boot.tabs.join('/') === '词条/分类/提案/资料' &&
      boot.heads.join('/') === '词条（Wiki 页面）/中文名 / 别名/状态/板块/块数/字数' &&
      boot.pageBar === 1 && boot.selects === 1 && boot.inputs === 1 &&
      boot.btns.join('/') === '列表/核对模式（2）/新建词条/导入/导出/刷新',
      'URL=' + boot.path + '；二级目录=[' + boot.tabs + ']，激活=「' + boot.active + '」；' +
      '表头=[' + boot.heads + ']；\n      页面栏按钮=[' + boot.btns + ']；select=' + boot.selects + ' input=' + boot.inputs)
    check('①b 板块骨架与全库读数都渲染出来了',
      boot.boards.length === 5 && boot.boards[0].includes('全部') &&
      boot.note.includes('共 5 个词条') && boot.note.includes('有正文 3') &&
      boot.note.includes('有中文名 3') && boot.note.includes('已核对 1') && boot.note.includes('待核对 2'),
      '板块=[' + boot.boards.join(' | ') + ']；读数行=「' + boot.note + '」')
    await page.screenshot({ path: path.join(SHOT_DIR, 't11-terms-list.png') })

    // ---------- ② 三种行 ----------
    const ens = await rowEns(page)
    const scrap = await rowSnap(page, 'Scrap Cup')
    const vault = await rowSnap(page, 'Ancient Vault')
    const equip = await rowSnap(page, 'Equipment')
    check('② 默认列表一次渲染出三种行：有中文名 / 没中文名（status 为空串）/ 无正文（chunkCount:0）',
      ens.join('/') === 'Ancient Vault/Equipment/Flame Altar/Scrap Cup' &&
      scrap && scrap.zh.includes('废料杯') && scrap.status === '待核对' && scrap.chunks === '3' &&
      vault && vault.zh === '未命名' && vault.zhEmpty === true && vault.status === '未收录' &&
      equip && equip.tags.join('/') === '无正文' && equip.chunks === '0' && equip.zh.includes('装备'),
      '默认视图（main）共 ' + ens.length + ' 行=[' + ens.join(' / ') + ']\n      ' +
      'Scrap Cup：中文名「' + (scrap && scrap.zh) + '」状态「' + (scrap && scrap.status) + '」块数 ' + (scrap && scrap.chunks) + '\n      ' +
      'Ancient Vault：中文名「' + (vault && vault.zh) + '」(empty 样式=' + (vault && vault.zhEmpty) + ') 状态「' + (vault && vault.status) + '」\n      ' +
      'Equipment：行内 Tag=[' + (equip && equip.tags.join(' / ')) + '] 块数 ' + (equip && equip.chunks))

    const allPicked = await pickView(page, 'all')
    await page.waitForTimeout(1200)
    const allEns = await rowEns(page)
    check('②b 「全部页面」预设把既没正文也没中文名的空页面也放出来（main 与 all 真的不同）',
      allPicked && allEns.length === 5 && allEns.includes('Weapon Subtype') && ens.length === 4,
      '切到 all 后 ' + allEns.length + ' 行=[' + allEns.join(' / ') + ']；默认 main 是 ' + ens.length + ' 行')

    // ---------- ③ 视图预设驱动后端 ----------
    const sel = await viewSelect(page)
    const beforeNoChunk = callCount('/kb/terms?')
    const pickedNoChunk = await pickView(page, 'nochunk')
    await page.waitForTimeout(1200)
    const nochunkEns = await rowEns(page)
    const viewReq = calls.filter((u) => u.includes('/kb/terms?') && u.includes('view=nochunk')).length
    const pickedUnnamed = await pickView(page, 'unnamed')
    await page.waitForTimeout(1200)
    const unnamedEns = await rowEns(page)
    await pickView(page, 'main')
    await page.waitForTimeout(1200)
    check('③ 视图预设是唯一筛选维度：切一下带 view= 重新取数，结果真的变',
      sel && sel.options.map((o) => o.value).join('/') === 'main/all/draft/verified/rejected/unnamed/nochunk' &&
      sel.options[0].label.includes('默认 · 4') && sel.options[1].label.includes('全部页面 · 5') &&
      sel.options[6].label.includes('无正文 · 2') &&
      pickedNoChunk && viewReq > 0 && nochunkEns.join('/') === 'Equipment/Weapon Subtype' &&
      pickedUnnamed && unnamedEns.join('/') === 'Ancient Vault/Weapon Subtype',
      '预设=[' + sel.options.map((o) => o.label).join(' | ') + ']\n      ' +
      '/kb/terms? 请求 ' + beforeNoChunk + ' → ' + callCount('/kb/terms?') + ' 个，其中 view=nochunk 的 ' + viewReq + ' 个\n      ' +
      '无正文 → [' + nochunkEns.join(' / ') + ']；无中文名 → [' + unnamedEns.join(' / ') + ']')

    // ---------- ④ 核对模式 + 批量 ----------
    const draftBefore = callCount('/kb/terms?')
    const toReview = await domClick(page, '.page-bar .bar-actions button', '核对模式')
    await page.waitForTimeout(1500)
    const review = await page.evaluate(() => ({
      cardEn: ((document.querySelector('.card .card-en') || {}).innerText || '').trim(),
      pos: ((document.querySelector('.card .queue-pos') || {}).innerText || '').replace(/\s+/g, ' ').trim(),
      kbd: !!document.querySelector('.kbd-note'),
      progress: ((document.querySelector('.progress .pmeta') || {}).innerText || '').replace(/\s+/g, ' ').trim(),
      barProgress: ((document.querySelector('.page-bar .stat') || {}).innerText || '').replace(/\s+/g, ' ').trim(),
      checkboxes: document.querySelectorAll('table.tbl tbody .pick input').length,
      panelTitle: ((document.querySelector('.panel-wrap .panel-title') || {}).innerText || '').trim(),
    }))
    const draftReq = calls.filter((u) => u.includes('/kb/terms?') && u.includes('view=draft')).length
    check('④ 核对模式（原逐条流水线）保留：卡片 + 快捷键说明 + 进度 + 逐个勾选框',
      toReview && draftReq > 0 && review.kbd &&
      review.cardEn === 'Equipment' && review.progress.includes('待核对') &&
      review.barProgress.includes('核对进度') && review.checkboxes === 2,
      '/kb/terms?view=draft 请求 ' + draftReq + ' 个（切换前 ' + draftBefore + '）\n      ' +
      '当前卡片=「' + review.cardEn + '」（' + review.pos + '），快捷键说明=' + review.kbd + '\n      ' +
      '面板进度=「' + review.progress + '」；页面栏=「' + review.barProgress + '」；勾选框 ' + review.checkboxes + ' 个')
    await page.screenshot({ path: path.join(SHOT_DIR, 't11-terms-review.png') })

    await page.evaluate(() => {
      const cb = document.querySelector('table.tbl thead th.pick input')
      cb.checked = true
      cb.dispatchEvent(new Event('change', { bubbles: true }))
    })
    await page.waitForTimeout(400)
    const batchBar = await page.evaluate(() => ({
      text: ((document.querySelector('.batchbar') || {}).innerText || '').replace(/\s+/g, ' ').trim(),
      btns: [...document.querySelectorAll('.batchbar button')].map((b) => (b.textContent || '').trim()),
      picked: document.querySelectorAll('table.tbl tbody .pick input:checked').length,
    }))
    const batchBefore = callCount('/kb/terms/batch')
    const batchClicked = await domClick(page, '.batchbar button', '标为已核对')
    await page.waitForTimeout(1400)
    const batchBody = bodiesOf('/kb/terms/batch').slice(-1)[0]
    const afterBatch = await page.evaluate(() => ({
      items: [...document.querySelectorAll('table.tbl tbody tr.row')].map((r) => {
        const e = r.querySelector('td.t .en')
        return e ? e.textContent.trim() : ''
      }),
      empty: !!document.querySelector('.empty'),
      emptyText: ((document.querySelector('.empty') || {}).innerText || '').replace(/\s+/g, ' ').trim(),
    }))
    check('④b 勾选 → 「标为已核对」→ POST /kb/terms/batch，队列里两条一起改掉',
      batchBar.picked === 2 && batchBar.btns.join('/') === '标为已核对/标为排除/取消勾选' &&
      batchClicked && callCount('/kb/terms/batch') > batchBefore &&
      batchBody && batchBody.status === 'verified' &&
      (batchBody.items || []).slice().sort().join('/') === 'Equipment/Scrap Cup' &&
      afterBatch.items.length === 0 && afterBatch.empty,
      '批量条=「' + batchBar.text + '」，勾中 ' + batchBar.picked + ' 条；POST body=' + JSON.stringify(batchBody) + '\n      ' +
      '批量后队列剩 ' + afterBatch.items.length + ' 条，空态=「' + afterBatch.emptyText + '」')

    await domClick(page, '.page-bar .bar-actions button', '列表')
    await page.waitForTimeout(1300)

    // ---------- ⑤ 状态标签 → 三选一 → POST /kb/terms ----------
    await page.evaluate(() => { window.__confirms = [] })
    const trigOpened = await rowClick(page, 'Equipment', 'td.st button.trig')
    await page.waitForTimeout(300)
    const equipBefore = await rowSnap(page, 'Equipment')
    const termBefore = callCount('/kb/terms')
    const opted = await rowClick(page, 'Equipment', 'td.st .menu button.opt', '待核对')
    await page.waitForTimeout(1200)
    const termBody = bodiesOf('/kb/terms').slice(-1)[0]
    const equipAfter = await rowSnap(page, 'Equipment')
    check('⑤ 状态标签是可点属性：弹三选一 + 清空 → 选中后 POST /kb/terms（一次 upsert 同时带 zh 与 status）',
      trigOpened && equipBefore.menu.join('/') === '待核对/已核对/弃用/清空中文名' &&
      opted && callCount('/kb/terms') > termBefore &&
      termBody && termBody.en === 'Equipment' && termBody.zh === '装备' &&
      termBody.status === 'draft' && Object.keys(termBody).sort().join('/') === 'en/status/zh' &&
      equipAfter.status === '待核对' && equipBefore.status === '已核对',
      '点击前标签「' + equipBefore.status + '」→ 菜单=[' + equipBefore.menu.join(' / ') + ']\n      ' +
      'POST /kb/terms body=' + JSON.stringify(termBody) + '（键=' + Object.keys(termBody).sort().join('/') + '）\n      ' +
      '选后标签「' + equipAfter.status + '」')
    await page.screenshot({ path: path.join(SHOT_DIR, 't11-terms-status.png') })

    // ---------- ⑥ 抽屉：三个区 ----------
    const opened = await rowClick(page, 'Scrap Cup', 'td.t')
    await page.waitForTimeout(900)
    const drawer = await drawerSnap(page)
    const noChunkDrawer = await (async () => {
      await page.evaluate(() => document.querySelector('.dr-close').click())
      await page.waitForTimeout(300)
      await rowClick(page, 'Equipment', 'td.t')
      await page.waitForTimeout(700)
      const d = await drawerSnap(page)
      await page.evaluate(() => document.querySelector('.dr-close').click())
      await page.waitForTimeout(300)
      await rowClick(page, 'Scrap Cup', 'td.t')
      await page.waitForTimeout(700)
      return d
    })()
    check('⑥ 列表 + 抽屉：一条词条的名字区 / 板块区 / 正文区都在一个抽屉里',
      opened && drawer && drawer.aria === '词条 Scrap Cup' && drawer.en === 'Scrap Cup' &&
      drawer.sections.join('/') === '名字/板块/正文' &&
      drawer.zhValue === '废料杯' && drawer.statusOptions.join('/') === '待核对/已核对/弃用' &&
      drawer.chunkCount === 3 && drawer.foot.join('/') === '下架整个词条' &&
      noChunkDrawer && noChunkDrawer.chunkCount === 0 &&
      noChunkDrawer.bodyText.includes('这个词条没有文本块'),
      '抽屉 aria=「' + (drawer && drawer.aria) + '」，区块=[' + (drawer && drawer.sections.join(' / ')) + ']\n      ' +
      '名字区中文名=「' + (drawer && drawer.zhValue) + '」状态选项=[' + (drawer && drawer.statusOptions.join(' / ')) + ']\n      ' +
      '正文区 ' + (drawer && drawer.chunkCount) + ' 块；底部按钮=[' + (drawer && drawer.foot.join(' / ')) + ']\n      ' +
      'Equipment（chunkCount 0）打开后正文区 ' + (noChunkDrawer && noChunkDrawer.chunkCount) + ' 块，\n      ' +
      '空态提示命中「这个词条没有文本块」=' + !!(noChunkDrawer && noChunkDrawer.bodyText.includes('这个词条没有文本块')))
    await page.screenshot({ path: path.join(SHOT_DIR, 't11-terms-drawer.png') })

    // ---------- ⑦ 块级下架 / 恢复 ----------
    await page.evaluate(() => { window.__confirms = [] })
    const chunkHead = await page.evaluate(() => [...document.querySelectorAll('.drawer .chunk')].map((c) => ({
      id: ((c.querySelector('.cnum') || {}).innerText || '').trim(),
      ops: [...c.querySelectorAll('.chead button')].map((b) => (b.textContent || '').trim()),
    })))
    const chunkRetireBefore = callCount('/kb/terms/chunk/retire')
    const clickedRetire = await page.evaluate(() => {
      const c = [...document.querySelectorAll('.drawer .chunk')][1]
      if (!c) return false
      const b = [...c.querySelectorAll('.chead button')].find((x) => (x.textContent || '').includes('下架'))
      if (!b) return false
      b.click()
      return true
    })
    await page.waitForTimeout(1100)
    const chunkConfirms = await page.evaluate(() => window.__confirms)
    const chunkRetireBody = bodiesOf('/kb/terms/chunk/retire').slice(-1)[0]
    const afterChunkRetire = await chunkSnap(page, 1)
    check('⑦ 块级下架：POST /kb/terms/chunk/retire → 块上 Tag「已下架」+ 灰化，文本仍可读可编辑',
      chunkHead.length === 3 && clickedRetire &&
      callCount('/kb/terms/chunk/retire') > chunkRetireBefore &&
      chunkConfirms.some((c) => c.includes('下架块 12')) &&
      chunkRetireBody && chunkRetireBody.i === 12 && chunkRetireBody.retired === true &&
      afterChunkRetire.tag.includes('已下架') && afterChunkRetire.ops.includes('恢复') &&
      parseFloat(afterChunkRetire.opacity) < 0.8 && afterChunkRetire.text.length > 0,
      '抽屉里 ' + chunkHead.length + ' 个块，操作=[' + chunkHead.map((c) => c.ops.join('+')).join(' | ') + ']\n      ' +
      'confirm=「' + ((chunkConfirms[0] || '').replace(/\n+/g, ' ⏎ ')) + '」；POST body=' + JSON.stringify(chunkRetireBody) + '\n      ' +
      '下架后 Tag=「' + afterChunkRetire.tag + '」按钮=[' + afterChunkRetire.ops.join('/') + '] opacity=' + afterChunkRetire.opacity +
      '（灰化），textarea 仍有「' + afterChunkRetire.text + '」')

    const chunkRecovered = await page.evaluate(() => {
      const c = [...document.querySelectorAll('.drawer .chunk')][1]
      const b = [...c.querySelectorAll('.chead button')].find((x) => (x.textContent || '').includes('恢复'))
      if (!b) return false
      b.click()
      return true
    })
    await page.waitForTimeout(1000)
    const chunkRecoverBody = bodiesOf('/kb/terms/chunk/retire').slice(-1)[0]
    const afterChunkRestore = await chunkSnap(page, 1)
    check('⑦b 块级恢复：POST /kb/terms/chunk/retire{retired:false} → 标记与灰化都撤掉',
      chunkRecovered && chunkRecoverBody.retired === false &&
      afterChunkRestore.tag === '' && parseFloat(afterChunkRestore.opacity) === 1,
      'POST body=' + JSON.stringify(chunkRecoverBody) + '；Tag=「' + (afterChunkRestore.tag || '（无）') + '」opacity=' + afterChunkRestore.opacity)

    // ---------- ⑧ 整条下架 / 恢复 ----------
    await page.evaluate(() => { window.__confirms = [] })
    const retireBefore = callCount('/kb/terms/retire')
    const footClicked = await page.evaluate(() => {
      const b = document.querySelector('.drawer .dr-foot button')
      if (!b) return false
      b.click()
      return true
    })
    await page.waitForTimeout(1300)
    const termConfirms = await page.evaluate(() => window.__confirms)
    const retireBody = bodiesOf('/kb/terms/retire').slice(-1)[0]
    const afterTermRetire = await rowSnap(page, 'Scrap Cup')
    const footAfter = await page.evaluate(() => [...document.querySelectorAll('.drawer .dr-foot button')].map((b) => (b.textContent || '').trim()))
    check('⑧ 整条下架：确认框写明「将下架该词条的 N 个文本块」→ POST /kb/terms/retire{retired:true} → 行上标「已下架」',
      footClicked && callCount('/kb/terms/retire') > retireBefore &&
      termConfirms.some((c) => c.includes('将下架该词条的 3 个文本块')) &&
      !termConfirms.some((c) => c.includes('删除')) &&
      retireBody && retireBody.title === 'Scrap Cup' && retireBody.retired === true &&
      afterTermRetire.tags.join('/') === '已下架' && footAfter.join('/') === '恢复整个词条',
      'confirm=「' + ((termConfirms[0] || '').replace(/\n+/g, ' ⏎ ')) + '」\n      ' +
      'POST body=' + JSON.stringify(retireBody) + '；行上 Tag=[' + afterTermRetire.tags.join(' / ') + ']，抽屉底部=[' + footAfter.join(' / ') + ']')
    await page.screenshot({ path: path.join(SHOT_DIR, 't11-terms-retired.png') })

    await page.evaluate(() => { window.__confirms = [] })
    await page.evaluate(() => document.querySelector('.drawer .dr-foot button').click())
    await page.waitForTimeout(1300)
    const recoverBody = bodiesOf('/kb/terms/retire').slice(-1)[0]
    const afterTermRestore = await rowSnap(page, 'Scrap Cup')
    check('⑧b 恢复整条：POST /kb/terms/retire{retired:false} → 「已下架」消失、按钮回到「下架整个词条」',
      recoverBody && recoverBody.retired === false && afterTermRestore.tags.length === 0 &&
      (await page.evaluate(() => (document.querySelector('.drawer .dr-foot button') || {}).textContent.trim())) === '下架整个词条',
      'POST body=' + JSON.stringify(recoverBody) + '；行上 Tag=[' + (afterTermRestore.tags.join(' / ') || '（无）') + ']')

    // ---------- ⑨ 改板块 = 原始分类归属 ----------
    await page.evaluate(() => { window.__confirms = [] })
    const boardOpened = await page.evaluate(() => {
      const b = [...document.querySelectorAll('.drawer .dr-sec button')].find((x) => (x.textContent || '').includes('改归属'))
      if (!b) return false
      b.click()
      return true
    })
    await page.waitForTimeout(800)
    const bpanel = await page.evaluate(() => {
      const p = document.querySelector('.drawer .bpanel')
      if (!p) return null
      return {
        cat: ((p.querySelector('.bcat') || {}).innerText || '').trim(),
        warn: ((p.querySelector('.warn-line') || {}).innerText || '').replace(/\s+/g, ''),
        opts: [...p.querySelectorAll('select option')].map((o) => (o.textContent || '').trim()),
      }
    })
    const catBefore = callCount('/kb/categories/set')
    await page.evaluate(() => {
      const s = document.querySelector('.drawer .bpanel select')
      s.value = 'combat'
      s.dispatchEvent(new Event('change', { bubbles: true }))
    })
    await page.waitForTimeout(300)
    await page.evaluate(() => {
      const b = [...document.querySelectorAll('.drawer .bpanel .dr-ops button')].find((x) => (x.textContent || '').trim() === '保存')
      b.click()
    })
    await page.waitForTimeout(1200)
    const catBody = bodiesOf('/kb/categories/set').slice(-1)[0]
    const catConfirms = await page.evaluate(() => window.__confirms)
    check('⑨ 改板块走「原始分类 → 大类」：POST /kb/categories/set，界面写明会影响同分类的所有词条',
      boardOpened && bpanel && bpanel.cat === 'Crafting Materials' &&
      bpanel.warn.includes('所有用到它的词条会一起换板块') &&
      callCount('/kb/categories/set') > catBefore &&
      catBody && catBody.raw === 'Crafting Materials' && catBody.groupKey === 'combat',
      '面板原始分类=「' + (bpanel && bpanel.cat) + '」；影响面文案=「' + (bpanel && bpanel.warn) + '」\n      ' +
      '下拉=[' + (bpanel && bpanel.opts.join(' / ')) + ']；POST body=' + JSON.stringify(catBody) + '\n      ' +
      'confirm=「' + ((catConfirms[0] || '').replace(/\n+/g, ' ⏎ ')) + '」')

    // ---------- ⑩ 保存这一块 / 追加文本块 ----------
    const saveBefore = callCount('/kb/terms/chunk')
    await page.evaluate(() => {
      const ta = document.querySelector('.drawer .chunk textarea')
      ta.value = '废料杯由 5 个废料在熔炉合成。'
      ta.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await page.waitForTimeout(250)
    const saved = await page.evaluate(() => {
      const b = [...document.querySelectorAll('.drawer .chunk .chead button')].find((x) => (x.textContent || '').includes('保存这一块'))
      if (!b) return false
      b.click()
      return true
    })
    await page.waitForTimeout(1200)
    const saveBody = bodiesOf('/kb/terms/chunk').slice(-1)[0]
    check('⑩ 保存这一块：改块文本 → POST /kb/terms/chunk{ i, text }',
      saved && callCount('/kb/terms/chunk') > saveBefore &&
      saveBody && saveBody.i === 11 && saveBody.text === '废料杯由 5 个废料在熔炉合成。',
      'POST body=' + JSON.stringify(saveBody))

    const addShown = await page.evaluate(() => {
      const b = [...document.querySelectorAll('.drawer button')].find((x) => (x.textContent || '').includes('追加文本块'))
      if (!b) return false
      b.click()
      return true
    })
    await page.waitForTimeout(400)
    await page.evaluate(() => {
      const ta = document.querySelector('.drawer .add-chunk textarea')
      ta.value = '灰烬宝库在北侧。'
      ta.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await page.waitForTimeout(250)
    const added = await page.evaluate(() => {
      const b = [...document.querySelectorAll('.drawer .add-chunk button')].find((x) => (x.textContent || '').trim() === '追加')
      if (!b) return false
      b.click()
      return true
    })
    await page.waitForTimeout(1300)
    const addBody = bodiesOf('/kb/terms/chunk/add').slice(-1)[0]
    const afterAdd = await chunkSnap(page, 3)
    check('⑩b 追加文本块：POST /kb/terms/chunk/add{ title, text, url, cats } → 抽屉里多出第 4 块',
      addShown && added && addBody && addBody.title === 'Scrap Cup' && addBody.text === '灰烬宝库在北侧。' &&
      afterAdd && afterAdd.text.includes('灰烬宝库'),
      'POST body=' + JSON.stringify(addBody) + '；追加后第 4 块=「' + (afterAdd && afterAdd.text) + '」')

    await page.evaluate(() => { window.__confirms = [] })
    await page.evaluate(() => document.querySelector('.dr-close').click())
    await page.waitForTimeout(400)

    // ---------- ⑪ 就地改中文名：body 只有 en/zh/status ----------
    const editOpened = await rowClick(page, 'Scrap Cup', 'td.zh button.zhv')
    await page.waitForTimeout(400)
    const editBox = await page.evaluate(() => {
      const tr = [...document.querySelectorAll('table.tbl tbody tr.row')].find((r) => {
        const e = r.querySelector('td.t .en')
        return e && e.textContent.trim() === 'Scrap Cup'
      })
      const inputs = [...tr.querySelectorAll('td.zh .edit input')]
      return {
        n: inputs.length,
        aria: inputs[0] ? inputs[0].getAttribute('aria-label') : '',
        value: inputs[0] ? inputs[0].value : '',
        ops: [...tr.querySelectorAll('td.zh .edit button')].map((b) => (b.textContent || '').trim()),
      }
    })
    await setInput(page, 'table.tbl tbody tr.row td.zh .edit input.w-zh', '废料杯（改名）、破杯子')
    await page.waitForTimeout(300)
    const renameBefore = callCount('/kb/terms')
    const renameSaved = await page.evaluate(() => {
      const tr = [...document.querySelectorAll('table.tbl tbody tr.row')].find((r) => {
        const e = r.querySelector('td.t .en')
        return e && e.textContent.trim() === 'Scrap Cup'
      })
      const b = [...tr.querySelectorAll('td.zh .edit button')].find((x) => (x.textContent || '').trim() === '保存')
      if (!b) return false
      b.click()
      return true
    })
    await page.waitForTimeout(1300)
    const renameBodies = bodiesOf('/kb/terms').filter((b) => b && b.en === 'Scrap Cup')
    const renameBody = renameBodies[renameBodies.length - 1]
    const afterRename = await rowSnap(page, 'Scrap Cup')
    check('⑪ 改中文名 / 别名：就地编辑 + 保存 → POST /kb/terms，body **只有** en/zh/status（不再回填 page/category）',
      editOpened && editBox.n === 1 && editBox.aria === '中文名与别名' &&
      editBox.ops.join('/') === '保存/取消' && editBox.value === '废料杯' &&
      renameSaved && callCount('/kb/terms') > renameBefore &&
      renameBody && renameBody.zh === '废料杯（改名）、破杯子' &&
      !('page' in renameBody) && !('category' in renameBody) &&
      Object.keys(renameBody).sort().join('/') === 'en/status/zh' &&
      afterRename.zh.includes('废料杯（改名）') && !afterRename.editing,
      '编辑框字段 aria=「' + editBox.aria + '」（原值「' + editBox.value + '」）按钮=[' + editBox.ops.join('/') + ']\n      ' +
      'POST /kb/terms body=' + JSON.stringify(renameBody) + '（键=' + Object.keys(renameBody).sort().join('/') + '）\n      ' +
      '保存后行上显示「' + afterRename.zh.replace(/\s+/g, ' ') + '」，编辑框已收起=' + !afterRename.editing)
    await page.screenshot({ path: path.join(SHOT_DIR, 't11-terms-rename.png') })

    // ---------- ⑫ 清空中文名 ----------
    await page.evaluate(() => { window.__confirms = [] })
    await rowClick(page, 'Scrap Cup', 'td.st button.trig')
    await page.waitForTimeout(300)
    const clearBefore = callCount('/kb/terms/clear')
    const cleared = await rowClick(page, 'Scrap Cup', 'td.st .menu button.opt', '清空中文名')
    await page.waitForTimeout(1300)
    const clearBody = bodiesOf('/kb/terms/clear').slice(-1)[0]
    const clearConfirms = await page.evaluate(() => window.__confirms)
    const afterClear = await rowSnap(page, 'Scrap Cup')
    check('⑫ 清空中文名：POST /kb/terms/clear{ en } → 行上回到「未命名」、状态回「待核对」',
      cleared && callCount('/kb/terms/clear') > clearBefore &&
      clearBody && clearBody.en === 'Scrap Cup' &&
      clearConfirms.some((c) => c.includes('清空「Scrap Cup」的中文名')) &&
      afterClear.zh === '未命名' && afterClear.zhEmpty === true && afterClear.status === '待核对',
      'confirm=「' + ((clearConfirms[0] || '').replace(/\n+/g, ' ⏎ ')) + '」；POST body=' + JSON.stringify(clearBody) + '\n      ' +
      '清空后行上「' + afterClear.zh + '」状态「' + afterClear.status + '」')

    // ---------- ⑬ 导出（Excel 旁路） ----------
    const expBefore = callCount('/kb/terms/export')
    await page.evaluate(() => { window.__downloads = [] })
    const exported = await domClick(page, '.page-bar .bar-actions button', '导出')
    await page.waitForTimeout(900)
    const downloads = await page.evaluate(() => window.__downloads)
    const exportNotice = await page.evaluate(() => ((document.querySelector('.notice') || {}).innerText || '').replace(/\s+/g, ' ').trim())
    check('⑬ Excel 旁路：GET /kb/terms/export → 前端自己造 Blob 触发下载（不再直接改文件）',
      exported && callCount('/kb/terms/export') > expBefore &&
      downloads.filter((d) => d === 'blob').length >= 1 &&
      downloads.some((d) => d.startsWith('anchor:')) &&
      exportNotice.includes('已导出'),
      'GET /kb/terms/export 请求 ' + expBefore + ' → ' + callCount('/kb/terms/export') + ' 个；' +
      '下载动作=' + JSON.stringify(downloads) + '；提示=「' + exportNotice + '」')

    // ---------- ⑭ 加载的是新面板 ----------
    const reqKeys = await page.evaluate(() =>
      performance.getEntriesByType('resource')
        .filter((e) => e.name.includes('/src/admin/views/kb/'))
        .map((e) => e.name.split('/').pop().split('?')[0]))
    // 只认「当前该有的 4 个面板模块」—— 多一个少一个都算不通过。
    // 不点名旧文件，免得把已经删掉的名字又写回脚本里。
    const panels = [...new Set(reqKeys)].filter((n) => n.endsWith('.vue')).sort()
    check('⑭ 页面加载的是本工作区源码里的「词条」面板，且 kb 目录下只加载了这 4 个面板',
      panels.join('/') === 'CategoryPanel.vue/ProposalPanel.vue/SourcePanel.vue/TermPanel.vue',
      '加载到的 kb 面板模块（去重排序）：' + (panels.join(', ') || '(空)'))

    // ---------- ⑮ 页面上没有"讲我们怎么实现"的说明文字 ----------
    const inner = await page.evaluate(() => document.body.innerText)
    const banned = ['墓碑', 'upsert', 'index.bin', '向量重建', '本面板', '为了']
    const hits = banned.filter((w) => inner.includes(w))
    check('⑮ 页面正文里没有实现说明类文字（墓碑 / upsert / index.bin 等）',
      hits.length === 0, hits.length ? '命中：' + hits.join(', ') : '已检查 ' + banned.length + ' 个词，均未出现')

    // ---------- ⑯ 提案（接口没变）----------
    await page.goto(BASE + '/admin/kb?tab=proposals', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(1800)
    const propBoot = await page.evaluate(() => {
      const items = [...document.querySelectorAll('article.item')]
      return {
        n: items.length,
        heads: items.map((i) => ((i.querySelector('.item-title') || {}).innerText || '').trim()),
        ops: items.map((i) => [...i.querySelectorAll('.item-actions button')].map((b) => (b.textContent || '').trim())),
      }
    })
    check('⑯ 提案卡片仍是「采纳 / 拒绝 / 删除」三个动作',
      propBoot.n === 2 && propBoot.ops[0].join('/') === '采纳/拒绝/删除' && propBoot.ops[1].join('/') === '采纳/拒绝/删除',
      '卡片=[' + propBoot.heads.join('、') + ']，动作=' + JSON.stringify(propBoot.ops))

    const propOpened = await domClick(page, 'article.item .col--new pre.text')
    await page.waitForTimeout(400)
    const propEditState = await page.evaluate(() => {
      const it = document.querySelector('article.item')
      const ta = it.querySelector('.col--new textarea.text.edit')
      return { hasTextarea: !!ta, value: ta ? ta.value : '', aria: ta ? ta.getAttribute('aria-label') : '' }
    })
    await page.evaluate(() => {
      const ta = document.querySelector('article.item .col--new textarea.text.edit')
      ta.value = '废料杯由 5 个废料在熔炉合成（人手改过）。'
      ta.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await page.waitForTimeout(400)
    const chunkBefore = callCount('/kb/terms/chunk')
    const revBefore = callCount('/kb/proposals/review')
    const chunksBefore = callCount('/kb/terms/chunks?')
    await page.evaluate(() => { window.__confirms = [] })
    const accepted = await domClick(page, 'article.item .item-actions button', '采纳')
    await page.waitForTimeout(1600)
    const acceptBodies = bodiesOf('/kb/terms/chunk').slice(-1)[0]
    const acceptRev = bodiesOf('/kb/proposals/review').slice(-1)[0]
    const acceptConfirm = await page.evaluate(() => window.__confirms)
    const afterAccept = await page.evaluate(() => document.querySelectorAll('article.item').length)
    check('⑯b 提案先改再采纳：先 GET /kb/terms/chunks 找块号 → POST /kb/terms/chunk 落文本 → 再 /kb/proposals/review 采纳',
      propOpened && propEditState.hasTextarea && propEditState.aria === '建议文本' &&
      accepted && callCount('/kb/terms/chunks?') > chunksBefore &&
      callCount('/kb/terms/chunk') > chunkBefore && callCount('/kb/proposals/review') > revBefore &&
      acceptBodies && acceptBodies.i === 11 && acceptBodies.text === '废料杯由 5 个废料在熔炉合成（人手改过）。' &&
      acceptRev && acceptRev.id === 7 && acceptRev.approve === true &&
      acceptConfirm.some((c) => c.includes('会先用你改后的文本覆盖')),
      '建议文本 aria=「' + propEditState.aria + '」（原值「' + propEditState.value + '」）\n      ' +
      'GET /kb/terms/chunks 请求 ' + chunksBefore + ' → ' + callCount('/kb/terms/chunks?') + ' 个；' +
      'POST /kb/terms/chunk body=' + JSON.stringify(acceptBodies) + '\n      ' +
      'POST /kb/proposals/review body=' + JSON.stringify(acceptRev) + '；confirm=「' + ((acceptConfirm[0] || '').replace(/\n+/g, ' ⏎ ')) + '」\n      ' +
      '待确认剩 ' + afterAccept + ' 条')
    await page.screenshot({ path: path.join(SHOT_DIR, 't11-proposal-edit.png') })

    await page.evaluate(() => { window.__confirms = [] })
    const deleted = await domClick(page, 'article.item .item-actions button', '删除')
    await page.waitForTimeout(1400)
    const delRev = bodiesOf('/kb/proposals/review').slice(-1)[0]
    const delConfirm = await page.evaluate(() => window.__confirms)
    const afterDelete = await page.evaluate(() => document.querySelectorAll('article.item').length)
    check('⑯c 提案「删除」在前端用拒绝表达（后端无删除接口），走 /review approve=false 并说明记录保留',
      deleted && delRev && delRev.id === 8 && delRev.approve === false && !delRev.delete &&
      delConfirm.some((c) => c.includes('标成「已拒绝」')) && !delConfirm.some((c) => c.includes('永久删除')) &&
      afterDelete === 0,
      'POST /kb/proposals/review body=' + JSON.stringify(delRev) + '；confirm=「' + ((delConfirm[0] || '').replace(/\n+/g, ' ⏎ ')) + '」；待确认剩 ' + afterDelete + ' 条')

    // ---------- ⑰ 0 console error ----------
    check('⑰ 全程 0 console error', errors.length === 0,
      errors.length ? errors.length + ' 条，首条：' + errors[0] : '干净')

    console.log('\n================ 汇总 ================')
    console.log('  依赖垫片：拦下 ' + shim.seen + ' 个 deps 模块，统一了 ' + shim.rewritten + ' 个的 chunk ?v=')
    for (const s of shim.samples) console.log('    · ' + s)
    const pass = results.filter((r) => r.pass).length
    console.log('  ' + pass + ' / ' + results.length + ' 项通过')
    for (const r of results.filter((x) => !x.pass)) console.log('  ❌ ' + r.name)
    console.log('  截图目录: ' + SHOT_DIR + '（前缀 t11-）')
  } finally {
    await page.close().catch(() => {})
  }
  process.exit(results.some((r) => !r.pass) ? 1 : 0)
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1) })
