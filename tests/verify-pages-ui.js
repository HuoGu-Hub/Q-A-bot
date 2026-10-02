/**
 * 「页面信息」三处改动的验收：
 *   ① 填写帮助挪到输入框旁、删掉「默认：原文」回显、工具栏不再有标记说明
 *   ② 保存/恢复的反馈走软弹窗（浮层），不再内联插入页面流
 *   ③ 编辑 : 预览 = 4 : 6
 */
const fs = require('node:fs')
const { connectCDP } = require('/root/.playwright/cdp')
const pw = (fs.readFileSync('/app/workspace/qqbot/.env','utf8').match(/^\s*ADMIN_PASSWORD\s*=\s*(.*)$/m)||[])[1].trim()
const DIR = '/app/workspace/qqbot/tests/screenshots/redesign/'
;(async () => {
  const b = await connectCDP(); const ctx = b.contexts()[0]; const page = await ctx.newPage()
  const errs = []
  page.on('pageerror', e => errs.push(String(e.message).slice(0,110)))
  try {
    await page.setViewportSize({ width: 1920, height: 1080 })
    const cdp = await ctx.newCDPSession(page)
    await cdp.send('Network.enable'); await cdp.send('Network.clearBrowserCache')
    await page.goto('http://localhost:8080/admin/login', { waitUntil:'domcontentloaded', timeout:25000 })
    await page.waitForTimeout(1500)
    if (await page.evaluate(() => !!document.querySelector('input[type=password]'))) {
      await page.fill('input[type=password]', pw)
      await Promise.all([ page.waitForURL(u=>!String(u).includes('/login'),{timeout:15000}).catch(()=>{}),
        page.evaluate(()=>document.querySelector('form').requestSubmit()) ])
      await page.waitForTimeout(2500)
    }
    await cdp.send('Network.clearBrowserCache')
    await page.goto('http://localhost:8080/admin/pages', { waitUntil:'domcontentloaded', timeout:25000 })
    await page.waitForTimeout(3000)

    const R = await page.evaluate(() => {
      const bar = document.querySelector('.page-bar')
      const help = document.querySelector('.blk-help')
      const blk = document.querySelector('.blk')
      const cols = document.querySelector('.cols')
      const editCol = document.querySelector('.panel-wrap')
      const prevCol = document.querySelector('.preview-col')
      const gtc = cols ? getComputedStyle(cols).gridTemplateColumns : null
      return {
        barText: bar ? bar.innerText.replace(/\s+/g,' ') : null,
        hasBlkDefault: !!document.querySelector('.blk-default'),
        helpText: help ? help.innerText.replace(/\s+/g,' ') : null,
        helpInsideBlk: !!(help && blk && blk.contains(help)),
        gtc,
        editW: editCol ? +editCol.getBoundingClientRect().width.toFixed(1) : null,
        prevW: prevCol ? +prevCol.getBoundingClientRect().width.toFixed(1) : null,
      }
    })
    await page.screenshot({ path: DIR + 'pages-ui-layout.png', timeout:60000, animations:'disabled' })

    // 软弹窗：直接改一个块再点「保存本页」（会真写库，随后恢复）
    const before = await page.evaluate(() => document.querySelectorAll('.toaster .toast').length)
    await page.evaluate(() => {
      const inp = document.querySelector('.blk textarea, .blk input')
      const set = Object.getOwnPropertyDescriptor(
        inp.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype, 'value').set
      set.call(inp, inp.value + '　')
      inp.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await page.waitForTimeout(400)
    // 点「恢复默认」而不是保存，避免真的写库；它同样会触发 toast
    await page.evaluate(() => {
      const b = Array.from(document.querySelectorAll('.blk button')).find(x => x.textContent.trim() === '恢复默认')
      if (b) b.click()
    })
    await page.waitForTimeout(1200)
    const T = await page.evaluate(() => {
      const t = document.querySelector('.toaster .toast')
      if (!t) return { present: false }
      const r = t.getBoundingClientRect()
      const cs = getComputedStyle(t.parentElement)
      return { present: true, text: t.innerText.replace(/\s+/g,' '), pos: cs.position,
               inFlow: cs.position !== 'fixed', top: +r.top.toFixed(1),
               tone: t.className }
    })
    await page.screenshot({ path: DIR + 'pages-ui-toast.png', timeout:60000, animations:'disabled' })

    const ratio = R.editW && R.prevW ? (R.editW / R.prevW) : null
    console.log('工具栏文本:', R.barText)
    console.log('输入框旁帮助:', R.helpText, '| 在块内:', R.helpInsideBlk)
    console.log('「默认：」回显还在吗:', R.hasBlkDefault ? '❌ 还在' : '✅ 已删')
    console.log('两栏宽度: 编辑', R.editW, '/ 预览', R.prevW, '→ 比值', ratio ? ratio.toFixed(2) : null, '(目标 0.667)')
    console.log('软弹窗:', JSON.stringify(T))
    console.log('\n=== 断言 ===')
    for (const [l,c] of [
      ['工具栏不再出现「可用标记/占位符」', !/可用标记|占位符/.test(R.barText || '')],
      ['填写帮助移到了输入框旁（在 .blk 内）', R.helpInsideBlk],
      ['帮助里说明了标记与占位符', /\[文字\]\(链接\)/.test(R.helpText||'') && /\{kb\}/.test(R.helpText||'')],
      ['「默认：原文」回显已删除', !R.hasBlkDefault],
      ['两栏比例≈4:6（编辑/预览 ≈ 0.667）', !!ratio && ratio > 0.60 && ratio < 0.74],
      ['反馈是浮层（position:fixed，不占布局）', T.present && T.pos === 'fixed'],
      ['无页面异常', errs.length === 0],
    ]) console.log(`  ${c?'✅':'❌'} ${l}`)
    console.log('异常:', errs.length ? errs.slice(0,3).join(' | ') : '无')
  } finally { await page.close().catch(()=>{}) }
  process.exit(0)
})().catch(e=>{ console.error('ERR', e.message); process.exit(1) })
