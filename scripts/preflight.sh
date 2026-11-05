#!/usr/bin/env bash
# ============================================================
#  上线前检查（preflight）
#
#  跑一遍所有该跑的东西，任何一项失败就退出非零。
#  部署前执行这个，比 CI 更直接 —— 你就是那道 CI。
#
#  用法：./scripts/preflight.sh [--skip-web] [--skip-golden] [--skip-eval]
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

SKIP_WEB=0
SKIP_GOLDEN=0
SKIP_EVAL=0
for arg in "$@"; do
  case "$arg" in
    --skip-web)    SKIP_WEB=1 ;;
    --skip-golden) SKIP_GOLDEN=1 ;;
    --skip-eval)   SKIP_EVAL=1 ;;
    *) echo "未知参数：$arg（支持 --skip-web / --skip-golden / --skip-eval）" >&2; exit 2 ;;
  esac
done

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
step "0/6  检查 .env 关键项"
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
step "1/6  后端测试"
if ./scripts/build-server.sh -q clean test 2>&1 | tail -3; then
  ok "测试通过（隔离副本 .toolchain/server-ci/）"
else
  bad "测试失败"
fi

# ---------- 2. 后端打包 ----------
step "2/6  后端打包"
if ./scripts/build-server.sh -q clean package -DskipTests 2>&1 | tail -3; then
  JAR=$(ls -1 .toolchain/server-ci/target/qqbot-server-*.jar 2>/dev/null | grep -v original | head -1)
  [ -n "$JAR" ] && ok "产物 $JAR" || bad "没找到 jar"
else
  bad "打包失败"
fi

# ---------- 3. 前端 ----------
if [ "$SKIP_WEB" = "1" ]; then
  step "3/6  前端（已跳过）"
else
  step "3/6  前端类型检查与构建"
  if ./scripts/build-web.sh > /tmp/preflight-web.log 2>&1; then
    ok "构建完成并已同步到 server/web-dist"
  else
    bad "前端构建失败（详见 /tmp/preflight-web.log）"
    tail -15 /tmp/preflight-web.log | sed 's/^/    /'
  fi
fi

# ---------- 4. 配置自检 ----------
step "4/6  配置自检"
# 2026-10-08：块、向量、词条表、问答记录现在都在同一个 SQLite 里。
# 旧检查指向的 data/kb/index.bin 与 pages.jsonl 已经不存在 —— 那两个检查恒定失败，
# 而"永远红的检查"等于没有检查，所以删掉。这里只确认库在，
# 块数与向量数由下面的门禁步骤打印出来。
DB="server/data/qa/qqbot.sqlite"
if [ -f "$DB" ]; then
  ok "语料与问答库存在（$(du -h "$DB" | cut -f1)）"
else
  bad "语料库缺失：$DB —— 知识库检索会静默失效"
fi

# ---------- 5. 检索回归门禁 ----------
if [ "$SKIP_GOLDEN" = "1" ]; then
  step "5/6  检索回归门禁（已跳过）"
  printf '  \033[33m⚠ 跳过门禁：本次发布没有检索质量基线背书\033[0m\n'
else
  step "5/6  检索回归门禁"
  if ./scripts/golden-gate.sh; then
    ok "黄金集底线守住"
  else
    bad "检索质量低于底线，或门禁无法度量（详见 .toolchain/verify/golden-gate.log）"
  fi
fi

# ---------- 6. 评测门禁 ----------
if [ "$SKIP_EVAL" = "1" ]; then
  step "6/6  评测门禁（已跳过）"
  printf '  \033[33m⚠ 跳过评测门禁：本次发布没有评测结论与判定器一致率背书\033[0m\n'
else
  step "6/6  评测门禁"
  EVAL_DB="${EVAL_DB:-$ROOT/server/data/qa/eval.sqlite}"
  if [ ! -f "$EVAL_DB" ]; then
    bad "评测库缺失：$EVAL_DB —— 没有评测结论，无法判断这次是变好还是变差"
  else
    export EVAL_DB
    export EVAL_MIN_AGREEMENT="${EVAL_MIN_AGREEMENT:-0.8}"
    BATCH="${EVAL_GATE_BATCH:-}"
    set +e
    ./scripts/eval-gate.sh "${BATCH}" 2>&1 | sed 's/^/    /'
    GATE_RC=${PIPESTATUS[0]}
    set -e
    case "$GATE_RC" in
      0) ok "评测通过（阈值 $EVAL_MIN_AGREEMENT）" ;;
      1) ok "评测有警告，未达到阻断条件" ;;
      2) bad "评测阻断：批次或判定器一致率不达标" ;;
      *) bad "评测无法判定（缺批次、缺校准历史或库不可读）" ;;
    esac
  fi
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
