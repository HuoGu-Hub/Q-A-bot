#!/bin/bash
# 每天检查知识库数据库的完整性。正常时只记一行日志，异常时写 journal 并以非 0 退出。
set -uo pipefail

DB=/opt/qqbot/server/data/qa/qqbot.sqlite
LOG=/var/log/qqbot-dbcheck.log
STAMP=$(date '+%F %T')

# -cmd ".timeout" 让检查在应用正在写的时候等锁，而不是误报
CHECK=$(sqlite3 -cmd ".timeout 10000" "$DB" 'pragma quick_check;' 2>&1 || true)

if [ "$CHECK" = "ok" ]; then
  echo "$STAMP ok" >> "$LOG"
  exit 0
fi

{
  echo "$STAMP 异常"
  echo "$CHECK" | head -10
} | tee -a "$LOG" | logger -t qqbot-dbcheck -p user.err

exit 1
