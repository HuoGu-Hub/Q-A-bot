// tests/manual/probe-security-fixes.mjs
// 验证三个安全修复是否真的生效。
import fs from 'node:fs'

const B = 'http://127.0.0.1:8080'
const PW = 'E2E-fix-9911'
let cookie = ''

async function login() {
  const r = await fetch(B + '/admin/api/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ password: PW }),
  })
  const sc = r.headers.getSetCookie?.() ?? []
  cookie = sc.map(c => c.split(';')[0]).join('; ')
  return r.status
}

const auth = () => ({ 'content-type': 'application/json', cookie })

console.log('═══ 漏洞 1：公开检索不走向量路 ═══')
const t0 = Date.now()
const s1 = await fetch(B + '/api/public/kb/search?q=Scrap%20Cup')
const j1 = await s1.json()
const ms = Date.now() - t0
console.log('  HTTP ' + s1.status + '，返回 ' + j1.count + ' 条，耗时 ' + ms + 'ms')
console.log('  ' + (ms < 200 ? '✅ 毫秒级 —— 没走外部 API（向量化要 200ms+）' : '⚠️ 偏慢，可能仍走了向量路'))

console.log('')
console.log('═══ 漏洞 2：词条表（kb_term）并发写不丢数据 ═══')
console.log('  登录：HTTP ' + await login())
const N = 20
const t1 = Date.now()
await Promise.all(Array.from({ length: N }, (_, i) =>
  fetch(B + '/admin/api/kb/terms', {
    method: 'POST', headers: auth(),
    body: JSON.stringify({ en: 'ConcurrencyTest-' + i, zh: '并发测试' + i, status: 'draft' }),
  })))
const after = await (await fetch(B + '/admin/api/kb/terms?q=ConcurrencyTest&view=all&limit=500', { headers: auth() })).json()
console.log('  ' + N + ' 个并发写，耗时 ' + (Date.now() - t1) + 'ms')
console.log('  落库：' + after.total + ' 条')
console.log('  ' + (after.total === N ? '✅ 一条没丢' : '❌ 丢了 ' + (N - after.total) + ' 条'))

// 清理：新接口没有"删除词条"（行 = 这个页面在表里有位置），
// 只有 /kb/terms/clear（清中文名）。所以清理后行还在，只是查不到了。
await Promise.all(Array.from({ length: N }, (_, i) =>
  fetch(B + '/admin/api/kb/terms/clear', {
    method: 'POST', headers: auth(),
    body: JSON.stringify({ en: 'ConcurrencyTest-' + i }),
  })))
const left = await (await fetch(B + '/admin/api/kb/terms?q=ConcurrencyTest&view=all&limit=500', { headers: auth() })).json()
const leftNamed = (left.items ?? []).filter((t) => (t.zh || '').trim() !== '').length
console.log('  清理后：命中 ' + left.total + ' 行，其中仍有中文名的 ' + leftNamed + ' 条'
  + (leftNamed === 0 ? '（中文名已清空；行本身保留，这是新契约）' : '（⚠️ 有中文名没清掉）'))

console.log('')
console.log('═══ 漏洞 3：请求体大小限制 ═══')
const big = 'x'.repeat(200_000)
const s3 = await fetch(B + '/admin/api/login', {
  method: 'POST', headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ password: big }),
})
console.log('  200KB body → HTTP ' + s3.status)
console.log('  ' + ([400, 413].includes(s3.status) ? '✅ 被拒绝' : '⚠️ 状态 ' + s3.status))

console.log('')
console.log('═══ 附带：robots.txt ═══')
const s4 = await fetch(B + '/robots.txt')
const txt = await s4.text()
console.log('  HTTP ' + s4.status + '，Disallow ' + (txt.match(/Disallow/g) ?? []).length + ' 条')
