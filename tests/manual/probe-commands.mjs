// tests/manual/probe-commands.mjs
// 验证指令系统 + 配置热生效

const B = 'http://127.0.0.1:8080'
let cookie = ''

async function login() {
  const r = await fetch(B + '/admin/api/login', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ password: 'E2E-cmd-9911' }),
  })
  cookie = (r.headers.getSetCookie?.() ?? []).map(c => c.split(';')[0]).join('; ')
}
const H = () => ({ 'content-type': 'application/json', cookie })
const j = async (p) => (await fetch(B + '/admin/api' + p, { headers: H() })).json()

await login()

console.log('═══ ① 指令列表（内置 4 条）═══')
const list = await j('/commands')
console.log('  available:', list.available, '| 指令数:', list.commands.length)
console.log('  requireMention:', list.requireMention, '| allowUserIds:', list.allowUserIds)
for (const c of list.commands) {
  console.log('   /' + c.trigger.padEnd(8), c.builtin ? '[内置]' : '      ', c.description)
}

console.log('')
console.log('═══ ② 变量系统 ═══')
const vars = await j('/commands/variables')
console.log('  可用变量', vars.variables.length, '个（高级变量', vars.variables.filter(v => v.advanced).length, '个）')
for (const v of vars.variables.slice(0, 6)) {
  console.log('   {' + v.name.padEnd(11) + '}', v.label.padEnd(14), '例：' + v.example)
}

console.log('')
console.log('═══ ③ 预览渲染（{cmd.list} 是 /help 的实现）═══')
const p1 = await fetch(B + '/admin/api/commands/preview', {
  method: 'POST', headers: H(),
  body: JSON.stringify({ template: '{cmd.list}' }),
}).then(r => r.json())
console.log('  渲染结果：')
for (const line of p1.rendered.split(String.fromCharCode(10))) {
  console.log('    ' + line)
}

console.log('')
console.log('═══ ④ 新建一条指令 ═══')
const save = await fetch(B + '/admin/api/commands', {
  method: 'POST', headers: H(),
  body: JSON.stringify({
    trigger: 'hello',
    reply: '{user} 你好呀～现在是 {time}，我在线 {uptime}。知识库有 {kb.count} 条资料。',
    description: '打个招呼',
    scope: 'all',
  }),
}).then(r => r.json())
console.log('  保存:', JSON.stringify(save))

console.log('')
console.log('═══ ⑤ 校验：非法触发词应被拒 ═══')
for (const t of ['含 空格', '/slash', '']) {
  const r = await fetch(B + '/admin/api/commands', {
    method: 'POST', headers: H(),
    body: JSON.stringify({ trigger: t, reply: 'x' }),
  })
  const b = await r.json()
  console.log('  trigger=' + JSON.stringify(t).padEnd(12), 'HTTP', r.status, b.error ?? '')
}

console.log('')
console.log('═══ ⑥ 内置指令不能删 ═══')
const del = await fetch(B + '/admin/api/commands/delete', {
  method: 'POST', headers: H(), body: JSON.stringify({ trigger: 'help' }),
})
console.log('  删 /help → HTTP', del.status, (await del.json()).error ?? '')

console.log('')
console.log('═══ ⑦ ★ 配置热生效 ═══')
const before = await j('/settings')
const rateItem = Object.values(before.groups).flat().find(x => x.key === 'app.guard.rate-limit.per-user-per-minute')
console.log('  改前 per-user-per-minute =', rateItem?.value)

const applied = await fetch(B + '/admin/api/settings', {
  method: 'POST', headers: H(),
  body: JSON.stringify({ changes: { 'app.guard.rate-limit.per-user-per-minute': 3 } }),
}).then(r => r.json())
console.log('  保存结果:', JSON.stringify(applied))

const after = await j('/settings')
const rateAfter = Object.values(after.groups).flat().find(x => x.key === 'app.guard.rate-limit.per-user-per-minute')
console.log('  改后（未重启）=', rateAfter?.value)
console.log('  ' + (rateAfter?.value === 3 ? '✅ 热生效成功' : '❌ 没生效'))

console.log('')
console.log('═══ ⑧ ★ 白名单：禁改项应被拒 ═══')
const bad = await fetch(B + '/admin/api/settings', {
  method: 'POST', headers: H(),
  body: JSON.stringify({ changes: { 'app.persistence.db': '/tmp/hacked.sqlite', 'app.admin.password': 'hacked' } }),
}).then(r => r.json())
console.log('  rejected:', JSON.stringify(bad.rejected))
console.log('  ' + (bad.rejected?.length === 2 ? '✅ 两项都被拒绝' : '❌ 有漏网'))
