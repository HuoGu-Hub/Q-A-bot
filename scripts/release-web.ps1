<#
.SYNOPSIS
  只发前端：构建 web-dist、上传替换、不重启服务。

.DESCRIPTION
  前端由后端从 file:./web-dist/ 直接读磁盘，替换文件即生效，所以不需要
  重新编译 jar，也不需要重启进程。适合只改了页面、样式、前端逻辑的时候。

  服务全程不停，群里正在用的机器人不受影响；浏览器按 Ctrl+F5 强刷。

  改了 Java、application.yml、deploy 下的配置，请用 release.ps1 发完整包。

.PARAMETER Server
  目标，形如 root@1.2.3.4。省略时只构建和打包。

.PARAMETER SkipBuild
  不重新构建，直接用现有的 server/web-dist 上传。

.PARAMETER SkipUpload
  只产包不上传。

.EXAMPLE
  .\scripts\release-web.ps1 -Server root@1.2.3.4
  .\scripts\release-web.ps1 -SkipBuild -Server root@1.2.3.4
#>
[CmdletBinding()]
param(
  [string]$Server,
  [int]$SshPort = 22,
  [string]$IdentityFile,
  [switch]$SkipBuild,
  [switch]$SkipUpload
)

# 参数透传给 release.ps1，行为只有一处实现，两个入口不会各自漂移
$forward = @{ WebOnly = $true; SshPort = $SshPort }
if ($Server)       { $forward.Server = $Server }
if ($IdentityFile) { $forward.IdentityFile = $IdentityFile }
if ($SkipBuild)    { $forward.SkipBuild = $true }
if ($SkipUpload)   { $forward.SkipUpload = $true }

& (Join-Path $PSScriptRoot 'release.ps1') @forward
exit $LASTEXITCODE
