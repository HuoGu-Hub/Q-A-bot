# ============================================================
#  wiki 批量拉取 + 清洗 —— PowerShell 入口
#
#  真正的逻辑在 scripts/wiki-import.sh，这里只负责找到 bash 并把参数原样传过去，
#  这样在 PowerShell 里也能用同一套参数：
#
#    .\scripts\wiki-import.ps1 --dry-run     # 先空跑看数量（不写库、不花钱）
#    .\scripts\wiki-import.ps1               # 拉取 + 清洗（只处理 wiki 上变过的页）
#    .\scripts\wiki-import.ps1 --force       # 全部重洗（清洗规则变了用这条）
#    .\scripts\wiki-import.ps1 --no-render   # 关掉渲染兜底（更快）
#    .\scripts\wiki-import.ps1 --purge       # 清掉全部 wiki 文章块，可重来
#
#  跑完要重启服务（索引只在进程内重载）。日志：.toolchain/verify/wiki-import.log
# ============================================================
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$bash = @(
    "C:\Program Files\Git\bin\bash.exe",
    "C:\Program Files\Git\usr\bin\bash.exe",
    "C:\Program Files (x86)\Git\bin\bash.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $bash) {
    Write-Host "找不到 bash（需要 Git for Windows）。请改用 Git Bash 执行 scripts/wiki-import.sh" -ForegroundColor Red
    exit 2
}

& $bash (Join-Path $root "scripts/wiki-import.sh") @Args
exit $LASTEXITCODE
