/**
 * 前端人工验收辅助脚本 —— 把「该看哪些页」和「哪些页客观上有毛病」分开。
 *
 * 脚本负责机械的部分：逐个页面走一遍，抓控制台报错 / 接口 4xx-5xx /
 * 布局横向溢出 / 白屏，并逐页截图；**视觉好不好看由人来看**。
 *
 * 用法：
 *   node tests/verify-frontend.js                 # 走全部页面（公开站 + 后台）
 *   node tests/verify-frontend.js --only=admin    # 只走管理后台
 *   node tests/verify-frontend.js --only=public   # 只走公开站
 *   node tests/verify-frontend.js --keep          # 跑完不关标签页，留给手工点
 *   BASE=http://localhost:8080 node tests/verify-frontend.js
 *
 * 宿主机 Chrome 需带 --remote-debugging-port=9222 启动（连不上见 AGENTS.md）。
 * 密码默认从仓库根的 .env 读 ADMIN_PASSWORD，也可用 --password=xxx 覆盖。
 * 全程只新开一个标签页，跑完关掉，不碰你正在看的页面。
 */
const fs = require('node:fs')
const path = require('node:path')
const { connectCDP } = require('/root/.playwright/cdp')

const ROOT = path.resolve(__dirname, '..')
const args = process.argv.slice(2)
const hasFlag = (f) => args.includes(f)
const getOpt = (name, dft) => {
  const prefix = `--${name}=`
  const hit = args.find((a) => a.startsWith(prefix))
  return hit ? hit.slice(prefix.length) : dft
}

const ONLY = getOpt('only', '')
const KEEP = hasFlag('--keep')
const SHOT_DIR = path.resolve(getOpt('shotdir', path.join(ROOT, 'tests/screenshots/verify')))
const WIDE = { width: 1440, height: 900 }
const MOBILE = { width: 390, height: 780 }

