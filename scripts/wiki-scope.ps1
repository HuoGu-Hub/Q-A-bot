# ============================================================
#  wiki 拉取范围菜单 —— 看「能拉什么、每个多大」
#
#  用法：
#    .\scripts\wiki-scope.ps1              # 打印菜单（当前配置 + 可选分类与页数）
#    .\scripts\wiki-scope.ps1 -Top 30      # 多看几个分类
#
#  只读：不写库、不改配置。改范围是编辑 server/src/main/resources/application.yml
#  里的 app.kb.wiki-import.prefixes / categories，然后跑 wiki-import.ps1 --dry-run 确认。
# ============================================================
param([int]$Top = 20)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$yml = Join-Path $root "server/src/main/resources/application.yml"
$text = Get-Content $yml -Raw

$mapBlock = [regex]::Match($text, "(?ms)^\s*map-sync:.*?(?=^\s{0,4}\S|\z)").Value
# ⚠️ 必须从 map-sync 块里取：文件里第一个 api-base 是 onebot 的 ${ONEBOT_API_BASE:...}，那是占位符不是 URL
$base = ([regex]::Match($mapBlock, "(?m)^\s*api-base:\s*(\S+)")).Groups[1].Value
if (-not $base) { $base = "https://enshrouded.wiki.gg/api.php" }
$ua = ([regex]::Match($text, "(?m)^\s*user-agent:\s*\x22([^\x22]+)\x22")).Groups[1].Value
if (-not $ua) { $ua = "qqbot-personal/0.1 (Enshrouded map data sync)" }
$headers = @{ "User-Agent" = $ua; "Accept" = "application/json" }

function Api($query) {
    $u = $base + "?" + $query + "&format=json&formatversion=2"
    Invoke-RestMethod -Uri $u -TimeoutSec 30 -Headers $headers
}

$block = [regex]::Match($text, "(?ms)^\s*wiki-import:.*?(?=^\s{0,4}\S|\z)").Value
Write-Host ""
Write-Host "==== 当前配置的范围 ====" -ForegroundColor Cyan
$cur = [regex]::Matches($block, "(?m)^\s*-\s*([^#\s][^\r\n]*)$") | ForEach-Object { $_.Groups[1].Value.Trim() }
foreach ($c in $cur) { Write-Host ("    " + $c) }
$cap = [regex]::Match($block, "max-pages-per-source:\s*(\d+)").Groups[1].Value
Write-Host ("  每个来源上限 max-pages-per-source = " + $cap + " 页")

Write-Host ""
Write-Host "==== wiki 上的分类（按成员数排序，前 $Top）====" -ForegroundColor Cyan
$cats = (Api "action=query&list=allcategories&aclimit=500&acprop=size").query.allcategories
$cats = $cats | Sort-Object -Property size -Descending
Write-Host ("  wiki 上共有 " + $cats.Count + " 个分类。成员数含子分类与文件；正文页数是实际能拉到的页。")
Write-Host ""
Write-Host ("  {0,-32} {1,8} {2,10}" -f "分类", "成员数", "正文页数")
foreach ($c in ($cats | Select-Object -First $Top)) {
    $name = $c.category
    $pages = "?"
    try {
        $q = "action=query&list=categorymembers&cmtitle=" + [uri]::EscapeDataString("Category:" + $name) + "&cmtype=page&cmlimit=500"
        $pages = (Api $q).query.categorymembers.Count
    } catch { }
    $mark = ""
    if ($cur -contains $name) { $mark = "  <- 已在范围里" }
    Write-Host ("  {0,-32} {1,8} {2,10}{3}" -f $name, $c.size, $pages, $mark)
}

Write-Host ""
Write-Host "==== 正文页的页名前缀（前 500 条聚合，用于 prefixes）====" -ForegroundColor Cyan
$all = (Api "action=query&list=allpages&aplimit=500&apnamespace=0").query.allpages
$pref = @{}
foreach ($p in $all) {
    $t = $p.title
    if ($t -match "^([^/]+)/") { $k = $Matches[1] + "/" } else { $k = "(无前缀)" }
    if ($pref.ContainsKey($k)) { $pref[$k] = $pref[$k] + 1 } else { $pref[$k] = 1 }
}
$pref.GetEnumerator() | Sort-Object -Property Value -Descending | Select-Object -First 12 | ForEach-Object {
    $mark = ""
    if ($cur -contains $_.Key) { $mark = "  <- 已在范围里" }
    Write-Host ("  {0,-32} {1,8}{2}" -f $_.Key, $_.Value, $mark)
}
Write-Host ""
Write-Host "  改范围：编辑 server/src/main/resources/application.yml 的 app.kb.wiki-import.prefixes / categories" -ForegroundColor Yellow
Write-Host "  然后：.\scripts\wiki-import.ps1 --dry-run   # 看页数与清洗结果，不写库"
Write-Host ""