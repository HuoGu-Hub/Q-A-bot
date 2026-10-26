#Requires -Version 5.1
<#
.SYNOPSIS
  把本地音频当作 QQ「语音条」发出去（不是文件）。

.DESCRIPTION
  走 OneBot 11 的 record 消息段，音频以 base64:// 内联给协议端，由 NapCat 转成 SILK。

  ⚠️ 两条硬规则：
    1. 给 MP3 / WAV / AMR，不要给 SILK。SILK 会被直通发送，NapCat 探测不到时长，
       结果是一条 0 秒语音，且不报错。
    2. 语音独立成条（本脚本也是这么发的），不要和文本或图片拼在同一条消息里。

.EXAMPLE
  ./scripts/voice-send.ps1 -Group 1046213033 -File .\clip.mp3

.EXAMPLE
  ./scripts/voice-send.ps1 -User 10001 -File .\clip.wav -ApiBase http://127.0.0.1:3000
#>
[CmdletBinding(DefaultParameterSetName = 'Group')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Group')]
    [long] $Group,

    [Parameter(Mandatory, ParameterSetName = 'User')]
    [long] $User,

    [Parameter(Mandatory)]
    [string] $File,

    [string] $ApiBase,
    [string] $Token,
    [int] $MaxKb = 8192
)

$ErrorActionPreference = 'Stop'

# ---------- 配置：命令行 > 环境变量 > 仓库根 .env ----------
function Read-DotEnv {
    param([string] $Root)
    $path = Join-Path $Root '.env'
    if (-not (Test-Path $path)) { return @{} }
    $map = @{}
    Get-Content $path | Where-Object { $_ -match '^\s*[A-Za-z_][A-Za-z0-9_]*\s*=' } | ForEach-Object {
        $k, $v = $_ -split '=', 2
        $map[$k.Trim()] = $v.Trim()
    }
    return $map
}

$root = Split-Path -Parent $PSScriptRoot
$envMap = Read-DotEnv -Root $root
if (-not $ApiBase) { $ApiBase = $envMap['ONEBOT_API_BASE'] }
if (-not $ApiBase) { $ApiBase = 'http://127.0.0.1:3000' }
if (-not $Token) { $Token = $envMap['ONEBOT_TOKEN'] }

if (-not (Test-Path -LiteralPath $File)) {
    throw "找不到音频文件：$File"
}
$item = Get-Item -LiteralPath $File
$ext = $item.Extension.ToLowerInvariant()
if ($ext -in @('.silk', '.slk')) {
    throw "不要发 SILK：NapCat 会直通发送，时长探测为 0，客户端显示 0 秒。请改用 MP3/WAV/AMR。"
}
if ($ext -notin @('.mp3', '.wav', '.amr', '.m4a', '.ogg', '.flac', '.aac')) {
    throw "不认识的音频扩展名 $ext。支持：mp3 / wav / amr / m4a / ogg / flac / aac"
}
if ($item.Length -gt $MaxKb * 1024) {
    throw "音频太大：$([math]::Round($item.Length / 1KB, 1)) KB，上限 $MaxKb KB"
}

# ---------- 组装 record 段 ----------
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($item.FullName))
$segment = @{ type = 'record'; data = @{ file = "base64://$b64" } }

if ($PSCmdlet.ParameterSetName -eq 'Group') {
    $action = 'send_group_msg'
    $payload = @{ group_id = $Group; message = @($segment) }
    $target = "群 $Group"
} else {
    $action = 'send_private_msg'
    $payload = @{ user_id = $User; message = @($segment) }
    $target = "QQ $User"
}

$body = [Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Depth 10 -Compress))
$headers = @{}
if ($Token) { $headers['Authorization'] = "Bearer $Token" }

Write-Host "发送语音到 $target：$($item.Name)（$([math]::Round($item.Length / 1KB, 1)) KB，请求 $([math]::Round($body.Length / 1KB, 1)) KB）"

$sw = [Diagnostics.Stopwatch]::StartNew()
try {
    $resp = Invoke-RestMethod -Uri "$ApiBase/$action" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 120
} catch {
    # 协议端返回的错误正文藏在 ErrorDetails 里，默认异常消息只有状态码
    $detail = $_.ErrorDetails.Message
    $suffix = ''
    if ($detail) { $suffix = [Environment]::NewLine + $detail }
    throw "调用 $action 失败：$($_.Exception.Message)$suffix"
}
$sw.Stop()

if ($resp.status -ne 'ok') {
    throw "协议端拒绝：$($resp | ConvertTo-Json -Depth 6 -Compress)"
}
$id = $resp.data.message_id
Write-Host "已发出，message_id=$id，耗时 $($sw.ElapsedMilliseconds) ms" -ForegroundColor Green
Write-Host "如果客户端显示 0 秒，检查是否误发了 SILK；如果显示完整时长，链路正常。" -ForegroundColor DarkGray