/** 从 .env 读密码 —— 只用于填表单，绝不打印。 */
function readPassword() {
  const cli = getOpt('password', '')
  if (cli) return cli
  try {
    const txt = fs.readFileSync(path.join(ROOT, '.env'), 'utf8')
    const m = txt.match(/^\s*ADMIN_PASSWORD\s*=\s*(.*)$/m)
    return m ? m[1].trim().replace(/^["']|["']$/g, '') : ''
  } catch {
    return ''
  }
}

/** 没有任何配置时按顺序试这些地址，取第一个真能打开的。 */
const CANDIDATES = [
  'http://localhost:8080',
  'http://localhost:5173',
  'http://localhost:8094',
  'http://127.0.0.1:8080',
  'http://127.0.0.1:5173',
]

const PAGES = [
  // ---- 公开站 ----
  { group: 'public', name: '首页', path: '/', mobile: true },
  { group: 'public', name: '资料库', path: '/library', mobile: true },
  { group: 'public', name: '词条详情', path: '/entry/Abysswalker', mobile: true },
  { group: 'public', name: '问答广场', path: '/plaza', mobile: true },
  { group: 'public', name: '资料管理', path: '/library/manage', mobile: true },
  { group: 'public', name: '关于', path: '/about', mobile: true },
  { group: 'public', name: '404 兜底', path: '/this-page-does-not-exist', mobile: false, tolerantStatus: true },
  // ---- 管理后台 ----
  { group: 'admin', name: '登录页', path: '/admin/login', noAuth: true },
  // 「数据大屏」已并入看板（/screen 重定向到 /dashboard），不再单独列一条
  { group: 'admin', name: '看板', path: '/admin/dashboard' },
  { group: 'admin', name: '问答记录', path: '/admin/records' },
  { group: 'admin', name: '词条', path: '/admin/kb?tab=terms' },
  { group: 'admin', name: '日志', path: '/admin/logs' },
  { group: 'admin', name: '指令', path: '/admin/commands' },
  { group: 'admin', name: '设置', path: '/admin/settings' },
  { group: 'admin', name: '模型', path: '/admin/models' },
  { group: 'admin', name: '广场管理', path: '/admin/plaza' },
  { group: 'admin', name: '知识库管理', path: '/admin/kb' },
  { group: 'admin', name: '分类', path: '/admin/categories' },
]

/** 探一次地址是否真的能打开前端（而不是随便哪个服务返回 200）。 */
async function probe(page, base) {
  try {
    const resp = await page.goto(base + '/', { waitUntil: 'domcontentloaded', timeout: 8000 })
    if (!resp || !resp.ok()) return null
    const info = await page.evaluate(() => ({
      title: document.title,
      hasApp: !!document.querySelector('#app'),
    }))
    return info.hasApp ? info : null
  } catch {
    return null
  }
}

async function resolveBase(page) {
  const explicit = getOpt('base', process.env.BASE || '')
  if (explicit) {
    const info = await probe(page, explicit)
    if (!info) throw new Error(`指定的 BASE=${explicit} 打不开或不像前端页面`)
    console.log(`地址（显式指定）：${explicit}  「${info.title}」`)
    return explicit
  }
  for (const c of CANDIDATES) {
    const info = await probe(page, c)
    if (info) {
      console.log(`地址（自动探测命中）：${c}  「${info.title}」`)
      return c
    }
  }
  throw new Error(
    '没探测到任何可用的前端地址。请显式指定，例如：\n' +
      '  BASE=http://localhost:8080 node tests/verify-frontend.js\n' +
      '（注意：跑在容器里，但网址是给【宿主机浏览器】打开的，所以要用宿主机上的端口。）'
  )
}

async function login(page, base, password) {
  await page.goto(base + '/admin/login', { waitUntil: 'domcontentloaded', timeout: 20000 })
  await page.waitForTimeout(1500)
  const hasForm = await page.evaluate(() => !!document.querySelector('input[type=password]'))
  if (!hasForm) return 'already' // 已有会话
  if (!password) return 'nopass'
  await page.fill('input[type=password]', password)
  await Promise.all([
    page.waitForURL((u) => !String(u).includes('/login'), { timeout: 15000 }).catch(() => {}),
    page.evaluate(() => document.querySelector('form').requestSubmit()),
  ])
  await page.waitForTimeout(2500)
  const stillLogin = await page.evaluate(() => !!document.querySelector('input[type=password]'))
  return stillLogin ? 'failed' : 'ok'
}

/** 走一个页面并采集客观信号。 */
async function visit(page, base, spec) {
  const rec = {
    name: spec.name,
    group: spec.group,
    path: spec.path,
    status: null,
    finalUrl: '',
    consoleErrors: [],
    pageErrors: [],
    httpErrors: [],
    apiErrors: [],
    empty: false,
    overflow: null,
    bouncedToLogin: false,
    mobileOverflow: null,
  }

  const onConsole = (m) => {
    if (m.type() === 'error') rec.consoleErrors.push(m.text().slice(0, 300))
  }
  const onPageError = (e) => rec.pageErrors.push(String(e.message || e).slice(0, 300))
  const onResponse = (r) => {
    const u = r.url()
    const s = r.status()
    if (s < 400) return
    if (/favicon|\.map(\?|$)|hot-update/.test(u)) return
    const short = u.replace(base, '') || u
    rec.httpErrors.push(`${s} ${short}`)
    if (/\/(admin\/)?api\//.test(u)) rec.apiErrors.push(`${s} ${short}`)
  }

  page.on('console', onConsole)
  page.on('pageerror', onPageError)
  page.on('response', onResponse)
  try {
    const resp = await page.goto(base + spec.path, { waitUntil: 'domcontentloaded', timeout: 30000 })
    rec.status = resp ? resp.status() : null
    // 给异步数据（接口 + 渲染）留时间；大屏/看板轮询较多
    await page.waitForTimeout(2600)

    const snap = await page.evaluate(() => {
      const app = document.querySelector('#app')
      const html = app ? app.innerHTML : ''
      const text = app ? (app.innerText || '').trim() : ''
      return {
        finalUrl: location.href,
        appChars: html.length,
        textChars: text.length,
        overflow: document.documentElement.scrollWidth > window.innerWidth + 1,
        loginForm: !!document.querySelector('input[type=password]'),
      }
    })
    rec.finalUrl = snap.finalUrl
    rec.empty = snap.appChars < 60 || snap.textChars < 5
    rec.overflow = snap.overflow
    // 只有后台页面掉登录才是问题。公开站的 /library/manage 有「邀请码」
    // 密码框，按 input[type=password] 一刀切会误报。
    rec.bouncedToLogin = spec.group === 'admin' && !spec.noAuth && snap.loginForm

    if (spec.mobile) {
      await page.setViewportSize(MOBILE)
      await page.waitForTimeout(900)
      rec.mobileOverflow = await page.evaluate(
        () => document.documentElement.scrollWidth > window.innerWidth + 1
      )
      await page.setViewportSize(WIDE)
      await page.waitForTimeout(500)
    }
  } catch (e) {
    rec.pageErrors.push(`导航失败：${String(e.message || e).slice(0, 200)}`)
  } finally {
    page.off('console', onConsole)
    page.off('pageerror', onPageError)
    page.off('response', onResponse)
  }
  return rec
}

/** 一个页面「有硬毛病」的判定 —— 只报确定的问题，不猜视觉。 */
function problemsOf(r, spec) {
  const p = []
  // 故意访问不存在的路径时，后端返回 404 是正确行为
  if (r.status && r.status >= 400 && !(spec && spec.tolerantStatus)) p.push(`HTTP ${r.status}`)
  if (r.pageErrors.length) p.push(`${r.pageErrors.length} 个未捕获异常`)
  if (r.apiErrors.length) p.push(`${r.apiErrors.length} 个接口失败`)
  if (r.empty) p.push('内容为空（疑似白屏）')
  if (r.bouncedToLogin) p.push('被弹回登录页（会话失效）')
  if (r.overflow) p.push('宽屏横向溢出')
  if (r.mobileOverflow) p.push('手机横向溢出')
  return p
}

;(async () => {
  fs.mkdirSync(SHOT_DIR, { recursive: true })
  const password = readPassword()
  const browser = await connectCDP()
  const ctx = browser.contexts()[0]
  const page = await ctx.newPage()
  const results = []
  let base = ''

  try {
    base = await resolveBase(page)
    console.log(`截图目录：${SHOT_DIR}`)
    console.log('')

    const needAdmin = !ONLY || ONLY === 'admin'
    if (needAdmin) {
      const st = await login(page, base, password)
      const label = { ok: '登录成功', already: '已有会话，免登录', nopass: '未找到密码，跳过登录', failed: '登录失败' }[st]
      console.log(`后台登录：${label}`)
      if (st === 'failed' || st === 'nopass') {
        console.log('  ⚠️ 后台页面将只能看到登录页；密码请确认 .env 里的 ADMIN_PASSWORD')
      }
      console.log('')
    }

    const todo = PAGES.filter((p) => !ONLY || p.group === ONLY)
    await page.setViewportSize(WIDE)

    for (const spec of todo) {
      const t0 = Date.now()
      const rec = await visit(page, base, spec)
      rec.ms = Date.now() - t0
      rec._spec = spec
      const slug = `${rec.group}-${spec.path.replace(/[^a-z0-9]+/gi, '_').replace(/^_|_$/g, '') || 'root'}`
      await page.screenshot({ path: path.join(SHOT_DIR, `${slug}.png`), fullPage: false }).catch(() => {})
      results.push(rec)
      const probs = problemsOf(rec, spec)
      const flag = probs.length ? '❌' : '✅'
      console.log(
        `${flag} [${rec.group}] ${rec.name.padEnd(6, '　')} ${spec.path.padEnd(26)} ` +
          `${String(rec.ms / 1000).padStart(5)}s  ${probs.join('；')}`
      )
    }
  } catch (e) {
    console.error(`\n执行中断：${e.message}`)
    process.exitCode = 2
  } finally {
    if (results.length) {
      const bad = results.filter((r) => problemsOf(r, r._spec).length)
      const apiFail = [...new Set(results.flatMap((r) => r.apiErrors))]
      const conFail = results.filter((r) => r.consoleErrors.length)

      console.log('\n================ 汇总 ================')
      console.log(`页面总数 ${results.length}，有硬毛病 ${bad.length}`)
      if (bad.length) {
        console.log('\n有问题的页面：')
        for (const r of bad) console.log(`  · ${r.name}（${r.path}）→ ${problemsOf(r, r._spec).join('；')}`)
      }
      if (apiFail.length) {
        console.log('\n失败的接口（去重）：')
        for (const a of apiFail.slice(0, 20)) console.log(`  · ${a}`)
      }
      if (conFail.length) {
        console.log('\n有控制台报错的页面：')
        for (const r of conFail) console.log(`  · ${r.name}：${r.consoleErrors[0].slice(0, 160)}`)
      }
      console.log('\n说明：本脚本只判定「客观毛病」（报错/白屏/溢出/掉登录）。')
      console.log('      配色、间距、文案、交互手感这些要看截图或页面本身 ——')
      console.log(`      截图在 ${SHOT_DIR}`)
      if (bad.length) process.exitCode = 1
    }

    if (KEEP && base) {
      await page.goto(base + '/').catch(() => {})
      console.log(`\n--keep 已指定：标签页留在 ${base}，请手工点完再关。`)
    } else {
      await page.close().catch(() => {})
    }
  }

  // connectOverCDP 持有的 WebSocket 会让事件循环一直存活：不显式退出的话，
  // 脚本干完活还会挂住空等（表现为「跑得很慢」，其实活早干完了）。
  // ⚠️ 这里【不能】用 browser.close() —— 对 CDP 连接的浏览器，那会把
  //    宿主机上你正在用的 Chrome 一起关掉。直接退出进程即可，连接随之断开。
  process.exit(process.exitCode || 0)
})()
