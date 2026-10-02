// tests/manual/probe-kb-e2e.mjs
// 知识库端到端验证：假 NapCat + 真实业务层 + 真实大模型。
// 发几个中文游戏问题，看机器人是不是真的"查了资料再回答"。
//
// 用法（两步，强烈建议指向【隔离实例】）：
//   1) 起一个独立库 + 独立端口的业务层，别用正在服务的生产实例：
//        cd server && mvn spring-boot:run \
//          -Dspring-boot.run.arguments="--app.qa.db=./data/qa/kb-e2e.sqlite --server.port=8099"
//   2) 起本探针（它自己占 3000 端口当假 NapCat），并指向上面那个实例：
//        APP_URL=http://127.0.0.1:8099/onebot/event node tests/manual/probe-kb-e2e.mjs
//   3) 跑完直接删掉隔离库，不留痕迹。
//
// ⚠️ 为什么必须用隔离实例：
//   本探针刻意「每条用不同用户 + 不同群」来绕开限流（见 main 里的注释），
//   一次运行会造出 5 个假群号。如果指向正在服务的生产实例，这些行会永久
//   写进它的 qa_stat，把后台的「独立群 / 独立用户 / 实际作答」和关键词榜
//   整体抬高 —— 2026-09-22 的一次运行就这么留下了 10 行残留：
//   真实群 3 个被算成 8 个、实际作答 62 被算成 72。
//
//   万一已经污染了生产库，用这个脚本清理：
//     tests/cleanup-sql/cleanup-probe-test-data.sql
import http from 'node:http'

const API_PORT = 3000

// 默认仍回落到本机 8080，方便随手跑；但生产实例通常就在那里，
// 所以下面会明确警告。要跑就显式指定 APP_URL。
const APP_URL = process.env.APP_URL ?? 'http://127.0.0.1:8080/onebot/event'
const USING_DEFAULT_TARGET = !process.env.APP_URL

const BOT_QQ = 100000003

// 假群号基数：700000 + i。这个范围同时写死在清理 SQL 里
// （tests/cleanup-sql/cleanup-probe-test-data.sql），改这里就要同步改那边。
const TEST_GROUP_BASE = 700000

const sent = []

http.createServer((req, res) => {
  let body = ''
  req.on('data', (c) => (body += c))
  req.on('end', () => {
    const action = (req.url || '').replace(/^\//, '')
    let data = {}
    if (action === 'get_login_info') {
      data = { user_id: BOT_QQ, nickname: '示例助手' }
    } else if (action === 'send_group_msg') {
      const p = JSON.parse(body || '{}')
      sent.push({ group: p.group_id, text: extract(p) })
      console.log('  [回复] ' + extract(p).slice(0, 220).replace(/\n/g, ' / '))
      data = { message_id: sent.length }
    } else if (action === 'get_msg') {
      data = { message_id: 1, message: [], raw_message: '' }
    }
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ status: 'ok', retcode: 0, data }))
  })
}).listen(API_PORT, () => console.log('[假NapCat] 已监听 :' + API_PORT))

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
function extract(p) {
  const m = p.message
  if (typeof m === 'string') return m
  if (!Array.isArray(m)) return ''
  return m.filter((s) => s.type === 'text').map((s) => s.data.text).join('').trim()
}
const at = { type: 'at', data: { qq: String(BOT_QQ) } }
const txt = (t) => ({ type: 'text', data: { text: ' ' + t } })
function ev(userId, groupId, message, id) {
  return { post_type: 'message', message_type: 'group', sub_type: 'normal', self_id: BOT_QQ,
    message_id: id, group_id: groupId, user_id: userId, time: Math.floor(Date.now() / 1000),
    sender: { user_id: userId, nickname: 'U' + userId, role: 'member' }, message }
}

const CASES = [
  '废料杯怎么合成？',
  '卷毛山羊怎么驯服？',
  '爆炸箭 III 是什么？',
  '灵火祭坛是干什么的？',
  '今天天气怎么样？',
]

async function main() {
  // 业务层要等 Maven 编译 + Spring 启动，给它最多 180 秒
  let ready = false
  for (let i = 0; i < 180; i++) {
    try {
      const r = await fetch(APP_URL, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' })
      if (r.status < 500) { ready = true; break }
    } catch (e) { /* 还没起来 */ }
    await sleep(1000)
  }
  if (!ready) {
    console.log('[探针] 业务层 180 秒内没起来，放弃')
    process.exit(1)
  }
  console.log('[探针] 业务层已就绪，开始提问')
  console.log('  目标：' + APP_URL)
  if (USING_DEFAULT_TARGET) {
    console.log('')
    console.log('  ⚠️  你用的是默认目标（没设 APP_URL）。')
    console.log('      如果 8080 上跑的是【正在服务的生产实例】，本次会在它的 qa_stat')
    console.log('      里永久留下 5 个假群号，把后台统计整体抬高。')
    console.log('      更稳的做法：起隔离实例（独立库 + 独立端口）后显式指定 APP_URL。')
    console.log('')
  }
  console.log('')

  for (let i = 0; i < CASES.length; i++) {
    const before = sent.length
    // 每条用不同用户 + 不同群，避开「每人每分钟 1 次 / 每群每分钟 10 条」的限流。
    // 代价：一次运行会造出 CASES.length 个假群号 —— 这正是必须指向隔离实例的原因。
    try {
      await fetch(APP_URL, { method: 'POST', headers: { 'content-type': 'application/json' },
        body: JSON.stringify(ev(2000 + i, TEST_GROUP_BASE + i, [at, txt(CASES[i])], 9000 + i)) })
    } catch (e) {
      console.log('  [错误] 投递失败：' + e.message)
      continue
    }
    console.log('[' + (i + 1) + '] 问：' + CASES[i])
    const t0 = Date.now()
    while (sent.length === before && Date.now() - t0 < 90000) await sleep(500)
    if (sent.length === before) console.log('  [回复] （超时无回复）')
    console.log('')
  }
  console.log('================ 结束 ================')
  console.log('')
  console.log(`本次向目标实例写入了 ${CASES.length} 条测试问答（假群号 ${TEST_GROUP_BASE}~${TEST_GROUP_BASE + CASES.length - 1}）。`)
  // ⚠️ 无条件提醒，不只在"用了默认目标"时提醒：显式设了 APP_URL 也可能正指着生产。
  //    生产库上留下这些行的后果实测过：数据大屏的「提问用户 / 群」会凭空多出 5 个群
  //    （8 个群里有 5 个是这里的假群号），关键词榜也会混进假热词。
  console.log('')
  console.log('请如实处理这次写入：')
  console.log('  · 指向的是临时隔离库 → 直接删掉那个库文件即可；')
  console.log('  · 指向的是生产实例   → 在**宿主机**上跑清理（备份后执行，不用停服务）：')
  console.log('      cp server/data/qa/qqbot.sqlite server/data/qa/qqbot-before-cleanup.sqlite')
  console.log('      sqlite3 server/data/qa/qqbot.sqlite < tests/cleanup-sql/cleanup-probe-test-data.sql')
  console.log('    （别在容器里跑：工作区是 9p 挂载，WAL 起不来，会报 disk I/O error）')
  process.exit(0)
}
main().catch((e) => { console.error(e); process.exit(1) })
