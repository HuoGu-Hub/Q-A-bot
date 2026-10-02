// tests/manual/probe-dependency-vulns.mjs
// 依赖漏洞扫描（不依赖 OWASP 的 NVD 全量库 —— 国内网络下不全）。
// 思路：解析出实际依赖坐标 → 查公开漏洞库 API。
import fs from 'node:fs'
import { execFileSync } from 'node:child_process'

const ROOT = '/app/workspace/qqbot'

// ---------- 1) 从 pom.xml 提取直接依赖 ----------
console.log('═══ 后端直接依赖 ═══')
const pom = fs.readFileSync(ROOT + '/server/pom.xml', 'utf8')
const deps = []
const re = /<dependency>([\s\S]*?)<\/dependency>/g
let m
while ((m = re.exec(pom))) {
  const g = /<groupId>([^<]+)/.exec(m[1])?.[1]
  const a = /<artifactId>([^<]+)/.exec(m[1])?.[1]
  const v = /<version>([^<]+)/.exec(m[1])?.[1]
  if (g && a) deps.push({ g, a, v: v ?? '(父 POM 管理)' })
}
deps.forEach(d => console.log('  ' + d.g + ':' + d.a + '  ' + d.v))

// ---------- 2) 实际解析出的版本（从 m2 目录） ----------
console.log('')
console.log('═══ 实际打包进去的版本（关键依赖）═══')
const interesting = ['spring-boot', 'spring-core', 'spring-web', 'tomcat-embed-core',
  'jackson-databind', 'langchain4j', 'sqlite-jdbc', 'logback-classic']
const m2 = ROOT + '/.toolchain/m2'
function findVersions(prefix, artifact) {
  const dir = m2 + '/' + prefix.replace(/\./g, '/') + '/' + artifact
  if (!fs.existsSync(dir)) return []
  return fs.readdirSync(dir).filter(f => fs.statSync(dir + '/' + f).isDirectory())
}
const resolved = []
for (const art of interesting) {
  for (const grp of ['org/springframework/boot', 'org/springframework', 'org/apache/tomcat/embed',
    'com/fasterxml/jackson/core', 'dev/langchain4j', 'org/xerial', 'ch/qos/logback']) {
    const vs = findVersions(grp, art)
    if (vs.length) { resolved.push({ art, v: vs[vs.length - 1] }); break }
  }
}
resolved.forEach(r => console.log('  ' + r.art.padEnd(24) + r.v))

// ---------- 3) 查漏洞：OSV.dev（Google 维护，免费、无需 key、国内可访问） ----------
console.log('')
console.log('═══ 漏洞查询（OSV.dev）═══')

async function queryOsv(pkg) {
  const r = await fetch('https://api.osv.dev/v1/query', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ package: { name: pkg.name, ecosystem: pkg.eco }, version: pkg.version }),
  })
  if (!r.ok) throw new Error('HTTP ' + r.status)
  return (await r.json()).vulns ?? []
}

const targets = [
  { name: 'org.springframework.boot:spring-boot', eco: 'Maven' },
  { name: 'org.springframework:spring-core', eco: 'Maven' },
  { name: 'org.springframework:spring-web', eco: 'Maven' },
  { name: 'org.apache.tomcat.embed:tomcat-embed-core', eco: 'Maven' },
  { name: 'com.fasterxml.jackson.core:jackson-databind', eco: 'Maven' },
  { name: 'dev.langchain4j:langchain4j-open-ai', eco: 'Maven' },
  { name: 'org.xerial:sqlite-jdbc', eco: 'Maven' },
  { name: 'ch.qos.logback:logback-classic', eco: 'Maven' },
  // 前端
  { name: 'vue', eco: 'npm' },
  { name: 'vue-router', eco: 'npm' },
  { name: 'vite', eco: 'npm' },
]

let totalVulns = 0
for (const t of targets) {
  const short = t.name.split(':').pop()
  const found = resolved.find(r => r.art === short)
  const version = t.eco === 'npm'
    ? (JSON.parse(fs.readFileSync(ROOT + '/web/node_modules/' + t.name + '/package.json', 'utf8')).version)
    : (found?.v ?? '0.0.0')
  try {
    const vulns = await queryOsv({ ...t, version })
    if (vulns.length === 0) {
      console.log('  ✅ ' + short.padEnd(26) + version.padEnd(12) + '无已知漏洞')
    } else {
      totalVulns += vulns.length
      console.log('  ⚠️  ' + short.padEnd(26) + version.padEnd(12) + vulns.length + ' 条')
      for (const v of vulns.slice(0, 3)) {
        const sev = v.severity?.[0]?.score ?? v.database_specific?.severity ?? '?'
        console.log('       - ' + v.id + '  ' + String(v.summary ?? '').slice(0, 70))
      }
    }
  } catch (e) {
    console.log('  ❓ ' + short.padEnd(26) + version.padEnd(12) + '查询失败：' + e.message)
  }
}

console.log('')
console.log('═══ 汇总 ═══')
console.log('  已知漏洞总数：' + totalVulns)
console.log('  ' + (totalVulns === 0 ? '✅ 未发现已知漏洞' : '⚠️ 见上方明细'))
