// tests/manual/probe-napcat-login.mjs
// 通过 NapCat 的 WebUI API 查询登录状态 / 快速登录配置 / 设备标识
// 鉴权算法：hash = sha256(token + '.napcat')  →  POST /api/auth/login  →  拿 Credential
import fs from 'node:fs'
import crypto from 'node:crypto'

const BASE = process.env.NAPCAT_WEBUI ?? 'http://192.168.65.254:6099'
const cfgPath = process.env.WEBUI_JSON ?? '/app/workspace/qqbot/deploy/data/napcat/config/webui.json'
const token = JSON.parse(fs.readFileSync(cfgPath, 'utf8')).token

const hash = crypto.createHash('sha256').update(token + '.napcat').digest('hex')

const login = await fetch(BASE + '/api/auth/login', {
  method: 'POST', headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ hash }),
})
const loginJson = await login.json()
const cred = loginJson?.data?.Credential ?? loginJson?.Credential
if (!cred) { console.error('登录失败：' + JSON.stringify(loginJson).slice(0, 200)); process.exit(1) }
console.log('[鉴权] 拿到 WebUI 凭证 ✅')
console.log('')

async function api(path, body) {
  const r = await fetch(BASE + '/api' + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: 'Bearer ' + cred },
    body: JSON.stringify(body ?? {}),
  })
  const j = await r.json().catch(() => null)
  return { status: r.status, data: j?.data ?? j }
}

const show = (label, v) => console.log('  ' + label.padEnd(26) + JSON.stringify(v))

console.log('=== ① 登录状态 ===')
const st = await api('/QQLogin/CheckLoginStatus')
console.log('  HTTP ' + st.status + '  ' + JSON.stringify(st.data).slice(0, 200))

console.log('')
console.log('=== ② 快速登录配置（决定重启后能否自动登录）===')
const qq = await api('/QQLogin/GetQuickLoginQQ')
show('当前配置的快速登录 QQ', qq.data)

console.log('')
console.log('=== ③ 登录信息 ===')
const info = await api('/QQLogin/GetQQLoginInfo')
console.log('  ' + JSON.stringify(info.data).slice(0, 300))

console.log('')
console.log('=== ④ 设备标识（决定会不会被风控）===')
const mac = await api('/QQLogin/GetLinuxMAC')
show('machine-info 里的 MAC', mac.data)
const mid = await api('/QQLogin/GetLinuxMachineId')
show('容器 /etc/machine-id', mid.data)
const guid = await api('/QQLogin/ComputeLinuxGUID')
show('算出来的设备 GUID', guid.data)

console.log('')
console.log('=== ⑤ 平台信息 ===')
const plat = await api('/QQLogin/GetPlatformInfo')
console.log('  ' + JSON.stringify(plat.data).slice(0, 300))

console.log('')
console.log('=== ⑥ 设备标识的备份列表 ===')
const bks = await api('/QQLogin/GetLinuxMachineInfoBackups')
show('已有备份', bks.data)
