// 验证日志系统的双数据源
const B = 'http://127.0.0.1:8080'
let cookie = ''

const r0 = await fetch(B + '/admin/api/login', {
  method: 'POST', headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ password: 'E2E-logs-9911' }),
})
cookie = (r0.headers.getSetCookie?.() ?? []).map(c => c.split(';')[0]).join('; ')
const H = { cookie }

const j = async (p) => (await fetch(B + '/admin/api' + p, { headers: H })).json()

console.log('═══ ① 日志系统状态 ═══')
const st = await j('/logs/status')
console.log('  启用        :', st.enabled)
console.log('  缓冲        :', st.bufferSize + '/' + st.capacity, '（累计写入 ' + st.totalWritten + '）')
console.log('  脱敏        :', st.maskSensitive)
console.log('  活跃流      :', st.activeStreams)
console.log('  NapCat 可用 :', st.napcatAvailable)
console.log('  NapCat 目录 :', st.napcatDir)

console.log('')
console.log('═══ ② 业务层日志（历史）═══')
const h = await j('/logs/history?limit=8&level=INFO')
console.log('  取到', h.entries.length, '条，currentSeq =', h.currentSeq)
for (const e of h.entries.slice(-5)) {
  console.log('   ', e.ts.slice(11, 19), e.level.padEnd(5), (e.logger || '').padEnd(28), e.message.slice(0, 52))
}

console.log('')
console.log('═══ ③ 脱敏验证（关键）═══')
// 造一条含敏感信息的日志：触发一次带 token 的错误
await fetch(B + '/admin/api/logs/history?limit=1', { headers: { 'x-test': 'x' } }).catch(() => {})
const h2 = await j('/logs/history?limit=2000&level=INFO')
const all = h2.entries.map(e => e.message + ' ' + (e.throwable ?? '')).join('\n')
const leaks = []
// token 应该是 16 位，日志里出现过 "已设置（16 位）" 这种（安全的）
const m1 = all.match(/Token[：:]\s*已设置（(\d+) 位）/g)
if (m1) console.log('  Token 只打了长度（安全）:', m1[0])
// 检查有没有完整的 QQ 号泄露
const qqMatch = all.match(/(?<![0-9A-Za-z_])\d{9,11}(?![0-9A-Za-z_])/g)
console.log('  含 9~11 位数字串的条数:', qqMatch ? qqMatch.length : 0, '（若被脱敏应形如 ***1234）')
const masked = all.match(/\*\*\*\d{4}/g)
console.log('  已脱敏标记 ***XXXX 出现:', masked ? masked.length : 0, '次')

console.log('')
console.log('═══ ④ NapCat 日志（另一数据源）═══')
const nf = await j('/logs/napcat/files')
console.log('  可用 :', nf.available)
console.log('  目录 :', nf.dir)
console.log('  文件 :', nf.files.length, '个')
for (const f of nf.files.slice(0, 3)) {
  console.log('    -', f.name, '(' + (f.size / 1024).toFixed(0) + ' KB)')
}
if (nf.files.length) {
  const tail = await j('/logs/napcat/tail?lines=120')
  console.log('  读到', tail.lines.length, '行，最后 3 行：')
  for (const l of tail.lines.slice(-3)) {
    console.log('   ', l.slice(0, 100))
  }
}
