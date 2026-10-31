#!/usr/bin/env bash
# 轻量密钥扫描：只扫 git 跟踪的文本文件，避免把 .env / 本地缓存算进来。
# 已知格式覆盖云厂商、GitHub、Slack、OpenAI 与私钥头；发现即失败。
set -euo pipefail
cd "$(dirname "$0")/.."

PATTERN='(BEGIN (RSA|OPENSSH|EC|DSA) PRIVATE KEY|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|sk-[A-Za-z0-9]{32,}|AIza[0-9A-Za-z_-]{35})'

if git grep -nEI "$PATTERN" -- . \
    ':!docs/openapi.baseline.json' \
    ':!web/pnpm-lock.yaml'; then
  echo "发现疑似密钥，请改用环境变量并轮换已泄露的值。" >&2
  exit 1
fi

echo "未发现已知格式的密钥"
