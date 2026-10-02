// 第二阶段：验证 点赞 → 上架 → 降级
const B = 'http://127.0.0.1:8099'
const H = { 'content-type': 'application/json' }
const j = async (p) => (await fetch(B + p, { headers: H })).json()
const post = async (p, body) => {
  const r = await fetch(B + p, { method: 'POST', headers: H, body: JSON.stringify(body) })
  return { status: r.status, body: await r.json() }
}

console.log('═══ ① 点赞前：答案不上架（隐私保护生效）═══')
let page = await j('/api/public/plaza/keyword?keyword=' + encodeURIComponent('废料杯'))
console.log('  answers:', page.answers.length, page.answers.length === 0 ? '✅ 没人赞过就不公开' : '❌')

console.log('')
console.log('═══ ② 点赞 2 条（模拟两个群友认可）═══')
const ids = [1, 2, 3]
for (const [i, id] of ids.entries()) {
  if (i < 2) {
    const r = await post('/api/public/plaza/vote', { statId: id, vote: 'up', clientId: 'voter-' + i + '-abcdefgh' })
    console.log('  statId=' + id + ' 点赞 →', JSON.stringify(r.body))
  }
}

console.log('')
console.log('═══ ③ 点赞后：答案上架 ═══')
page = await j('/api/public/plaza/keyword?keyword=' + encodeURIComponent('废料杯'))
console.log('  answers:', page.answers.length)
for (const a of page.answers) {
  console.log('    statId=' + a.statId, '👍' + a.up, 'badge=' + (a.badge ?? '-'))
  console.log('      ' + String(a.answer).slice(0, 45))
}

console.log('')
console.log('═══ ④ 关键词列表也出现了 ═══')
const kws = await j('/api/public/plaza/keywords')
console.log('  关键词数:', kws.count)
for (const k of kws.keywords) {
  console.log('    ' + k.keyword, '(' + k.termEn + ')', '被问' + k.count + '次', '获赞答案' + k.votedCount + '条')
}

console.log('')
console.log('═══ ⑤ 降级触发：把两条都踩到阈值 ═══')
// 每条踩 2 次（阈值=2），且踩>赞
for (const id of [1, 2]) {
  for (const v of ['d1', 'd2']) {
    await post('/api/public/plaza/vote', { statId: id, vote: 'down', clientId: 'downvoter-' + v + '-xyzabc' })
  }
}
page = await j('/api/public/plaza/keyword?keyword=' + encodeURIComponent('废料杯'))
console.log('  各答案票数：')
for (const a of page.answers) {
  console.log('    statId=' + a.statId, '👍' + a.up, '👎' + a.down)
}
console.log('  needsNewAnswer:', page.needsNewAnswer, page.needsNewAnswer ? '✅ 该降级了' : '（还没到阈值）')

console.log('')
console.log('═══ ⑥ 去重验证（三条回答内容相似）═══')
console.log('  原始 3 条 → 上架 ' + page.answers.length + ' 条（相似度>0.9 会合并）')
