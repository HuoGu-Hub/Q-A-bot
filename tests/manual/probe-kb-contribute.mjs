// 知识库贡献全流程验证
const B = 'http://127.0.0.1:8097'
const H = { 'content-type': 'application/json' }
let cookie = ''
async function login() {
  const r = await fetch(B + '/admin/api/login', { method: 'POST', headers: H, body: JSON.stringify({ password: 'E2E-kb-9911' }) })
  cookie = (r.headers.getSetCookie?.() ?? []).map(c => c.split(';')[0]).join('; ')
}
const AH = () => ({ 'content-type': 'application/json', cookie })
const aj = async (p) => (await fetch(B + '/admin/api' + p, { headers: AH() })).json()
const apost = async (p, b) => { const r = await fetch(B + '/admin/api' + p, { method: 'POST', headers: AH(), body: JSON.stringify(b) }); return { status: r.status, body: await r.json() } }
const ppost = async (p, b) => { const r = await fetch(B + '/api/public' + p, { method: 'POST', headers: H, body: JSON.stringify(b) }); return { status: r.status, body: await r.json() } }
const pj = async (p) => (await fetch(B + '/api/public' + p, { headers: H })).json()

await login()
console.log('═══ ① 初始状态 ═══')
const ov0 = await aj('/kb/overview')
console.log('  索引:', ov0.indexChunks, '块 ×', ov0.indexDimensions, '维')
console.log('  模型:', ov0.embeddingModel, '（硬编码）')
console.log('  待审:', ov0.counts.pending, '| 待索引:', ov0.counts.approved, '| 已索引:', ov0.counts.indexed)
const chunksBefore = ov0.indexChunks

console.log('')
console.log('═══ ② 生成一次性邀请码 ═══')
const tok = await apost('/kb/tokens', { label: '测试一次性', validDays: 0, uses: 1 })
console.log('  生成:', tok.status, 'token =', String(tok.body.token).slice(0, 16) + '…')
console.log('  ' + tok.body.note)

console.log('')
console.log('═══ ③ 校验 token（不消耗）═══')
const v1 = await ppost('/kb/contribute/verify', { token: tok.body.token })
console.log('  第 1 次校验:', v1.body.ok ? '✅ 有效' : '❌ ' + v1.body.message)
const v2 = await ppost('/kb/contribute/verify', { token: tok.body.token })
console.log('  第 2 次校验:', v2.body.ok ? '✅ 仍然有效（校验不消耗）' : '❌ 被消耗了')

console.log('')
console.log('═══ ④ 提交资料（消耗 token）═══')
const sub = await ppost('/kb/contribute', {
  token: tok.body.token,
  title: '测试词条·示例专属',
  text: '这是一条用于验证增量索引的测试资料。\\n\\n它包含一些独特的关键词：喵喵验证码 ZXCVBNM，用于确认检索能命中新加的内容。\\n\\n如果这段文字能被搜到，说明增量索引工作正常。',
  url: 'https://example.com/test',
  submitter: '自动化测试',
})
console.log('  提交:', sub.status, sub.body.message ?? sub.body.error)
const contribId = sub.body.id

console.log('')
console.log('═══ ⑤ token 应该已被消耗 ═══')
const v3 = await ppost('/kb/contribute/verify', { token: tok.body.token })
console.log('  再校验:', v3.body.ok ? '❌ 还能用（一次性失效了）' : '✅ 已失效 ' + v3.body.message)
const sub2 = await ppost('/kb/contribute', { token: tok.body.token, title: '二次提交', text: '重复使用同一个一次性 token 应该被拒绝。' })
console.log('  重复提交:', sub2.status, sub2.body.error ?? '(竟然成功了)')

console.log('')
console.log('═══ ⑥ 审核前：不得出现在公开站 ═══')
const s1 = await pj('/kb/search?q=' + encodeURIComponent('ZXCVBNM'))
console.log('  搜 ZXCVBNM →', s1.count, '条', s1.count === 0 ? '✅ 未索引不可见' : '❌ 泄露了')

console.log('')
console.log('═══ ⑦ 管理端审核通过 ═══')
const rev = await apost('/kb/review', { id: contribId, approve: true, note: '' })
console.log('  审核:', rev.status, rev.body.message)
const ov1 = await aj('/kb/overview')
console.log('  现在 待索引:', ov1.counts.approved)

console.log('')
console.log('═══ ⑧ 审核后：仍然不得出现 ═══')
const s2 = await pj('/kb/search?q=' + encodeURIComponent('ZXCVBNM'))
console.log('  搜 ZXCVBNM →', s2.count, '条', s2.count === 0 ? '✅ 未索引仍不可见' : '❌ 提前泄露了')

console.log('')
console.log('═══ ⑨ ★ 建立增量索引 ═══')
const idx = await apost('/kb/index', {})
console.log('  结果:', idx.status)
console.log('  ', JSON.stringify(idx.body, null, 2).split('\n').join('\n   '))

console.log('')
console.log('═══ ⑩ ★ 索引后：立即可搜到 ═══')
const s3 = await pj('/kb/search?q=' + encodeURIComponent('ZXCVBNM'))
console.log('  搜 ZXCVBNM →', s3.count, '条', s3.count > 0 ? '✅ 增量索引生效！' : '❌ 没搜到')
if (s3.entries?.length) {
  console.log('    命中的是:', s3.entries[0].title)
}

console.log('')
console.log('═══ ⑪ 索引块数应该增加了 ═══')
const ov2 = await aj('/kb/overview')
console.log('  索引前:', chunksBefore, '→ 现在:', ov2.indexChunks) 
console.log('  ' + (ov2.indexChunks > chunksBefore ? '✅ 增加了 ' + (ov2.indexChunks - chunksBefore) + ' 块' : '❌ 没变'))
