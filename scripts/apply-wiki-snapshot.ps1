<#
.SYNOPSIS
  把本地导出的 wiki 语料快照合并到线上 SQLite，只替换 wiki 表。

.DESCRIPTION
  快照由本地导入 + wiki-export.py 生成（.sqlite.gz）。流程：
    上传快照 → 停服务 → SQLite 一致性备份 → 事务内替换 wiki 表 → 启服务。
  其他表（问答、命令、设置、地图）一行不动。
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Snapshot,
  [Parameter(Mandatory = $true)][string]$Server,
  [int]$SshPort = 22,
  [string]$IdentityFile,
  [string]$ServiceName = "qqbot",
  [string]$DbPath = "/opt/qqbot/server/data/qa/qqbot.sqlite"
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $Snapshot)) { throw "找不到快照：$Snapshot" }
$snapshotPath = (Resolve-Path $Snapshot).Path
if (-not $snapshotPath.EndsWith(".sqlite.gz")) { throw "快照必须是 .sqlite.gz：$snapshotPath" }

$root = Split-Path -Parent $PSScriptRoot
$sqlPath = Join-Path $root "scripts\wiki-merge.sql"
if (-not (Test-Path $sqlPath)) { throw "找不到 $sqlPath" }
$mergeSql = [IO.File]::ReadAllText($sqlPath)

function ConvertTo-Lf([string]$Text) {
  return $Text.Replace([string][char]13 + [string][char]10, [string][char]10).Replace([string][char]13, [string][char]10)
}

$scpArgs = @("-P", $SshPort)
$sshArgs = @("-p", $SshPort, "-o", "BatchMode=yes")
if ($IdentityFile) {
  $scpArgs += @("-i", $IdentityFile)
  $sshArgs += @("-i", $IdentityFile)
}

Write-Host "==> 上传快照 $((Get-Item $snapshotPath).Length / 1MB) MB" -ForegroundColor Cyan
& scp @scpArgs $snapshotPath "$($Server):/root/wiki-snapshot.sqlite.gz"
if ($LASTEXITCODE -ne 0) { throw "scp 上传失败" }

$remote = @'
set -e
cd /root
cat > /root/wiki-merge.sql <<'SQL'
__MERGE_SQL__
SQL
gunzip -f /root/wiki-snapshot.sqlite.gz
if [ ! -s /root/wiki-snapshot.sqlite ]; then
  echo "快照解压失败" >&2
  exit 1
fi

echo "==> 停服务 __SERVICE__"
systemctl stop __SERVICE__ || true
TS=$(date +%Y%m%d%H%M%S)
BACKUP="/root/qqbot-pre-wiki-merge-$TS.sqlite"
echo "==> 备份 $BACKUP"
sqlite3 __DB__ ".backup '$BACKUP'"

echo "==> 合并 wiki 表"
if ! sqlite3 __DB__ < /root/wiki-merge.sql; then
  echo "合并失败，恢复备份 $BACKUP" >&2
  sqlite3 __DB__ ".restore '$BACKUP'" || cp -f "$BACKUP" __DB__
  systemctl start __SERVICE__ || true
  exit 1
fi

echo "==> 完整性检查"
sqlite3 __DB__ "PRAGMA integrity_check;"

rm -f /root/wiki-snapshot.sqlite
echo "==> 启动服务 __SERVICE__"
systemctl start __SERVICE__
sleep 5
systemctl is-active __SERVICE__
echo "完成；回滚备份：$BACKUP"
'@

$remote = $remote.Replace("__MERGE_SQL__", $mergeSql)
$remote = $remote.Replace("__DB__", $DbPath)
$remote = $remote.Replace("__SERVICE__", $ServiceName)
$remote = ConvertTo-Lf $remote
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remote))

Write-Host "==> 远程合并" -ForegroundColor Cyan
& ssh @sshArgs $Server "echo $b64 | base64 -d | bash"
if ($LASTEXITCODE -ne 0) { throw "远程合并失败" }
Write-Host "完成。" -ForegroundColor Green
