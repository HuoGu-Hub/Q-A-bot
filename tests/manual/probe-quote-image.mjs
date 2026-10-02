// tests/manual/probe-quote-image.mjs
// 专项验证：① 引用文字 ② 自己发图 ③ SSRF 防护 ④ 引用纯图片消息（群里最常见的用法）
//
// 用法：先启动工程，再跑本脚本
//   java -jar server/target/qqbot-server-*.jar
//   node tests/manual/probe-quote-image.mjs
import http from 'node:http'

const API_PORT = 3000
const APP_URL = 'http://127.0.0.1:8080/onebot/event'
const BOT_QQ = 999999
const GROUP = 123456
const QUOTED_TEXT = '会议时间改到下午三点了'
const IMAGE_URL = 'https://www.python.org/static/img/python-logo.png'

const sent = []
let getMsgCalls = 0

// 假 NapCat：id=111 返回文字消息，id=222 返回【纯图片】消息
http.createServer((req, res) => {
  let body = ''
  req.on('data', (c) => (body += c))
  req.on('end', () => {
    const action = (req.url || '').replace(/^\//, '')
    let data = {}
    if (action === 'get_login_info') data = { user_id: BOT_QQ, nickname: '测试机器人' }
    else if (action === 'get_msg') {
      getMsgCalls++
      const req2 = JSON.parse(body || '{}')
      const wantImage = String(req2.message_id) === '222'
      console.log('    [假NapCat] get_msg(id=' + req2.message_id + ') → 返回' + (wantImage ? '【纯图片】消息' : '文字消息'))
      data = wantImage
        ? { message_id: 222, message_type: 'group', group_id: GROUP, user_id: 88888,
            raw_message: '[图片]',
            message: [{ type: 'image', data: { url: IMAGE_URL, file: IMAGE_URL } }],
            sender: { user_id: 88888, nickname: '发图的人' } }
        : { message_id: 111, message_type: 'group', group_id: GROUP, user_id: 88888,
            raw_message: QUOTED_TEXT,
            message: [{ type: 'text', data: { text: QUOTED_TEXT } }],
            sender: { user_id: 88888, nickname: '被引用的人' } }
    } else if (action === 'send_group_msg') {
      const p = JSON.parse(body || '{}')
      sent.push(p)
      console.log('    [发] ' + extract(p).slice(0, 75))
      data = { message_id: sent.length }
    }
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ status: 'ok', retcode: 0, data }))
  })
}).listen(API_PORT, () => console.log('[探针] 假 NapCat :' + API_PORT))

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
function extract(p) {
  const m = p.message
  if (typeof m === 'string') return m
  if (!Array.isArray(m)) return ''
  return m.filter((s) => s.type === 'text').map((s) => s.data.text).join('').trim()
}
const at = { type: 'at', data: { qq: String(BOT_QQ) } }
const txt = (t) => ({ type: 'text', data: { text: ' ' + t } })
function ev(userId, message, id) {
  return { post_type: 'message', message_type: 'group', sub_type: 'normal', self_id: BOT_QQ,
    message_id: id, group_id: GROUP, user_id: userId, time: Math.floor(Date.now() / 1000),
    sender: { user_id: userId, nickname: 'U' + userId, role: 'member' }, message }
}
async function waitReply(before, timeoutMs) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) { if (sent.length > before) return; await sleep(300) }
}
async function ask(userId, message, id) {
  const before = sent.length
  await fetch(APP_URL, { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify(ev(userId, message, id)) })
  await waitReply(before, 60000)
  return sent.length > before ? extract(sent[sent.length - 1]) : ''
}

async function main() {
  for (let i = 0; i < 40; i++) {
    try { const r = await fetch(APP_URL, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' }); if (r.status < 500) break } catch (e) {}
    await sleep(1000)
  }
  console.log('[探针] 业务层已就绪')
  const results = []

  console.log('')
  console.log('---- ① 引用【文字】消息 + 提问 ----')
  let r = await ask(1001, [at, { type: 'reply', data: { id: '111' } }, txt('引用的那句话说的是几点？只回答时间')], 9001)
  let ok = /三|3/.test(r)
  console.log('  → ' + r)
  console.log('  ' + (ok ? '✅ 引用文字成功' : '❌ 失败')); results.push(ok)

  console.log('')
  console.log('---- ② 自己发图片 + 提问 ----')
  r = await ask(1002, [at, { type: 'image', data: { url: IMAGE_URL } }, txt('这张图里是什么？一句话')], 9002)
  ok = /python|蛇|logo|标志/i.test(r)
  console.log('  → ' + r)
  console.log('  ' + (ok ? '✅ 自己发的图片被理解' : '❌ 失败')); results.push(ok)

  console.log('')
  console.log('---- ③ 引用【纯图片】消息 + 打字提问（群里最常见的用法）----')
  r = await ask(1003, [at, { type: 'reply', data: { id: '222' } }, txt('这张图里是什么？一句话')], 9003)
  ok = /python|蛇|logo|标志/i.test(r)
  console.log('  → ' + r)
  console.log('  ' + (ok ? '✅★ 被引用的图片成功传给模型' : '❌★ 还是拿不到被引用的图片')); results.push(ok)

  console.log('')
  console.log('---- ④ SSRF 防护：图片 URL 指向内网 ----')
  r = await ask(1004, [at, { type: 'image', data: { url: 'http://127.0.0.1:4000/secret.png' } }, txt('看看这张图')], 9004)
  ok = /下载不下来|看不了/.test(r)
  console.log('  → ' + r)
  console.log('  ' + (ok ? '✅ 内网地址被拒绝' : '❌ SSRF 防护可能失效')); results.push(ok)

  console.log('')
  console.log('================ 结果 ================')
  console.log(results.filter(Boolean).length + '/' + results.length + ' 项通过 ' + (results.every(Boolean) ? '✅' : '❌'))
  process.exit(results.every(Boolean) ? 0 : 1)
}

main().catch((e) => { console.error(e); process.exit(1) })
