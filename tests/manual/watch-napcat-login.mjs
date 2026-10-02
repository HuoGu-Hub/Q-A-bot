// tests/manual/watch-napcat-login.mjs
// 观察 NapCat 重启后的登录过程：每 3 秒查一次状态，直到登录完成或超时
// 用法：node tests/manual/watch-napcat-login.mjs   （在重启 NapCat 前后跑都行）
import fs from 'node:fs'
import crypto from 'node:crypto'

const BASE = process.env.NAPCAT_WEBUI ?? 'http://192.168.65.254:6099'
const cfgPath = '/app/workspace/qqbot/deploy/data/napcat/config/webui.json'
const TIMEOUT_MS = Number(process.env.TIMEOUT_MS ?? 180000)
const token = JSON.parse(fs.readFileSync(cfgPath, 'utf8')).token
const hash = crypto.createHash('sha256').update(token + '.napcat').digest('hex')

async function getCred() {
  const r = await fetch(BASE + '/api/auth/login', { method:'POST', headers:{'content-type':'application/json'}, body: JSON.stringify({ hash }) })
  const j = await r.json()
  return j?.data?.Credential ?? j?.Credential
}

async function status(cred) {
  const r = await fetch(BASE + '/api/QQLogin/CheckLoginStatus', {
    method:'POST', headers:{'content-type':'application/json', authorization:'Bearer ' + cred}, body:'{}' })
  return (await r.json()).data
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms))
const t0 = Date.now()
console.log('[观察] 开始监控登录状态（最长 ' + (TIMEOUT_MS/1000) + ' 秒）')
console.log('[观察] 现在去执行重启：docker compose restart napcat')
console.log('')

let last = ''
while (Date.now() - t0 < TIMEOUT_MS) {
  try {
    const cred = await getCred()
    const s = await status(cred)
    const line = 'loginPhase=' + s.loginPhase + '  isLogin=' + s.isLogin + '  coreReady=' + s.coreReady + '  isOffline=' + s.isOffline
    if (line !== last) {
      const t = ((Date.now() - t0) / 1000).toFixed(1) + 's'
      console.log('  [' + t.padStart(7) + '] ' + line + (s.loginError ? '   错误：' + s.loginError : ''))
      last = line
    }
    if (s.isLogin && s.coreReady && !s.isOffline) {
      console.log('')
      console.log('✅ 登录完成（用时 ' + ((Date.now()-t0)/1000).toFixed(1) + ' 秒）')
      process.exit(0)
    }
  } catch (e) {
    const t = ((Date.now() - t0) / 1000).toFixed(1) + 's'
    if (last !== 'DOWN') { console.log('  [' + t.padStart(7) + '] 服务不可达（正在重启中…）'); last = 'DOWN' }
  }
  await sleep(3000)
}
console.log('')
console.log('⏱ 超时：' + (TIMEOUT_MS/1000) + ' 秒内没登录成功。去看 WebUI 是不是在等二维码。')
process.exit(1)
