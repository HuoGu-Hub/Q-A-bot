// tests/manual/probe-plaza.mjs —— 问答广场端到端验证
const B = 'http://127.0.0.1:8099'
const H = { 'content-type': 'application/json' }
const j = async (p) => (await fetch(B + p, { headers: H })).json()
const post = async (p, body) => {
  const r = await fetch(B + p, { method: 'POST', headers: H, body: JSON.stringify(body) })
  return { status: r.status, body: await r.json() }
}

console.log('═══ ① 空库时的广场（冷启动）═══')
const k0 = await j('/api/public/plaza/keywords')
console.log('  关键词数:', k0.count, '（冷启动应为 0 —— 没有点赞过的内容不上架）')

console.log('')
console.log('═══ ② 直接造数据（模拟已有问答 + 点赞）═══')
// 通过 SQL 造：3 条关于「废料杯」的问答
console.log('  （由 shell 侧预置，见下方 SQL 输出）')

console.log('')
console.log('═══ ③ 关键词页 ═══')
const page = await j('/api/public/plaza/keyword?keyword=' + encodeURIComponent('废料杯'))
console.log('  keyword:', page.keyword)
console.log('  askedCount:', page.askedCount)
console.log('  answers:', page.answers?.length ?? 0)
for (const a of page.answers ?? []) {
  console.log('    statId=' + a.statId, '👍' + a.up, '👎' + a.down, 'badge=' + (a.badge ?? '-'))
  console.log('      ' + String(a.answer).slice(0, 50))
}

console.log('')
console.log('═══ ④ 投票 + 防重复投票 ═══')
if (page.answers?.length) {
  const id = page.answers[0].statId
  const cid = 'test-client-abcdefgh'
  const v1 = await post('/api/public/plaza/vote', { statId: id, vote: 'up', clientId: cid })
  console.log('  第 1 次点赞:', JSON.stringify(v1.body))
  const v2 = await post('/api/public/plaza/vote', { statId: id, vote: 'up', clientId: cid })
  console.log('  同一人再点:', JSON.stringify(v2.body))
  console.log('  ' + (v2.body.up === v1.body.up ? '✅ 票数没涨（防刷生效）' : '❌ 被刷了'))
  const v3 = await post('/api/public/plaza/vote', { statId: id, vote: 'down', clientId: cid })
  console.log('  同一人改票:', JSON.stringify(v3.body))
  console.log('  ' + (v3.body.down === 1 && v3.body.up === 0 ? '✅ 改票生效' : '❌ 改票异常'))
  // 另一个 clientId
  const v4 = await post('/api/public/plaza/vote', { statId: id, vote: 'up', clientId: 'another-client-xyz' })
  console.log('  换个人点赞:', JSON.stringify(v4.body))
} else {
  console.log('  （没有答案可投，跳过）')
}

console.log('')
console.log('═══ ⑤ 参数校验 ═══')
const bad1 = await post('/api/public/plaza/vote', { statId: 1, vote: 'xxx', clientId: 'abcdefgh' })
console.log('  非法 vote →', bad1.status, bad1.body.error)
const bad2 = await post('/api/public/plaza/vote', { statId: 1, vote: 'up' })
console.log('  缺 clientId →', bad2.status, bad2.body.error)

console.log('')
console.log('═══ ⑥ 隐私检查（公开接口不能有 QQ 号/群号）═══')
const raw = JSON.stringify(page)
const leaks = ['userId', 'groupId', 'user_id', 'group_id', 'voterHash'].filter(k => raw.includes(k))
console.log('  ' + (leaks.length ? '❌ 发现隐私字段: ' + leaks.join(',') : '✅ 无隐私字段'))

console.log('')
console.log('═══ ⑦ 降级：问问新答案（限额）═══')
const a1 = await post('/api/public/plaza/ask-new', { keyword: '废料杯' })
console.log('  第 1 次:', a1.status, a1.body.ok ? '生成成功，' + String(a1.body.answer).length + ' 字' : a1.body.error)
