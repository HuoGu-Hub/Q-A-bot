// tests/manual/loadtest-concurrency.mjs
// 并发压测：模拟「50 个人在 1 分钟内同时 @ 机器人」，观察真实表现。
//
// 两个假服务：
//   3000  假 NapCat（记录每条回复的到达时间）
//   4000  假大模型（OpenAI 兼容，可配置延迟，零成本）
//
// 用法：
//   node tests/manual/loadtest-concurrency.mjs [人数] [模型延迟ms]
// 例：node tests/manual/loadtest-concurrency.mjs 50 3000
import http from 'node:http'

const USERS = Number(process.argv[2] ?? 50)
const LLM_DELAY = Number(process.argv[3] ?? 3000)
const API_PORT = 3000
const LLM_PORT = 4000
const APP_URL = 'http://127.0.0.1:8080/onebot/event'
const BOT_QQ = 999999
const GROUPS = [123456, 222333]

const sent = []
let llmCalls = 0
let llmPeak = 0
let llmNow = 0
const startAt = Date.now()
const rel = () => ((Date.now() - startAt) / 1000).toFixed(1) + 's'

// 假 NapCat
http.createServer((req, res) => {
  let body = ''
  req.on('data', (c) => (body += c))
  req.on('end', () => {
    const action = (req.url || '').replace(/^\//, '')
    let data = {}
    if (action === 'get_login_info') data = { user_id: BOT_QQ, nickname: '压测机器人' }
    else if (action === 'send_group_msg') {
      const p = JSON.parse(body || '{}')
      sent.push({ groupId: p.group_id, at: Date.now(), text: extract(p) })
      data = { message_id: sent.length }
    }
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ status: 'ok', retcode: 0, data }))
  })
}).listen(API_PORT)

// 假大模型（慢）
http.createServer((req, res) => {
  req.on('data', () => {})
  req.on('end', () => {
    llmCalls++; llmNow++; if (llmNow > llmPeak) llmPeak = llmNow
    setTimeout(() => {
      llmNow--
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ id: 'x', object: 'chat.completion', created: 0, model: 'fake',
        choices: [{ index: 0, message: { role: 'assistant', content: '压测回复' }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 100, completion_tokens: 10, total_tokens: 110 } }))
    }, LLM_DELAY)
  })
}).listen(LLM_PORT)

function extract(p) {
  const m = p.message
  if (typeof m === 'string') return m
  if (!Array.isArray(m)) return ''
  return m.filter((s) => s.type === 'text').map((s) => s.data.text).join('')
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const at = { type: 'at', data: { qq: String(BOT_QQ) } }

async function main() {
  console.log('[压测] 假 NapCat :' + API_PORT + '   假大模型 :' + LLM_PORT + ' (每次延迟 ' + LLM_DELAY + 'ms)')
  for (let i = 0; i < 40; i++) {
    try { const r = await fetch(APP_URL, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' }); if (r.status < 500) break } catch (e) {}
    await sleep(1000)
  }
  console.log('[压测] 业务层就绪，开始注入 ' + USERS + ' 个人（分散在 ' + GROUPS.length + ' 个群）')
  console.log('')

  const t0 = Date.now()
  const tasks = []
  for (let i = 0; i < USERS; i++) {
    const g = GROUPS[i % GROUPS.length]
    tasks.push(fetch(APP_URL, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        post_type: 'message', message_type: 'group', sub_type: 'normal', self_id: BOT_QQ,
        message_id: 10000 + i, group_id: g, user_id: 50000 + i,
        time: Math.floor(Date.now() / 1000),
        sender: { user_id: 50000 + i, nickname: 'U' + i, role: 'member' },
        message: [at, { type: 'text', data: { text: ' 你好，帮我看看这个问题' } }],
      }),
    }).then((r) => r.status).catch(() => 'ERR'))
  }
  const statuses = await Promise.all(tasks)
  const injectMs = Date.now() - t0
  console.log('[压测] ' + USERS + ' 条事件注入完成，耗时 ' + injectMs + ' ms')
  console.log('[压测] 上报响应码分布：' + JSON.stringify(statuses.reduce((a, s) => (a[s] = (a[s] || 0) + 1, a), {})))
  console.log('')

  // 等所有回复落地（最多 120 秒）
  let lastCount = -1, stable = 0
  for (let i = 0; i < 240; i++) {
    await sleep(500)
    if (sent.length === lastCount) { stable++; if (stable > 12) break } else stable = 0
    lastCount = sent.length
  }

  console.log('================ 结果 ================')
  console.log('模型调用次数 ：' + llmCalls)
  console.log('模型并发峰值 ：' + llmPeak)
  console.log('实际回复条数 ：' + sent.length)
  for (const g of GROUPS) {
    const arr = sent.filter((s) => s.groupId === g)
    const notifies = arr.filter((s) => s.text.includes('忙不过来') || s.text.includes('别急')).length
    const real = arr.length - notifies
    console.log('  群 ' + g + '：真实回复 ' + real + ' 条，提示 ' + notifies + ' 条')
  }
  console.log('')
  const lat = sent.map((s) => s.at - t0).sort((a, b) => a - b)
  if (lat.length) {
    const p = (q) => (lat[Math.min(lat.length - 1, Math.floor(lat.length * q))] / 1000).toFixed(1) + 's'
    console.log('从注入开始到回复落地的延迟分布：')
    console.log('  最快 ' + (lat[0] / 1000).toFixed(1) + 's   中位 ' + p(0.5) + '   P90 ' + p(0.9) + '   最慢 ' + (lat[lat.length - 1] / 1000).toFixed(1) + 's')
  }
  process.exit(0)
}

main().catch((e) => { console.error(e); process.exit(1) })
