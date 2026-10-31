<#
.SYNOPSIS
  只发后端：编译 jar、上传替换、重启服务，不动前端。

.DESCRIPTION
  适合只改了 Java、application.yml、system.md 的时候。包里不带 web-dist，
  服务器上现有的前端保持原样；上传量和耗时都比完整发版小。

  服务会停一下再起来，中断约数秒。只改了页面、样式、前端逻辑请用
  release-web.ps1，前端后端都要发请用 release.ps1。

.PARAMETER WithNginx
  同时安装 deploy/nginx/qqbot-domain.conf 并 reload nginx。默认不碰线上 Nginx。

.PARAMETER Server
  目标，形如 root@1.2.3.4。省略时只构建和打包。

.PARAMETER SkipBuild
  不重新编译，直接用 .toolchain/release 下现成的 jar。

.PARAMETER SkipUpload
  只产包不上传。

.PARAMETER NoRestart
  传完文件不重启，自己挑时间 systemctl restart qqbot。

.EXAMPLE
  .\scripts\release-server.ps1 -Server root@1.2.3.4
  .\scripts\release-server.ps1 -SkipBuild -Server root@1.2.3.4
#>
[CmdletBinding()]
param(
  [string]$Server,
  [int]$SshPort = 22,
  [string]$IdentityFile,
  [switch]$SkipBuild,
  [switch]$SkipUpload,
  [switch]$NoRestart,
  [switch]$WithNginx,
  [string]$NginxConf = "/etc/nginx/sites-available/qqbot.conf"
)

# 参数透传给 release.ps1，行为只有一处实现，几个入口不会各自漂移
$forward = @{ ServerOnly = $true; SshPort = $SshPort }
if ($Server)       { $forward.Server = $Server }
if ($IdentityFile) { $forward.IdentityFile = $IdentityFile }
if ($SkipBuild)    { $forward.SkipBuild = $true }
if ($SkipUpload)   { $forward.SkipUpload = $true }
if ($NoRestart)    { $forward.NoRestart = $true }
if ($WithNginx)    { $forward.WithNginx = $true; $forward.NginxConf = $NginxConf }

& (Join-Path $PSScriptRoot 'release.ps1') @forward
exit $LASTEXITCODE
