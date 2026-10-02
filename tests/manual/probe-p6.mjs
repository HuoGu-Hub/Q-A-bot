// P6 验证：管理接口 + 求助流程
const B = 'http://127.0.0.1:8099'
const H = { 'content-type': 'application/json' }
let cookie = ''
async function login() {
  const r = await fetch(B + '/admin/api/login', {
    method: 'POST', headers: H,
    body: JSON.stringify({ password: 'E2E-p6-9911' }),
  })
  cookie = (r.headers.getSetCookie?.() ?? []).map(c => c.split(';')[0]).join('; ')
}
const AH = () => ({ 'content-type': 'application/json', cookie })
const aj = async (p) => (await fetch(B + '/admin/api' + p, { headers: AH() })).json()
const apost = async (p, b) => {
  const r = await fetch(B + '/admin/api' + p, { method: 'POST', headers: AH(), body: JSON.stringify(b) })
  return { status: r.status, body: await r.json() }
}
const pj = async (p) => (await fetch(B + '/api/public' + p, { headers: H })).json()
const ppost = async (p, b) => {
  const r = await fetch(B + '/api/public' + p, { method: 'POST', headers: H, body: JSON.stringify(b) })
  return { status: r.status, body: await r.json() }
}

await login()
console.log('═══ ① 管理概览 ═══')
const ov = await aj('/plaza/overview')
console.log('  enabled:', ov.enabled, '| onlyVoted:', ov.onlyVoted)
console.log('  投票数:', ov.voteCount, '| 求助数:', ov.helpCount)
console.log('  降级阈值:', ov.downvoteThreshold)
console.log('  今日问新答案:', ov.usage.askTodayTotal, '/', ov.usage.askLimitGlobal)

console.log('')
console.log('═══ ② 造数据：直接往库里写（走 SQL）═══')
console.log('  （用 shell 侧预置，见下）')

console.log('')
console.log('═══ ③ ★ 求助流程：网页申请 → 群内确认 ═══')
const req = await ppost('/plaza/help-request', { keyword: '废料杯', question: '废料杯到底怎么合成？' })
console.log('  网页申请 →', req.status, JSON.stringify(req.body).slice(0, 120))

console.log('')
console.log('═══ ④ ★ 安全验证：网页不能直接指定群号发消息 ═══')
const bad = await ppost('/plaza/ask-help', { groupId: 999999, keyword: 'x', question: 'y' })
console.log('  旧接口 ask-help →', bad.status, JSON.stringify(bad.body).slice(0, 80))
console.log('  ' + (bad.status === 404 ? '✅ 已移除（不再有任意群广播漏洞）' : '❌ 还能用！'))

console.log('')
console.log('═══ ⑤ 管理：被投票的内容 ═══')
const ans = await aj('/plaza/answers?limit=50')
console.log('  条数:', ans.count)
for (const a of ans.answers) {
  console.log('    statId=' + a.statId, '👍' + a.up, '👎' + a.down, '| 群' + a.groupId, '| 用户' + a.userId)
  console.log('      ' + String(a.question).slice(0, 40))
}

console.log('')
console.log('═══ ⑥ 管理：求助记录 ═══')
const hp = await aj('/plaza/help?limit=20')
console.log('  条数:', hp.count)
for (const h of hp.requests) {
  console.log('    [' + h.status + ']', String(h.question).slice(0, 40), '| 群' + h.groupId)
}
