#!/usr/bin/env bash
# 评测门禁 —— 给 CI 与发版脚本读退出码。
#
# 退出码：0 通过，1 警告，2 阻断，3 无法判定。
# 用法：
#   ./scripts/eval-gate.sh            # 最近一个已完成批次
#   ./scripts/eval-gate.sh 12         # 指定批次
# 环境变量：
#   EVAL_DB   数据库路径，默认 server/data/qa/qqbot.sqlite
#
# 为什么直接读库而不是调接口：CI 没有后台会话，为一个布尔结论去登录
# 后台既难写，也不该给流水线账号。判定逻辑与页面完全一致（基线优先）。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 评测库是独立文件（默认与主库同目录的 eval.sqlite）
DB="${EVAL_DB:-$ROOT/server/data/eval/eval.sqlite}"
BATCH="${1:-}"

# 取最新那个 jar：target 里可能同时躺着旧版本
JAR="$(ls -t "$ROOT"/server/target/qqbot-server-*.jar 2>/dev/null | head -n 1 || true)"
if [ -z "$JAR" ]; then
  echo "找不到 server/target/qqbot-server-*.jar，先执行 (cd server && mvn -DskipTests package)" >&2
  exit 3
fi
if [ ! -f "$DB" ]; then
  echo "找不到数据库：$DB（可用 EVAL_DB 指定）" >&2
  exit 3
fi

ARGS=(--db "$DB")
if [ -n "$BATCH" ]; then
  ARGS+=(--batch "$BATCH")
fi

java -Dloader.main=com.example.qqbot.EvalGateCli -cp "$JAR" \
  org.springframework.boot.loader.launch.PropertiesLauncher "${ARGS[@]}"
