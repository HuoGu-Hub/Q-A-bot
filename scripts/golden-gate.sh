#!/usr/bin/env bash
# ============================================================
#  检索回归门禁（golden gate）
#
#  一句话：把"检索有没有变差"变成一条命令，退出码就是结论。
#
#  为什么需要它（2026-10-08）：
#    RetrievalGoldenTest 早就存在，但它默认是**度量工具** —— 缺语料、缺 key、
#    向量路调不通，一律 Assumptions 静默跳过，mvn test 照样绿。
#    而"测不出结果时仍然显示绿色"的门禁，比没有门禁更糟：
#    它给出的是"已经守住了"的假信号。所以这里显式打开
#    -Dqqbot.golden.gate=true，让"测不了"变成失败。
#
#  门槛口径：**底线**，不是目标。数值与来由见 RetrievalGoldenTest 里的注释，
#  含义是"不许比现在更差"，不是"要做到多好"。
#
#  用法：
#    ./scripts/golden-gate.sh                # 生产路径，重排生效
#    ./scripts/golden-gate.sh --rerank off   # 对比用：关掉重排
#
#  退出码：0 通过；1 未通过；2 前置条件不满足（同样算未通过）
#  日志与逐条明细写在 .toolchain/verify/
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

RERANK=on
if [ "$#" -gt 0 ]; then
  if [ "$1" != "--rerank" ]; then
    echo "未知参数：$1（只支持 --rerank on|off）" >&2
    exit 2
  fi
  if [ "$#" -ge 2 ]; then RERANK="$2"; fi
fi
case "$RERANK" in
  on|off) ;;
  *) echo "--rerank 只能是 on 或 off，收到：$RERANK" >&2; exit 2 ;;
esac

VERIFY="$ROOT/.toolchain/verify"
LOG="$VERIFY/golden-gate.log"
mkdir -p "$VERIFY"

say() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
ok()  { printf '  \033[32m✅ %s\033[0m\n' "$1"; }
bad() { printf '  \033[31m❌ %s\033[0m\n' "$1"; }

# ---------- 前置条件：缺了什么就退出 2，绝不"跳过当通过" ----------
say "0/2  前置条件"
MISSING=0
if [ -f "$ROOT/tests/golden/retrieval-golden.tsv" ]; then
  ok "黄金集存在"
else
  bad "缺 tests/golden/retrieval-golden.tsv"; MISSING=1
fi
if [ -f "$ROOT/server/data/qa/qqbot.sqlite" ]; then
  ok "生产语料存在"
else
  bad "缺 server/data/qa/qqbot.sqlite"; MISSING=1
fi
if [ -f "$ROOT/.env" ] && grep -qE '^SILICONFLOW_API_KEY=.+' "$ROOT/.env"; then
  ok "SILICONFLOW_API_KEY 已设置（本次会真的调用向量与重排模型）"
else
  bad ".env 里的 SILICONFLOW_API_KEY 为空 —— 向量路与重排都起不来"; MISSING=1
fi
if [ "$MISSING" = "1" ]; then
  bad "前置条件不满足，门禁无法度量 —— 这不是通过"
  exit 2
fi

# ---------- 跑隔离构建里的那一个测试 ----------
say "1/2  跑黄金集（重排 $RERANK）"
MVN_OK=0
# -Dqqbot.repo.root 必须显式传：隔离构建里 surefire fork 出来的 JVM，
# 工作目录未必落在工程根下（实测从后台作业启动时就不是），
# 而 RetrievalGoldenTest 要靠它找到黄金集与生产语料。
# 门禁不能依赖"恰好从哪个目录启动"。
if ./scripts/build-server.sh -q test \
      -Dtest=RetrievalGoldenTest -DfailIfNoTests=false \
      -Dqqbot.golden.gate=true -Dqqbot.golden.rerank="$RERANK" \
      -Dqqbot.repo.root="$ROOT" 2>&1 | tee "$LOG"; then
  MVN_OK=1
  ok "测试进程成功退出"
else
  bad "测试进程失败"
fi

# ---------- 汇总 ----------
say "2/2  结果"
# 指标只出现在**控制台输出**里：surefire 的 .txt 报告只写异常与汇总行，
# 不含测试自己打印的表格（实测过）。所以固定解析日志。
# 类别名与 GOLDEN-GATE 判定行都是 ASCII，控制台编码不同也照样能匹配。
SRC="$LOG"

if [ -f "$ROOT/.toolchain/server-ci/target/golden-detail.tsv" ]; then
  cp -f "$ROOT/.toolchain/server-ci/target/golden-detail.tsv" "$VERIFY/golden-detail.tsv"
  ok "逐条明细：.toolchain/verify/golden-detail.tsv"
fi

printf '  %s\n' "类别         条数      R@1      R@5      MRR      干净率"
{ grep -E '^(entity|colloquial|gap|chitchat|descriptive)[[:space:]]' "$SRC" || true; } | sed 's/^/  /'

if [ "$MVN_OK" = "1" ] && grep -q 'GOLDEN-GATE: PASS' "$SRC"; then
  printf '\n\033[32m\033[1m门禁通过：检索底线守住。\033[0m\n'
  exit 0
fi

bad "门禁未通过"
printf '  日志：.toolchain/verify/golden-gate.log\n'
printf '  逐条失手看明细里的 rank 列，与期望块对照；\n'
printf '  改完必须能说清"哪一条样本因此变好了"，否则不进主干。\n'
exit 1
