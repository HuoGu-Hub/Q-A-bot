<#
.SYNOPSIS
  本地构建 → 打包 → 上传 → 服务器替换并重启。

.DESCRIPTION
  只做三件事：在本地出产物、打成一个 tar.gz、传到服务器解包重启。
  服务器上不需要 JDK、Maven、Node，也不需要 git。

  后端构建刻意在 .toolchain/release/build 里做隔离副本，不碰 server/target：
  IDEA 跑的是 server/target/classes，对着 server/pom.xml 跑 clean 会把正在运行的
  classpath 抽走，报 NoClassDefFoundError。

.PARAMETER Server
  目标，形如 root@1.2.3.4 或 qqbot@1.2.3.4。省略时只构建和打包。

.EXAMPLE
  .\scripts\release.ps1 -Server root@1.2.3.4
  .\scripts\release.ps1 -SkipBuild -Server root@1.2.3.4
  .\scripts\release.ps1 -SkipUpload
  .\scripts\release.ps1 -WebOnly -Server root@1.2.3.4
#>
[CmdletBinding()]
param(
  [string]$Server,
  [int]$SshPort = 22,
  # 私钥文件。服务器只认控制台绑定的那把时，不带这个参数 scp 会 Permission denied
  [string]$IdentityFile,
  [switch]$SkipBuild,
  [switch]$SkipUpload,
  [switch]$NoRestart,
  [switch]$WithConfig,
  # 只发前端：构建 web-dist、上传替换，不动 jar、不重启服务
  [switch]$WebOnly,
  [string]$RemoteDir = "/opt/qqbot",
  [string]$ServiceUser = "qqbot",
  [string]$ServiceName = "qqbot"
)

$ErrorActionPreference = "Stop"

$Root     = Split-Path -Parent $PSScriptRoot
$BuildDir = Join-Path $Root ".toolchain\release"
$StageDir = Join-Path $BuildDir "stage"
$Bundle   = Join-Path $BuildDir "qqbot-release.tar.gz"
$JarName  = "qqbot-server-0.0.1-SNAPSHOT.jar"

function Step([string]$Text) {
  Write-Host ""
  Write-Host "==> $Text" -ForegroundColor Cyan
}

# 复制到 Linux 上执行的文本文件时统一成 LF。
# Windows 工作副本可能是 CRLF：bash 会因 \r 报语法错，nginx 与 systemd 直接拒绝解析。
function Copy-TextLf([string]$From, [string]$ToDir) {
  $text = [System.IO.File]::ReadAllText($From)
  $text = $text.Replace([string][char]13 + [string][char]10, [string][char]10)
  $dest = Join-Path $ToDir (Split-Path $From -Leaf)
  [System.IO.File]::WriteAllText($dest, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function Invoke-Native([string]$File, [string[]]$Arguments, [string]$WorkDir) {
  Push-Location $WorkDir
  try {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$File 执行失败，退出码 $LASTEXITCODE" }
  } finally { Pop-Location }
}

New-Item -ItemType Directory -Path $BuildDir -Force | Out-Null

# ---------------------------------------------------------------- 1. 前端
if (-not $SkipBuild) {
  Step "构建前端 web/dist"
  Invoke-Native "pnpm.cmd" @("build") (Join-Path $Root "web")

  Step "同步前端产物到 server/web-dist"
  $dist = Join-Path $Root "web\dist"
  $target = Join-Path $Root "server\web-dist"
  if (-not (Test-Path $dist)) { throw "web/dist 不存在，前端构建没有成功" }
  if (Test-Path $target) { Remove-Item $target -Recurse -Force }
  Copy-Item $dist $target -Recurse
  $files = @(Get-ChildItem $target -Recurse -File)
  $mb = ($files | Measure-Object Length -Sum).Sum / 1MB
  Write-Host ("    web-dist: {0:N1} MB / {1} 个文件" -f $mb, $files.Count)
}

# ---------------------------------------------------------------- 1b. 只发前端
# 前端是 file:./web-dist/ 直接读磁盘，替换文件就生效，不用重启服务，也不碰 jar
if ($WebOnly) {
  Step "只发前端"
  $webDist = Join-Path $Root "server\web-dist"
  if (-not (Test-Path $webDist)) {
    throw "server\web-dist 不存在；先去掉 -SkipBuild 跑一次完整流程"
  }
  $webBundle = Join-Path $BuildDir "qqbot-web-dist.tar.gz"
  if (Test-Path $webBundle) { Remove-Item $webBundle -Force }
  & tar -czf $webBundle -C (Join-Path $Root "server") "web-dist"
  if ($LASTEXITCODE -ne 0) { throw "tar 打包失败" }
  Write-Host ("    {0}  {1:N1} MB" -f $webBundle, ((Get-Item $webBundle).Length / 1MB))

  if ($SkipUpload) {
    Step "已跳过上传"
    Write-Host "    包在 $webBundle"
    return
  }
  if (-not $Server) {
    throw "缺少 -Server，例如 -Server root@1.2.3.4；只想打包就加 -SkipUpload"
  }

  Step "上传到 $Server"
  $scpArgs = @("-P", $SshPort)
  if ($IdentityFile) { $scpArgs += @("-i", $IdentityFile) }
  $scpArgs += @($webBundle, "${Server}:/tmp/qqbot-web-dist.tar.gz")
  & scp @scpArgs
  if ($LASTEXITCODE -ne 0) { throw "scp 失败：检查 -IdentityFile 是否指向正确的私钥" }

  Step "服务器端替换 web-dist"
  # 只解包 web-dist，chown 完就结束。服务一直在跑，浏览器强刷即可看到新页面
  $remote = @'
set -e
if [ "$(id -u)" = 0 ]; then S=""; else S="sudo"; fi

# 先解到临时目录：归档损坏时不会把线上那份拆坏
rm -rf /tmp/qqbot-web-new
mkdir -p /tmp/qqbot-web-new
tar xzf /tmp/qqbot-web-dist.tar.gz -C /tmp/qqbot-web-new

$S mkdir -p __DIR__/server
if command -v rsync >/dev/null 2>&1; then
  # --delete 清掉旧 hash 资源，避免目录越滚越大
  $S rsync -a --delete /tmp/qqbot-web-new/web-dist/ __DIR__/server/web-dist/
else
  $S tar xzf /tmp/qqbot-web-dist.tar.gz -C __DIR__/server
fi
$S chown -R __USER__:__USER__ __DIR__/server/web-dist
rm -rf /tmp/qqbot-web-new

if curl -s -m 5 -o /dev/null http://127.0.0.1:8080/; then
  echo "首页可达"
else
  echo "首页不可达，查 systemctl status qqbot"
fi
'@
  $remote = $remote.Replace("__DIR__", $RemoteDir).Replace("__USER__", $ServiceUser)
  $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remote))
  $sshArgs = @("-t", "-p", $SshPort)
  if ($IdentityFile) { $sshArgs += @("-i", $IdentityFile) }
  $sshArgs += @($Server, "echo $b64 | base64 -d | bash")
  & ssh @sshArgs
  if ($LASTEXITCODE -ne 0) { throw "远端替换失败，看上面的输出" }

  Write-Host ""
  Write-Host "前端已更新，服务没有重启。浏览器按 Ctrl+F5 强刷。" -ForegroundColor Green
  return
}

