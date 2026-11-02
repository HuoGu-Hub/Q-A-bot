<#
.SYNOPSIS
  把本地导出的 wiki 语料快照合并到线上 SQLite，只替换 wiki 表。

.DESCRIPTION
  快照由本地导入 + wiki-export.py 生成（.sqlite.gz）。流程：
    上传快照 → 停服务 → SQLite 一致性备份 → 事务内替换 wiki 表 → 启服务。
  其他表（问答、命令、设置、地图）一行不动。

  可选的 -ImageDelta 是 wiki-image-delta.py 生成的 tar.gz，会先解包进配图目录，
  这样新块正文里的 [[img:wiki:...]] 标记有文件可指。
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Snapshot,
  [Parameter(Mandatory = $true)][string]$Server,
  [int]$SshPort = 22,
  [string]$IdentityFile,
  [string]$ServiceName = "qqbot",
  [string]$ServiceUser = "qqbot",
  [string]$DbPath = "/opt/qqbot/server/data/qa/qqbot.sqlite",
  [string]$WikiImageDir = "/opt/qqbot/server/data/wiki-images",
  [string]$ImageDelta
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

if ($ImageDelta) {
  if (-not (Test-Path $ImageDelta)) { throw "找不到配图增量：$ImageDelta" }
  $imagePath = (Resolve-Path $ImageDelta).Path
  if (-not $imagePath.EndsWith(".tar.gz")) { throw "配图增量必须是 .tar.gz：$imagePath" }
  Write-Host "==> 上传配图增量 $((Get-Item $imagePath).Length / 1MB) MB" -ForegroundColor Cyan
  & scp @scpArgs $imagePath "$($Server):/root/wiki-image-delta.tar.gz"
  if ($LASTEXITCODE -ne 0) { throw "scp 配图上传失败" }
}

$remote = @'
set -e
cd /root
if [ -f /root/wiki-image-delta.tar.gz ]; then
  echo "==> 解包配图增量"
  mkdir -p __WIKI_IMG__
  tar xzf /root/wiki-image-delta.tar.gz -C __WIKI_IMG__
  chown -R __USER__:__USER__ __WIKI_IMG__
  rm -f /root/wiki-image-delta.tar.gz
fi

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
for _ in $(seq 1 30); do
  state=$(systemctl is-active __SERVICE__ || true)
  [ "$state" = "active" ] && break
  sleep 2
done
systemctl is-active __SERVICE__
echo "完成；回滚备份：$BACKUP"
'@

$remote = $remote.Replace("__MERGE_SQL__", $mergeSql)
$remote = $remote.Replace("__DB__", $DbPath)
$remote = $remote.Replace("__SERVICE__", $ServiceName)
$remote = $remote.Replace("__USER__", $ServiceUser)
$remote = $remote.Replace("__WIKI_IMG__", $WikiImageDir)
$remote = ConvertTo-Lf $remote
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remote))

Write-Host "==> 远程合并" -ForegroundColor Cyan
& ssh @sshArgs $Server "echo $b64 | base64 -d | bash"
if ($LASTEXITCODE -ne 0) { throw "远程合并失败" }
Write-Host "完成。" -ForegroundColor Green
