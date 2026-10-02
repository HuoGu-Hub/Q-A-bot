// tests/manual/napcat-status.mjs
// NapCat 协议层健康检查：不启动业务层，纯诊断。
//
// 用法：
//   node tests/manual/napcat-status.mjs
//   NAPCAT_HOST=http://127.0.0.1:3000 node tests/manual/napcat-status.mjs
import fs from 'node:fs'
import path from 'node:path'

const CONFIG_DIR = process.env.NAPCAT_CONFIG_DIR ?? 'deploy/data/napcat/config'
const DEFAULT_HOST = process.env.NAPCAT_HOST ?? 'http://127.0.0.1:3000'

const mask = (v) => (v ? '已设置(' + String(v).length + '位)' : '空')
const on = (b) => (b ? '启用' : '未启用')

function findConfig() {
  if (!fs.existsSync(CONFIG_DIR)) return null
  const f = fs.readdirSync(CONFIG_DIR).filter((n) => n.startsWith('onebot11_') && n.endsWith('.json'))
  return f.length ? path.join(CONFIG_DIR, f[0]) : null
}

const cfgPath = findConfig()
console.log('========== 1. 配置文件 ==========')
if (!cfgPath) {
  console.log('  ✗ 未找到 onebot11_*.json —— OneBot 网络配置尚未创建')
  process.exit(1)
}
console.log('  ' + cfgPath)
const cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'))
if (!cfg.network) {
  console.log('  ⚠ 这是旧版扁平结构配置，本脚本按新版 network.* 结构解析')
  process.exit(1)
}

console.log('')
console.log('========== 2. 网络适配器（这是关键）==========')
const GROUPS = [
  ['httpServers',      'HTTP 服务端      → 业务层调它【发】消息', 'port'],
  ['httpSseServers',   'HTTP SSE 服务端  → 事件以 SSE 推给业务层', 'port'],
  ['httpClients',      'HTTP 上报        → 它把事件【推】给业务层', 'url'],
  ['websocketServers', '正向 WebSocket   → 业务层连它收发', 'port'],
  ['websocketClients', '反向 WebSocket   → 它连业务层收发（推荐）', 'url'],
]
let canCall = false
let canReceive = false
let callHost = DEFAULT_HOST

for (const [key, label, addrKey] of GROUPS) {
  const list = cfg.network[key] || []
  const enabled = list.filter((x) => x.enable)
  console.log('  ' + label)
  if (!list.length) { console.log('       （空）'); continue }
  for (const it of list) {
    const addr = it[addrKey] !== undefined ? String(it[addrKey]) : '-'
    console.log('       - ' + on(it.enable) + '  ' + (it.name || '') + '  ' + addrKey + '=' + addr +
                '  format=' + (it.messagePostFormat || '-') + '  token=' + mask(it.token))
    if (key === 'httpServers' && it.enable) {
      canCall = true
      callHost = 'http://' + (it.host === '0.0.0.0' ? '127.0.0.1' : (it.host || '127.0.0.1')) + ':' + it.port
    }
    if ((key === 'httpClients' || key === 'websocketClients') && it.enable) canReceive = true
  }
}

console.log('')
console.log('========== 3. 结论 ==========')
console.log('  业务层能否【调用】NapCat 发消息：' + (canCall ? '✅ 能' : '❌ 不能（httpServers 为空或未启用）'))
console.log('  NapCat 能否【推送】事件给业务层：' + (canReceive ? '✅ 能' : '❌ 不能（httpClients / websocketClients 为空或未启用）'))

if (!canCall) {
  console.log('')
  console.log('  → 需要在 NapCat WebUI【网络配置】里新建一个「HTTP 服务器」：')
  console.log('      enable 打开，端口 3000，消息格式 array，并设置一个 token')
  process.exit(1)
}

console.log('')
console.log('========== 4. 实测调用（' + callHost + '）==========')
const list = cfg.network.httpServers || []
const main = list.find((x) => x.enable) || {}
const headers = { 'content-type': 'application/json' }
if (main.token) headers.authorization = 'Bearer ' + main.token

async function call(action) {
  const res = await fetch(callHost + '/' + action, { method: 'POST', headers, body: '{}' })
  let json = null
  try { json = await res.json() } catch (e) { /* 非 JSON */ }
  return { httpStatus: res.status, json }
}

let healthy = false
try {
  const r = await call('get_login_info')
  if (r.json && r.json.status === 'ok') {
    const d = r.json.data || {}
    console.log('  ✅ get_login_info  HTTP ' + r.httpStatus + '   已登录：' + d.nickname + '（QQ ' + d.user_id + '）')
    healthy = true
  } else if (r.httpStatus === 401 || r.httpStatus === 403) {
    console.log('  ✗ HTTP ' + r.httpStatus + '  Token 与 NapCat 中配置的不一致')
  } else {
    console.log('  ✗ HTTP ' + r.httpStatus + '  ' + JSON.stringify(r.json))
  }
} catch (e) {
  console.log('  ✗ 连不上 ' + callHost + '：' + e.message)
}

console.log('')
console.log(healthy ? '结论：协议层运行正常 ✅' : '结论：协议层尚未就绪 ❌')
process.exit(healthy ? 0 : 1)