# ---------------------------------------------------------------- 2. 后端
$jarPath = Join-Path $BuildDir $JarName
if (-not $SkipBuild) {
  Step "构建后端，隔离副本 .toolchain/release/build"
  $src = Join-Path $BuildDir "build"
  if (Test-Path $src) { Remove-Item $src -Recurse -Force }
  New-Item -ItemType Directory -Path $src -Force | Out-Null
  Copy-Item (Join-Path $Root "server\pom.xml") $src
  Copy-Item (Join-Path $Root "server\src") $src -Recurse
  # pom 的 resources 声明了 ${project.basedir}/../system.md，从 build/ 看就是 .toolchain/release/system.md
  Copy-Item (Join-Path $Root "system.md") $BuildDir

  Invoke-Native "mvn" @("-q", "-B", "-f", (Join-Path $src "pom.xml"), "-DskipTests", "package") $src
}

if (-not (Test-Path $jarPath)) {
  $found = Get-ChildItem (Join-Path $BuildDir "build\target") -Filter "qqbot-server-*.jar" -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
  if (-not $found) { throw "找不到构建产物；先去掉 -SkipBuild 跑一次，或者检查 mvn 输出" }
  Copy-Item $found.FullName $jarPath -Force
}
Write-Host ("    jar: {0:N1} MB" -f ((Get-Item $jarPath).Length / 1MB))

# ---------------------------------------------------------------- 3. 打包
Step "打包"
if (Test-Path $StageDir) { Remove-Item $StageDir -Recurse -Force }
$pkg = Join-Path $StageDir "qqbot-release"
$pkgServer = Join-Path $pkg "server"
New-Item -ItemType Directory -Path $pkgServer -Force | Out-Null

Copy-Item $jarPath (Join-Path $pkgServer $JarName)
Copy-Item (Join-Path $Root "server\web-dist") (Join-Path $pkgServer "web-dist") -Recurse
Copy-Item (Join-Path $Root "system.md") $pkg

