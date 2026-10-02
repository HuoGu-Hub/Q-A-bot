// tests/manual/probe-wiki-corpus.mjs
// 抽样统计 enshrouded.wiki.gg 的正文体量，用来估算分块数 / 向量索引大小 / embedding 成本。
// 用法：node tests/manual/probe-wiki-corpus.mjs [样本数，默认 60]
const API = 'https://enshrouded.wiki.gg/api.php'
const SAMPLE = Number(process.argv[2] ?? 60)

async function api(params) {
  const url = API + '?' + new URLSearchParams({ format: 'json', ...params })
  const r = await fetch(url, { headers: { 'user-agent': 'qqbot-personal/0.1 (corpus survey)' } })
  if (!r.ok) throw new Error('HTTP ' + r.status + ' ' + url)
  return r.json()
}

// 随机抽一批正文页
async function samplePages(n) {
  const out = new Map()
  while (out.size < n) {
    const j = await api({ action: 'query', generator: 'random', grnnamespace: '0',
      grnlimit: String(Math.min(20, n - out.size)), prop: 'extracts', explaintext: '1', exlimit: '20' })
    const pages = j?.query?.pages ?? {}
    for (const p of Object.values(pages)) {
      if (p.title && typeof p.extract === 'string') out.set(p.title, p.extract)
    }
    if (Object.keys(pages).length === 0) break
  }
  return [...out.entries()]
}

const t0 = Date.now()
const pages = await samplePages(SAMPLE)
const lens = pages.map(([, t]) => t.length).sort((a, b) => a - b)

const sum = lens.reduce((a, b) => a + b, 0)
const avg = sum / lens.length
const median = lens[Math.floor(lens.length / 2)]
const stubs = lens.filter((n) => n < 200).length
const zero = lens.filter((n) => n === 0).length

console.log('样本数            : ' + lens.length + '（耗时 ' + ((Date.now() - t0) / 1000).toFixed(1) + 's）')
console.log('正文总字符        : ' + sum)
console.log('平均 / 中位数     : ' + Math.round(avg) + ' / ' + median + ' 字符')
console.log('最短 / 最长       : ' + lens[0] + ' / ' + lens[lens.length - 1])
console.log('空正文(<1)        : ' + zero + '（' + (zero / lens.length * 100).toFixed(1) + '%）')
console.log('近空(<200字符)    : ' + stubs + '（' + (stubs / lens.length * 100).toFixed(1) + '%）')
console.log('')
console.log('按 wiki 统计的 3689 篇文章外推：')
console.log('  正文总量约      : ' + (avg * 3689 / 1e6).toFixed(2) + ' M 字符')
console.log('  约合 token      : ' + (avg * 3689 / 4 / 1e6).toFixed(2) + ' M（按 4 字符/token 粗估）')
console.log('  按 400 token/块 : ' + Math.round(avg * 3689 / 4 / 400) + ' 块')
console.log('  向量索引(1024维) : ' + (avg * 3689 / 4 / 400 * 1024 * 4 / 1e6).toFixed(1) + ' MB (float32)')
console.log('')
console.log('最长的 5 篇：')
for (const [title, text] of pages.sort((a, b) => b[1].length - a[1].length).slice(0, 5)) {
  console.log('  ' + String(text.length).padStart(6) + '  ' + title)
}
console.log('')
console.log('正文最短的 5 篇（分块时要能容忍这种 stub）：')
for (const [title, text] of pages.sort((a, b) => a[1].length - b[1].length).slice(0, 5)) {
  console.log('  ' + String(text.length).padStart(6) + '  ' + title)
}
