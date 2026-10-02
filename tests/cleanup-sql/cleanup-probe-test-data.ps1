# ============================================================
#  清理 probe-kb-e2e.mjs 留在库里的测试数据（Windows / PowerShell 版）
#
#  为什么需要这个包装脚本：
#    原来的用法是 `sqlite3 db < file.sql` —— **PowerShell 不支持 `<` 输入重定向**
#    （报「"<"运算符是为将来使用而保留的」，而且 sqlite3 根本不会被执行）。
#    这里改用 sqlite3 自己的 `.read` 命令，等价、且没有重定向。
#
#  用法：
#    powershell -ExecutionPolicy Bypass -File tests/cleanup-sql/cleanup-probe-test-data.ps1
#  或在 PowerShell 里：
#    ./tests/cleanup-sql/cleanup-probe-test-data.ps1
#
#  ⚠️ 在**宿主机**上跑（不要在 dsh 容器里：工作区是 9p 挂载，WAL 起不来，
#     会直接报 disk I/O error）。bot 正在运行也没关系 —— SQLite 自己会串行化，
#     而且这个脚本只删 id 1~10 / group_id 700000~700004 这些确定的测试行。
# ============================================================
$ErrorActionPreference = "Stop"

# tests/cleanup-sql/ → 上两级就是仓库根
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$db   = Join-Path $root "server/data/qa/qqbot.sqlite"
$sql  = Join-Path $PSScriptRoot "cleanup-probe-test-data.sql"

Write-Host "数据库：$db"
if (-not (Test-Path -LiteralPath $db)) {
  Write-Host "找不到数据库文件，请确认路径（当前是在仓库根目录跑的吗？）" -ForegroundColor Red
  exit 1
}

if (-not (Get-Command sqlite3 -ErrorAction SilentlyContinue)) {
  Write-Host "PATH 里没有 sqlite3。先装一个再重跑：" -ForegroundColor Yellow
  Write-Host "  winget install SQLite.SQLite        # 装完记得重开一个终端"
  Write-Host "  或 scoop install sqlite"
  exit 1
}

# ---------- 备份（主库 + WAL/SHM，缺了 WAL 备份会落后于主库）----------
$bak = "$db.before-cleanup"
Copy-Item -LiteralPath $db -Destination $bak -Force
foreach ($ext in @("-wal", "-shm")) {
  if (Test-Path -LiteralPath "$db$ext") {
    Copy-Item -LiteralPath "$db$ext" -Destination "$bak$ext" -Force
  }
}
Write-Host "已备份到 $bak" -ForegroundColor Green
Write-Host ""

# ---------- 执行（用 .read，绕开 PowerShell 没有 < 这件事）----------
$sqlForSqlite = $sql -replace "\\", "/"
& sqlite3 $db ".read $sqlForSqlite"

Write-Host ""
Write-Host "完成。上面最后几条 SELECT 就是核对结果：" -ForegroundColor Green
Write-Host "  残留测试行 / 孤儿 raw / 孤儿 keyword —— 都应该是 0"
Write-Host "  独立群 —— 应该等于真实群数（清理前是 8，其中 5 个是假群号）"
Write-Host "  最后一条的「大屏群」—— 就是数据大屏「提问用户 / 群」里那个数"