New-Item -ItemType Directory -Path (Join-Path $pkg "deploy\nginx") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $pkg "deploy\systemd") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $pkg "deploy\server") -Force | Out-Null
Copy-TextLf (Join-Path $Root "deploy\nginx\qqbot.conf") (Join-Path $pkg "deploy\nginx")
Copy-TextLf (Join-Path $Root "deploy\nginx\qqbot-domain.conf") (Join-Path $pkg "deploy\nginx")
Copy-TextLf (Join-Path $Root "deploy\nginx\qqbot-ip.conf") (Join-Path $pkg "deploy\nginx")
Copy-TextLf (Join-Path $Root "deploy\nginx\qqbot-ip-public.conf") (Join-Path $pkg "deploy\nginx")
Copy-TextLf (Join-Path $Root "deploy\systemd\qqbot.service") (Join-Path $pkg "deploy\systemd")
Copy-TextLf (Join-Path $Root "deploy\server\bootstrap.sh") (Join-Path $pkg "deploy\server")
Copy-TextLf (Join-Path $Root "deploy\docker-compose.yml") (Join-Path $pkg "deploy")

New-Item -ItemType Directory -Path (Join-Path $pkg "scripts") -Force | Out-Null
Copy-TextLf (Join-Path $Root "scripts\health-check.sh") (Join-Path $pkg "scripts")

# 配置默认不进包：每次发版都覆盖服务器上改过的 overrides.yml 会丢设置
if ($WithConfig) {
  $ov = Join-Path $Root "server\config\overrides.yml"
  if (Test-Path $ov) {
    New-Item -ItemType Directory -Path (Join-Path $pkgServer "config") -Force | Out-Null
    Copy-Item $ov (Join-Path $pkgServer "config")
    Write-Host "    已包含 server/config/overrides.yml"
  }
}

$commit = (git -C $Root rev-parse --short HEAD 2>$null)
if (-not $commit) { $commit = "unknown" }
@(
  "built:  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
  "commit: $commit",
  "jar:    $JarName"
) | Set-Content (Join-Path $pkg "VERSION.txt") -Encoding UTF8

if (Test-Path $Bundle) { Remove-Item $Bundle -Force }
& tar -czf $Bundle -C $StageDir "qqbot-release"
if ($LASTEXITCODE -ne 0) { throw "tar 打包失败" }
Write-Host ("    {0}  {1:N1} MB" -f $Bundle, ((Get-Item $Bundle).Length / 1MB))

# ---------------------------------------------------------------- 4. 上传
if ($SkipUpload) {
  Step "已跳过上传"
  Write-Host "    包在 $Bundle"
  return
}
if (-not $Server) {
  throw "缺少 -Server，例如 -Server root@1.2.3.4；只想打包就加 -SkipUpload"
}

Step "上传到 $Server"
$scpArgs = @("-P", $SshPort)
if ($IdentityFile) { $scpArgs += @("-i", $IdentityFile) }
$scpArgs += @($Bundle, "${Server}:/tmp/qqbot-release.tar.gz")
& scp @scpArgs
if ($LASTEXITCODE -ne 0) { throw "scp 失败：检查 -IdentityFile 是否指向正确的私钥" }

# ---------------------------------------------------------------- 5. 远端安装
Step "服务器端替换并重启"
$remote = @'
set -e
if [ "$(id -u)" = 0 ]; then S=""; else S="sudo"; fi
$S systemctl stop __SERVICE__ 2>/dev/null || true
$S mkdir -p __DIR__
$S tar xzf /tmp/qqbot-release.tar.gz -C __DIR__ --strip-components=1
$S chown -R __USER__:__USER__ __DIR__
# 单元跟着仓库走：不然改了 JVM 参数或启动依赖，服务器上那份永远是旧的
if [ -f __DIR__/deploy/systemd/__SERVICE__.service ]; then
  $S cp __DIR__/deploy/systemd/__SERVICE__.service /etc/systemd/system/__SERVICE__.service
  echo "已同步 systemd 单元"
fi
$S systemctl daemon-reload
$S systemctl enable __SERVICE__ >/dev/null 2>&1 || true
__RESTART__
sleep 3
# 首次部署时单元还没装，这里必然非零：只当作诊断，不要让整次上传判失败
$S systemctl is-active __SERVICE__ || $S journalctl -u __SERVICE__ -n 40 --no-pager || true
'@

# 重启失败不要立刻中断：先让下面的 is-active 与 journalctl 把现场打出来
$restart = if ($NoRestart) { "echo skip-restart" } else { '$S systemctl restart __SERVICE__ || true' }
$remote = $remote.Replace("__DIR__", $RemoteDir)
$remote = $remote.Replace("__USER__", $ServiceUser)
$remote = $remote.Replace("__RESTART__", $restart)
$remote = $remote.Replace("__SERVICE__", $ServiceName)

$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remote))
$sshArgs = @("-t", "-p", $SshPort)
if ($IdentityFile) { $sshArgs += @("-i", $IdentityFile) }
$sshArgs += @($Server, "echo $b64 | base64 -d | bash")
& ssh @sshArgs
if ($LASTEXITCODE -ne 0) { throw "远端安装失败，看上面的输出" }

Write-Host ""
Write-Host "完成。看日志：" -ForegroundColor Green
Write-Host "  ssh -p $SshPort $Server 'journalctl -u $ServiceName -f'"
