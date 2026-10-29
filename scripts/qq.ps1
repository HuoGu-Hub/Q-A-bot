# qqbot 本地运维入口，任意目录可用。
# 安装一次，在 $PROFILE 里加一行：
#   . 'E:\Project\agentWs\qqbot\scripts\qq.ps1'
# 服务器侧配套脚本：deploy/bin/qq，装到 /usr/local/bin/qq。

$script:QqRepoRoot = Split-Path -Parent $PSScriptRoot
$script:QqRelease  = Join-Path $script:QqRepoRoot 'scripts\release.ps1'
if (-not $env:QQBOT_SERVER) { $env:QQBOT_SERVER = 'qqbot' }

function Invoke-QqRemote {
  param([string[]]$RemoteArgs)
  & ssh $env:QQBOT_SERVER "qq $($RemoteArgs -join ' ')"
}

function qq {
  [CmdletBinding()]
  param(
    [Parameter(Position = 0)][string]$Command = 'help',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Rest
  )

  switch ($Command.ToLower()) {
    'up'     { & $script:QqRelease -Server $env:QQBOT_SERVER }
    'pack'   { & $script:QqRelease -SkipUpload }
    'fast'   { & $script:QqRelease -SkipBuild -Server $env:QQBOT_SERVER }
    'soft'   { & $script:QqRelease -Server $env:QQBOT_SERVER -NoRestart }
    'sh'     { & ssh $env:QQBOT_SERVER }

    'setup'  {
      $file = Join-Path $script:QqRepoRoot 'deploy\bin\qq'
      & scp $file "$($env:QQBOT_SERVER):/usr/local/bin/qq"
      & ssh $env:QQBOT_SERVER 'chmod +x /usr/local/bin/qq && qq where'
    }

    'db'     {
      if ($Rest.Count -gt 0) { ($Rest -join ' ') | & ssh $env:QQBOT_SERVER 'qq db' }
      else { '' | & ssh $env:QQBOT_SERVER 'qq db' }
    }

    'nap'    { Invoke-QqRemote (@('nap') + $Rest) }
    'st'     { Invoke-QqRemote @('st') }
    'logs'   { Invoke-QqRemote (@('logs') + $Rest) }
    'log'    { Invoke-QqRemote @('log') }
    'err'    { Invoke-QqRemote (@('err') + $Rest) }
    'health' { Invoke-QqRemote @('health') }
    'bak'    { Invoke-QqRemote @('bak') }
    'disk'   { Invoke-QqRemote @('disk') }
    'check'  { Invoke-QqRemote @('check') }
    'ping'   { Invoke-QqRemote @('ping') }

    'where'  {
      "仓库    $script:QqRepoRoot"
      "服务器  $env:QQBOT_SERVER"
    }

    default  {
      @'
qq —— qqbot 本地运维入口，任意目录可用

  发版
  qq up        构建 + 打包 + 上传 + 重启
  qq pack      只构建打包，不上传
  qq fast      跳过构建，只上传重启
  qq soft      只传文件，不重启

  服务器
  qq setup     首次：把服务器侧 qq 装到 /usr/local/bin
  qq sh        登进服务器
  qq st        服务、内存、端口、容器
  qq logs [n]  最近 n 行日志
  qq log       实时跟日志
  qq err       只看报错
  qq health    健康自检
  qq ping      查 NapCat 协议层
  qq nap ...   NapCat 容器，ps|logs|restart|sh
  qq bak       备份数据库
  qq db "SQL"  查库
  qq check     出事收现场
  qq disk      磁盘与日志占用
  qq where     打印路径与目标机
'@
    }
  }
}