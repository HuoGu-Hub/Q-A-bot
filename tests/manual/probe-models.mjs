// tests/manual/probe-models.mjs
// 验证模型配置：脱敏 / 测试连通性 / 热生效 / 失败回绝

const B = 'http://127.0.0.1:8080'
let cookie = ''
async function login() {
  const r = await fetch(B + '/admin/api/login', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ password: 'E2E-m-9911' }),
  })
  cookie = (r.headers.getSetCookie?.() ?? []).map(c => c.split(';')[0]).join('; ')
}
const H = () => ({ 'content-type': 'application/json', cookie })
const j = async (p) => (await fetch(B + '/admin/api' + p, { headers: H() })).json()

await login()

console.log('═══ ① 当前模型列表 ═══')
const list = await j('/models')
for (const p of list.providers) {
  console.log('  ' + p.name.padEnd(14) + ' model=' + p.modelName.padEnd(24) + ' key=' + (p.apiKeyMasked || '(未配置)'))
}

console.log('')
console.log('═══ ② 脱敏检查（key 不能出现在响应里）═══')
const raw = JSON.stringify(list)
const leak = /sk-[a-zA-Z0-9]{20,}/.test(raw)
console.log('  ' + (leak ? '❌ 有完整 key 泄露！' : '✅ 无完整 key'))

console.log('')
console.log('═══ ③ 测试连通性（当前模型）═══')
const t1 = await fetch(B + '/admin/api/models/test', {
  method: 'POST', headers: H(),
  body: JSON.stringify({ provider: 'opencode-go', modelName: 'deepseek-v4.1-flash' }),
}).then(r => r.json())
console.log('  ok=' + t1.ok + '  耗时=' + t1.elapsedMs + 'ms')
if (t1.ok) console.log('  模型回复：' + t1.reply)
else console.log('  错误：' + t1.error)

console.log('')
console.log('═══ ④ ★ 测试一个不存在的模型（应当失败）═══')
const t2 = await fetch(B + '/admin/api/models/test', {
  method: 'POST', headers: H(),
  body: JSON.stringify({ provider: 'opencode-go', modelName: 'no-such-model-xyz' }),
}).then(r => r.json())
console.log('  ok=' + t2.ok)
console.log('  ' + (t2.ok ? '❌ 不存在的模型居然通过了' : '✅ 正确识别为不可用'))
if (!t2.ok) console.log('  错误信息：' + String(t2.error).slice(0, 100))

console.log('')
console.log('═══ ⑤ ★ 保存不存在的模型（verify=true 应被拒绝）═══')
const r5 = await fetch(B + '/admin/api/models', {
  method: 'POST', headers: H(),
  body: JSON.stringify({ provider: 'opencode-go', modelName: 'no-such-model-xyz', verify: true }),
})
const b5 = await r5.json()
console.log('  HTTP ' + r5.status)
console.log('  ' + (r5.status === 400 ? '✅ 被拒绝（先测再换生效）' : '❌ 竟然保存了'))
console.log('  ' + (b5.error ?? ''))
const after5 = await j('/models')
const m5 = after5.providers.find(p => p.name === 'opencode-go')
console.log('  模型 ID 仍是：' + m5.modelName + '  ' + (m5.modelName === 'deepseek-v4.1-flash' ? '✅ 未被改坏' : '❌ 被改了'))

console.log('')
console.log('═══ ⑥ 拉取可用模型列表 ═══')
const av = await j('/models/available?provider=opencode-go')
console.log('  ok=' + av.ok + '  数量=' + (av.count ?? 0))
if (av.ok && av.models.length) {
  console.log('  前 8 个：' + av.models.slice(0, 8).join(', '))
} else {
  console.log('  ' + (av.error ?? ''))
}

console.log('')
console.log('═══ ⑦ ★ 热生效：切到另一个模型再切回来 ═══')
const before = (await j('/models')).providers.find(p => p.name === 'opencode-go').modelName
console.log('  当前：' + before)

const target = 'deepseek-v4-flash'
const r7 = await fetch(B + '/admin/api/models', {
  method: 'POST', headers: H(),
  body: JSON.stringify({ provider: 'opencode-go', modelName: target, verify: true }),
})
const b7 = await r7.json()
console.log('  切换到 ' + target + ' → HTTP ' + r7.status)
if (r7.ok) {
  console.log('    ' + JSON.stringify(b7))
  const now = (await j('/models')).providers.find(p => p.name === 'opencode-go').modelName
  console.log('    接口读回：' + now + '  ' + (now === target ? '✅ 已生效' : '❌ 没变'))
  // 切回来
  await fetch(B + '/admin/api/models', {
    method: 'POST', headers: H(),
    body: JSON.stringify({ provider: 'opencode-go', modelName: before, verify: true }),
  })
  const back = (await j('/models')).providers.find(p => p.name === 'opencode-go').modelName
  console.log('    切回：' + back + '  ' + (back === before ? '✅ 已还原' : '❌ 还原失败'))
} else {
  console.log('    ' + (b7.error ?? '失败'))
}
