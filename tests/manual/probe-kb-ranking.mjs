// tests/manual/probe-kb-ranking.mjs
// 用【全量索引】查一个中文问题，看目标页真实排在第几 —— 用来判断要不要上混合检索。
// 用法：node tests/manual/probe-kb-ranking.mjs "废料杯怎么合成？" "Scrap Cup"
import fs from 'node:fs'

const dir = '/app/workspace/qqbot/server/data/kb'
const query = process.argv[2] ?? '废料杯怎么合成？'
const want = process.argv[3] ?? 'Scrap Cup'

const env = Object.fromEntries(
  fs.readFileSync('/app/workspace/qqbot/.env', 'utf8').split('\n')
    .filter((l) => l.includes('=') && !l.trim().startsWith('#'))
    .map((l) => { const i = l.indexOf('='); return [l.slice(0, i).trim(), l.slice(i + 1).trim()] }),
)

const chunks = fs.readFileSync(dir + '/chunks.jsonl', 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
const buf = fs.readFileSync(dir + '/index.bin')
const n = buf.readInt32BE(4)
const d = buf.readInt32BE(8)
const vecs = []
for (let i = 0; i < n; i++) {
  const v = new Float32Array(d)
  for (let j = 0; j < d; j++) v[j] = buf.readFloatBE(12 + (i * d + j) * 4)
  vecs.push(v)
}

const r = await fetch('https://api.siliconflow.cn/v1/embeddings', {
  method: 'POST',
  headers: { 'content-type': 'application/json', authorization: 'Bearer ' + env.SILICONFLOW_API_KEY },
  body: JSON.stringify({ model: 'BAAI/bge-m3', input: query, encoding_format: 'float' }),
})
const q = (await r.json()).data[0].embedding

const cos = (a, b) => {
  let x = 0, na = 0, nb = 0
  for (let i = 0; i < a.length; i++) { x += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
  return x / (Math.sqrt(na) * Math.sqrt(nb))
}

const scored = chunks.map((c, i) => ({ t: c.title, s: cos(q, vecs[i]) })).sort((a, b) => b.s - a.s)
console.log('查询：「' + query + '」   目标：' + want)
console.log('索引共 ' + n + ' 块，纯向量 Top-8：')
scored.slice(0, 8).forEach((x, i) => console.log('  ' + (i + 1) + '. ' + x.s.toFixed(3) + '  ' + x.t))

const rank = scored.findIndex((x) => x.t === want) + 1
console.log('')
console.log('  → ' + want + ' 排名：' + (rank > 0 ? rank : '未命中') +
  (rank > 0 ? '（分数 ' + scored[rank - 1].s.toFixed(3) + '）' : ''))
console.log('  → 若 topK=5，' + (rank > 0 && rank <= 5 ? '会被带进 prompt ✅' : '不会被带进 prompt ❌'))
