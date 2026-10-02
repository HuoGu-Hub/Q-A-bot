#!/usr/bin/env bash
# ============================================================
#  上线前检查（preflight）
#
#  跑一遍所有该跑的东西，任何一项失败就退出非零。
#  部署前执行这个，比 CI 更直接 —— 你就是那道 CI。
#
#  用法：./scripts/preflight.sh [--skip-web]
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

SKIP_WEB=0
[ "${1:-}" = "--skip-web" ] && SKIP_WEB=1

# ---------- 工具链 ----------
export JAVA_HOME="${JAVA_HOME:-$ROOT/.toolchain/jdk-17.0.2}"
export PATH="$JAVA_HOME/bin:$ROOT/.toolchain/apache-maven-3.9.9/bin:$PATH"
# ⚠️ 后端构建一律走 ./scripts/build-server.sh（复制到 .toolchain/server-ci/ 再编）。
#    绝不要直接 mvn -f server/pom.xml：宿主机 IDEA 跑的 classpath 就是
#    server/target/classes，容器这边一 clean/重编就会把它从运行中的 JVM
#    脚底下抽走 → NoClassDefFoundError（2026-10-01 事故，详见该脚本头注释）。

FAILED=0
step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
ok()   { printf '  \033[32m✅ %s\033[0m\n' "$1"; }
bad()  { printf '  \033[31m❌ %s\033[0m\n' "$1"; FAILED=1; }

# ---------- 0. 密钥检查 ----------
step "0/4  检查 .env 关键项"
if [ ! -f .env ]; then
  bad ".env 不存在（应该从 .env.example 复制）"
else
  check_key() {
    if grep -qE "^$1=.+" .env; then ok "$1 已设置"; else bad "$1 为空 —— $2"; fi
  }
  check_key ONEBOT_TOKEN       "NapCat 的 HTTP Token"
  check_key ADMIN_PASSWORD     "不设的话管理后台会整个返回 503"
  check_key SILICONFLOW_API_KEY "向量模型，知识库检索要用"
  check_key OPENCODE_GO_API_KEY "对话模型"
fi

# ---------- 1. 后端测试 ----------
step "1/4  后端测试"
if ./scripts/build-server.sh -q clean test 2>&1 | tail -3; then
  ok "测试通过（隔离副本 .toolchain/server-ci/）"
else
  bad "测试失败"
fi

# ---------- 2. 后端打包 ----------
step "2/4  后端打包"
if ./scripts/build-server.sh -q clean package -DskipTests 2>&1 | tail -3; then
  JAR=$(ls -1 .toolchain/server-ci/target/qqbot-server-*.jar 2>/dev/null | grep -v original | head -1)
  [ -n "$JAR" ] && ok "产物 $JAR" || bad "没找到 jar"
else
  bad "打包失败"
fi

# ---------- 3. 前端 ----------
if [ "$SKIP_WEB" = "1" ]; then
  step "3/4  前端（已跳过）"
else
  step "3/4  前端类型检查与构建"
  if ./scripts/build-web.sh > /tmp/preflight-web.log 2>&1; then
    ok "构建完成并已同步到 server/web-dist"
  else
    bad "前端构建失败（详见 /tmp/preflight-web.log）"
    tail -15 /tmp/preflight-web.log | sed 's/^/    /'
  fi
fi

# ---------- 4. 配置自检 ----------
step "4/4  配置自检"
if [ -f server/data/kb/index.bin ]; then
  SIZE=$(du -h server/data/kb/index.bin | cut -f1)
  ok "向量索引存在（$SIZE）"
else
  bad "向量索引缺失 —— 知识库检索会静默失效，跑：--app.kb.index.enabled=true"
fi
# 2026-09-27：词条表（中文名 / 核对状态）已从 TSV 文件搬进 SQLite 单表 kb_term，
# 服务启动时按 pages.jsonl 自动补齐缺失的页面。所以这里只检查语料侧，
# 词条表由服务自己管（见 KbTermStore.reconcile）。
if [ -f server/data/kb/pages.jsonl ]; then
  N=$(wc -l < server/data/kb/pages.jsonl)
  ok "页面清单 $N 页（词条表启动时按它补齐）"
else
  bad "pages.jsonl 缺失 —— 词条表无法自动补齐新页面"
fi

# ---------- 结果 ----------
printf '\n'
if [ "$FAILED" = "0" ]; then
  printf '\033[32m\033[1m全部通过，可以部署。\033[0m\n'
  exit 0
else
  printf '\033[31m\033[1m有检查未通过，先修好再部署。\033[0m\n'
  exit 1
fi
