// tests/manual/setup-napcat-autologin.mjs
// 配置 NapCat 重启后自动登录 + 建立设备标识备份（安全网）
import fs from 'node:fs'
import crypto from 'node:crypto'

const BASE = process.env.NAPCAT_WEBUI ?? 'http://192.168.65.254:6099'
const cfgPath = '/app/workspace/qqbot/deploy/data/napcat/config/webui.json'
// 真实 QQ 号不进仓库：必须显式传 BOT_QQ，避免有人把真号写回代码
const BOT_QQ = process.env.BOT_QQ
if (!BOT_QQ) {
  console.error('缺少 BOT_QQ。用法：BOT_QQ=你的机器人QQ node tests/manual/setup-napcat-autologin.mjs')
  process.exit(1)
}
const token = JSON.parse(fs.readFileSync(cfgPath, 'utf8')).token
const hash = crypto.createHash('sha256').update(token + '.napcat').digest('hex')

const lr = await fetch(BASE + '/api/auth/login', { method:'POST', headers:{'content-type':'application/json'}, body: JSON.stringify({ hash }) })
const lj = await lr.json()
const cred = lj?.data?.Credential ?? lj?.Credential
if (!cred) { console.error('鉴权失败'); process.exit(1) }

async function api(path, body) {
  const r = await fetch(BASE + '/api' + path, {
    method:'POST', headers:{'content-type':'application/json', authorization:'Bearer ' + cred },
    body: JSON.stringify(body ?? {}), })
  const j = await r.json().catch(() => null)
  return { status: r.status, ok: (j?.code === 0 || j?.success === true || r.status === 200), data: j?.data ?? j }
}

console.log('=== 第 1 步：建立设备标识备份（安全网）===')
const bk = await api('/QQLogin/CreateLinuxMachineInfoBackup')
console.log('  创建备份：HTTP ' + bk.status + '  ' + JSON.stringify(bk.data).slice(0, 150))
const list = await api('/QQLogin/GetLinuxMachineInfoBackups')
console.log('  当前备份列表：' + JSON.stringify(list.data))

console.log('')
console.log('=== 第 2 步：配置重启后自动登录 ===')
const before = await api('/QQLogin/GetQuickLoginQQ')
console.log('  设置前：' + JSON.stringify(before.data))
const set = await api('/QQLogin/SetQuickLoginQQ', { uin: BOT_QQ })
console.log('  设置：HTTP ' + set.status + '  ' + JSON.stringify(set.data).slice(0, 150))
const after = await api('/QQLogin/GetQuickLoginQQ')
console.log('  设置后：' + JSON.stringify(after.data))

console.log('')
console.log('=== 第 3 步：确认配置文件已落盘 ===')
const cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'))
console.log('  webui.json 里的 autoLoginAccount = ' + JSON.stringify(cfg.autoLoginAccount))

console.log('')
console.log('=== 第 4 步：记下当前设备指纹（万一要恢复）===')
const mac = (await api('/QQLogin/GetLinuxMAC')).data
const mid = (await api('/QQLogin/GetLinuxMachineId')).data
const guid = (await api('/QQLogin/ComputeLinuxGUID')).data
console.log('  MAC         = ' + mac.mac)
console.log('  machine-id  = ' + mid.machineId)
console.log('  设备 GUID   = ' + guid.guid)
