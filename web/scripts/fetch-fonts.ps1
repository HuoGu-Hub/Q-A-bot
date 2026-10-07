<#
  下载并落地标题字体的分片文件。
  ----------------------------------------------------------------------------
  为什么需要这个脚本
  ----------------------------------------------------------------------------
  站点的标题用「思源宋体（Noto Serif SC）」。中文整套字体有几 MB，直接引进来的话
  每个访客（尤其是手机上的群友）都要先下载一遍才能看见标题 —— 不可接受。

  Google Fonts 的做法是把一个中文字体按 unicode 区间**切成 ~100 个分片**，
  每个分片对应 256 个左右的码位，并在 @font-face 上用 unicode-range 声明。
  浏览器只会去取"当前页面真正出现了的字符"所在的那几个分片，
  而中文页面的常用汉字收敛得很快，实测一个页面通常只命中 5~15 个分片（几百 KB）。

  这个脚本就是把这套分片原样搬回自己家：从 Google 拿 @font-face 清单，
  把 woff2 一个个下到 web/public/fonts/noto-serif-sc/，再把 CSS 里的绝对 URL
  改写成站内路径，输出到 web/src/theme/fonts.css。
  自托管之后，访问者不需要能连上 fonts.googleapis.com（国内很多时候连不上）。

  用法：
      pwsh -File web/scripts/fetch-fonts.ps1

  产物（都在仓库里，改字体才需要重跑）：
      web/public/fonts/noto-serif-sc/*.woff2
      web/src/theme/fonts.css

  许可：Noto Serif SC 采用 SIL Open Font License 1.1，允许自托管与再分发。
#>
param(
    [string]$Weight = '600',
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'

# 必须带一个现代浏览器的 UA：不带的话 Google Fonts 会回 woff/ttf（体积大好几倍），
# 而且不分 unicode-range 分片。
$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

$dir = Join-Path $Root 'public\fonts\noto-serif-sc'
$outCss = Join-Path $Root 'src\theme\fonts.css'
$cssUrl = "https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@$Weight&display=swap"

Write-Host "拉取 @font-face 清单：$cssUrl"
$css = (Invoke-WebRequest -Uri $cssUrl -Headers @{ 'User-Agent' = $ua } -UseBasicParsing -TimeoutSec 60).Content

$faces = [regex]::Matches($css, '(?s)@font-face\s*\{.*?\}')
if ($faces.Count -eq 0) { throw '解析不到任何 @font-face，Google 的返回格式可能变了' }
Write-Host "共 $($faces.Count) 个分片"

if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dir | Out-Null

$blocks = @()
$urls = @()
for ($i = 0; $i -lt $faces.Count; $i++) {
    $block = $faces[$i].Value
    $m = [regex]::Match($block, 'url\((https://[^)]+)\)')
    if (-not $m.Success) { Write-Warning "第 $i 个分片没有 url()，跳过"; continue }
    $urls += [pscustomobject]@{ i = $i; url = $m.Groups[1].Value }
    # 站内绝对路径：这两个入口的 publicDir 就是 web/public，构建后会原样拷进 dist
    $blocks += $block.Replace($m.Groups[1].Value, "/fonts/noto-serif-sc/$i.woff2")
}

Write-Host "下载 woff2（$($urls.Count) 个，并发 8）…"
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$urls | ForEach-Object -Parallel {
    $dest = Join-Path $using:dir ($_.i.ToString() + '.woff2')
    try {
        Invoke-WebRequest -Uri $_.url -Headers @{ 'User-Agent' = $using:ua } -OutFile $dest -TimeoutSec 120
    } catch {
        Write-Warning "分片 $($_.i) 下载失败：$($_.Exception.Message)"
    }
} -ThrottleLimit 8

# 失败的重试两轮：gstatic 偶发会掐断连接，实测 100 个里总有一两个要重试
for ($pass = 1; $pass -le 2; $pass++) {
    $missing = $urls | Where-Object { -not (Test-Path (Join-Path $dir ($_.i.ToString() + '.woff2'))) }
    if (-not $missing) { break }
    Write-Host "第 $pass 轮重试：$($missing.Count) 个"
    foreach ($t in $missing) {
        try {
            Invoke-WebRequest -Uri $t.url -Headers @{ 'User-Agent' = $ua } -OutFile (Join-Path $dir ($t.i.ToString() + '.woff2')) -TimeoutSec 120
        } catch { Write-Warning "分片 $($t.i) 仍然失败" }
    }
}

$files = Get-ChildItem $dir -Filter *.woff2
$bad = @($files | Where-Object {
    $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
    $bytes.Length -lt 512 -or [System.Text.Encoding]::ASCII.GetString($bytes, 0, 4) -ne 'wOF2'
})
if ($bad.Count -gt 0) { throw "有 $($bad.Count) 个文件不是有效的 woff2：$($bad.Name -join ', ')" }

$header = @"
/* Noto Serif SC $Weight —— 自托管。
   由 Google Fonts 的 unicode-range 分片原样落地：浏览器只请求页面真正用到的字，
   不用整包下载几 MB 的中文字体。
   ⚠️ 这个文件是生成的，别手改 —— 重新生成：
       pwsh -File web/scripts/fetch-fonts.ps1
   许可：SIL Open Font License 1.1 */
"@
$nl = [Environment]::NewLine
Set-Content -Path $outCss -Value ($header + $nl + ($blocks -join $nl) + $nl) -Encoding utf8

Write-Host ""
Write-Host "完成：$($files.Count) 个分片，合计 $([math]::Round((($files | Measure-Object Length -Sum).Sum / 1MB), 2)) MB，用时 $([math]::Round($sw.Elapsed.TotalSeconds))s"
Write-Host "  → $dir"
Write-Host "  → $outCss"
