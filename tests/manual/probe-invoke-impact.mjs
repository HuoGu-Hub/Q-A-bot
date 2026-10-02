// tests/manual/probe-invoke-impact.mjs
// 量化 #invoke 造成的语料损失：多少页面因此变空/变短？
import fs from 'node:fs'

const KB = '/app/workspace/qqbot/server/data/kb'

// 1) 原始 wikitext 里含 #invoke 的页面
const rawDir = KB + '/raw'
const files = fs.readdirSync(rawDir).filter(f => f.endsWith('.wiki'))
let withInvoke = 0
let invokeOnly = 0            // 含 #invoke，且去掉模板后几乎没有正文
const titles = []
const pageTitles = new Map()
for (const line of fs.readFileSync(KB + '/pages.jsonl', 'utf8').split('\n').filter(Boolean)) {
  const d = JSON.parse(line)
  pageTitles.set(String(d.pageid), d.title)
}

for (const f of files) {
  const text = fs.readFileSync(rawDir + '/' + f, 'utf8')
  if (!text.includes('#invoke')) continue
  withInvoke++
  // 去掉 {{...}} 之后剩下的「人话」有多少
  const stripped = text.replace(/\{\{[^]*?\}\}/g, '').replace(/\[\[[^\]]*\]\]/g, '')
    .replace(/[|=\[\]{}#*!]/g, ' ').replace(/\s+/g, ' ').trim()
  if (stripped.length < 60) {
    invokeOnly++
    const id = f.replace('.wiki', '')
    titles.push(pageTitles.get(id) ?? id)
  }
}

// 2) 最终语料里的分布
const chunks = fs.readFileSync(KB + '/chunks.jsonl', 'utf8').split('\n').filter(Boolean).map(l => JSON.parse(l))
const byTitle = new Map()
for (const c of chunks) {
  if (!byTitle.has(c.title)) byTitle.set(c.title, [])
  byTitle.get(c.title).push(c)
}

console.log('═══ #invoke 的影响面 ═══')
console.log('  原始页总数          : ' + files.length)
console.log('  含 #invoke 的页     : ' + withInvoke + '  (' + (withInvoke / files.length * 100).toFixed(1) + '%)')
console.log('  其中「几乎只剩 #invoke」的页 : ' + invokeOnly + '  (' + (invokeOnly / files.length * 100).toFixed(1) + '%)')
console.log('')
console.log('  → 这 ' + invokeOnly + ' 个页面被整个丢弃（清洗后为空）')
console.log('')

if (titles.length) {
  console.log('  受影响的页面类型（前 25 个）：')
  titles.sort().slice(0, 25).forEach(t => console.log('    - ' + t))
  if (titles.length > 25) console.log('    … 还有 ' + (titles.length - 25) + ' 个')
}

// 3) 这些页面在最终语料里是否真的消失了
const survived = titles.filter(t => byTitle.has(t))
console.log('')
console.log('  这些页面在最终语料里还能找到的：' + survived.length + ' / ' + titles.length)
if (survived.length) {
  survived.slice(0, 5).forEach(t => {
    const c = byTitle.get(t)[0]
    console.log('    · ' + t + ' → ' + c.text.length + ' 字符：' + c.text.slice(0, 80).replace(/\n/g, ' '))
  })
}
