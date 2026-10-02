const B = 'http://127.0.0.1:8099'
const H = { 'content-type': 'application/json' }
let cookie = ''
async function login() {
  const r = await fetch(B + '/admin/api/login', { method: 'POST', headers: H, body: JSON.stringify({ password: 'E2E-p6-9911' }) })
  cookie = (r.headers.getSetCookie?.() ?? []).map(c => c.split(';')[0]).join('; ')
}
const AH = () => ({ 'content-type': 'application/json', cookie })
const aj = async (p) => (await fetch(B + '/admin/api' + p, { headers: AH() })).json()
const ppost = async (p, b) => { const r = await fetch(B + '/api/public' + p, { method: 'POST', headers: H, body: JSON.stringify(b) }); return { status: r.status, body: await r.json() } }
const pj = async (p) => (await fetch(B + '/api/public' + p, { headers: H })).json()

await login()
const ids = process.argv.slice(2).map(Number)
console.log('═══ 投票（模拟 2 人赞 + 2 人踩）═══')
for (const id of ids.slice(0, 2)) {
  await ppost('/plaza/vote', { statId: id, vote: 'up', clientId: 'voter-a-' + id + 'xyz' })
  await ppost('/plaza/vote', { statId: id, vote: 'down', clientId: 'voter-b-' + id + 'xyz' })
  await ppost('/plaza/vote', { statId: id, vote: 'down', clientId: 'voter-c-' + id + 'xyz' })
}
await ppost('/plaza/vote', { statId: ids[2], vote: 'up', clientId: 'voter-d-xyz12' })

console.log('')
console.log('═══ ★ 管理：被投票的内容（含未上架的）═══')
const ans = await aj('/plaza/answers?limit=50')
console.log('  条数:', ans.count)
for (const a of ans.answers) {
  console.log('    statId=' + a.statId, '👍' + a.up, '👎' + a.down, '🕐' + a.outdated, '| 群' + a.groupId, '用户' + a.userId)
  console.log('      Q: ' + String(a.question).slice(0, 36))
}

console.log('')
console.log('═══ ★ 管理：投票明细（谁投的）═══')
const v = await aj('/plaza/votes?statId=' + ids[0])
console.log('  statId=' + ids[0] + ' 的投票:', v.votes.length, '条')
for (const x of v.votes) {
  console.log('    ' + x.voterHash.slice(0, 12) + '…', x.vote, x.updatedAt.slice(11, 19))
}
console.log('  ' + (JSON.stringify(v).includes('100000005') ? '❌ 泄露了 QQ 号' : '✅ 只有 hash，无 QQ 号'))

console.log('')
console.log('═══ ★ 下架（清空投票 → 自动从公开站消失）═══')
const before = await pj('/plaza/keyword?keyword=' + encodeURIComponent('废料杯'))
console.log('  下架前公开站条数:', before.answers.length)
const td = await fetch(B + '/admin/api/plaza/takedown', { method: 'POST', headers: AH(), body: JSON.stringify({ statId: ids[2] }) })
console.log('  下架 statId=' + ids[2] + ' →', td.status, (await td.json()).note)
const after = await pj('/plaza/keyword?keyword=' + encodeURIComponent('卷毛山羊'))
console.log('  该关键词公开站条数:', after.answers.length, after.answers.length === 0 ? '✅ 已消失' : '❌ 还在')
