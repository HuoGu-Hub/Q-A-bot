// tests/manual/probe-embedding-retrieval.mjs
// 一次跑完三件事：
//   ① 验证 SILICONFLOW_API_KEY 能用（模型名/端点/维度）
//   ② 压掉 R1 风险：中文问题到底能不能检索到英文资料
//   ③ 给相似度阈值（app.kb.min-score）一个实测依据
//
// 用法：node tests/manual/probe-embedding-retrieval.mjs [干扰块数，默认 80]
import fs from 'node:fs'

const ENV = '/app/workspace/qqbot/.env'
const CHUNKS = '/app/workspace/qqbot/server/data/kb/chunks.jsonl'
const BASE = 'https://api.siliconflow.cn/v1'
const MODEL = 'BAAI/bge-m3'
const DISTRACTORS = Number(process.argv[2] ?? 80)

// ---------- 读 key（绝不打印内容） ----------
const env = Object.fromEntries(
  fs.readFileSync(ENV, 'utf8').split('\n')
    .filter((l) => l.includes('=') && !l.trim().startsWith('#'))
    .map((l) => { const i = l.indexOf('='); return [l.slice(0, i).trim(), l.slice(i + 1).trim()] }),
)
const key = env.SILICONFLOW_API_KEY
if (!key) { console.error('❌ .env 里没有 SILICONFLOW_API_KEY'); process.exit(1) }
console.log('[KEY] 已读到 SILICONFLOW_API_KEY（' + key.length + ' 位，不打印内容）')

// ---------- embedding ----------
async function embed(inputs) {
  const r = await fetch(BASE + '/embeddings', {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: 'Bearer ' + key },
    body: JSON.stringify({ model: MODEL, input: inputs, encoding_format: 'float' }),
  })
  const j = await r.json().catch(() => null)
  if (!r.ok || !j?.data) {
    throw new Error('HTTP ' + r.status + '：' + JSON.stringify(j).slice(0, 400))
  }
  return j.data.sort((a, b) => a.index - b.index).map((d) => d.embedding)
}

const cos = (a, b) => {
  let dot = 0, na = 0, nb = 0
  for (let i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
  return dot / (Math.sqrt(na) * Math.sqrt(nb))
}

// ---------- 载入分块 ----------
const all = fs.readFileSync(CHUNKS, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
const byTitle = new Map()
for (const c of all) if (!byTitle.has(c.title)) byTitle.set(c.title, c)

const CASES = [
  { q: '废料杯怎么合成？', want: 'Scrap Cup' },
  { q: '卷毛山羊怎么驯服', want: 'Frizzy Goat' },
  { q: '爆炸箭 III 是干什么用的', want: 'Explosive Arrow III' },
  { q: '雇佣兵头盔什么属性', want: 'Mercenary Helmet' },
]
const NEGATIVE = '今天北京天气怎么样，适合出门吗'

// 目标块 + 随机干扰块
const picked = new Map()
for (const c of CASES) {
  const hit = byTitle.get(c.want)
  if (!hit) { console.error('❌ 语料里找不到 ' + c.want); process.exit(1) }
  picked.set(c.want, hit)
}
let seed = 42
const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff
while (picked.size < CASES.length + DISTRACTORS) {
  picked.set(all[Math.floor(rnd() * all.length)].i, all[Math.floor(rnd() * all.length)])
}
const pool = [...new Set(picked.values())]
console.log('[数据] 候选块 ' + pool.length + ' 个（' + CASES.length + ' 个目标 + ' + DISTRACTORS + ' 个随机干扰）')

// ---------- 调接口 ----------
console.log('[调用] 正在向量化…')
const t0 = Date.now()
const chunkVecs = []
for (let i = 0; i < pool.length; i += 10) {
  const batch = pool.slice(i, i + 10)
  const vecs = await embed(batch.map((c) => c.text.slice(0, 2000)))
  chunkVecs.push(...vecs)
}
const queryVecs = await embed([...CASES.map((c) => c.q), NEGATIVE])
console.log('[调用] 完成，' + pool.length + '+' + (CASES.length + 1) + ' 条，耗时 ' + ((Date.now() - t0) / 1000).toFixed(1) + 's')
console.log('[维度] ' + chunkVecs[0].length + ' 维')

// ---------- 评测 ----------
console.log('')
console.log('============ 中文问题 → 英文资料（R1 风险验证）============')
let hit1 = 0, hit5 = 0
CASES.forEach((c, qi) => {
  const scored = pool.map((chunk, ci) => ({ title: chunk.title, score: cos(queryVecs[qi], chunkVecs[ci]) }))
  scored.sort((a, b) => b.score - a.score)
  const rank = scored.findIndex((s) => s.title === c.want) + 1
  if (rank === 1) hit1++
  if (rank <= 5) hit5++
  const mark = rank === 1 ? '✅ Top-1' : rank <= 5 ? '⚠️ Top-' + rank : '❌ 排名 ' + rank
  console.log('')
  console.log('  「' + c.q + '」  期望：' + c.want + '   ' + mark)
  console.log('   ' + scored.slice(0, 3).map((s) => s.score.toFixed(3) + ' ' + s.title).join('\n   '))
})

const negScores = pool.map((chunk, ci) => cos(queryVecs[CASES.length], chunkVecs[ci])).sort((a, b) => b - a)
console.log('')
console.log('  无关问题「' + NEGATIVE + '」')
console.log('   最高相似度 ' + negScores[0].toFixed(3) + ' / 中位 ' + negScores[Math.floor(negScores.length / 2)].toFixed(3))

console.log('')
console.log('================ 结果 ================')
console.log('  Top-1 命中：' + hit1 + '/' + CASES.length)
console.log('  Top-5 命中：' + hit5 + '/' + CASES.length)
console.log('  无关问题的最高分：' + negScores[0].toFixed(3) + '  ← 阈值 min-score 应明显高于它')
