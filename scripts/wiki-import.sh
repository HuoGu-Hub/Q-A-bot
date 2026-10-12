#!/usr/bin/env bash
# ============================================================
#  wiki 攻略批量拉取 + 清洗 —— 一条命令
#
#  链路：枚举页 → 拉 wikitext → WikitextCleaner 清洗（默认保留、只丢噪声）
#        → 数据层页面再取一份渲染 HTML（action=parse + jsoup）
#        → KbTextChunker 切块 → 写块 + 向量
#
#  用法：
#    ./scripts/wiki-import.sh                # 增量：只处理 revid 变过的页
#    ./scripts/wiki-import.sh --dry-run      # 只看数量，不写库（不花 embedding 的钱）
#    ./scripts/wiki-import.sh --force        # 忽略 revid，全部重洗（清洗规则变了用这条）
#    ./scripts/wiki-import.sh --no-render    # 关掉渲染兜底（快，但拿不到 {{Get}} 与数据表）
#    ./scripts/wiki-import.sh --purge        # 清掉全部 wiki 文章块 + 导入状态（可重来）
#
#  导入只重载进程内索引，跑完要重启服务。
#  退出码：0 成功；1 失败；2 前置条件不满足
#  日志：.toolchain/verify/wiki-import.log
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

DRY=0; FORCE=0; PURGE=0; RENDER=1
for arg in "$@"; do
  case "$arg" in
    --dry-run)   DRY=1 ;;
    --force)     FORCE=1 ;;
    --purge)     PURGE=1 ;;
    --no-render) RENDER=0 ;;
    *) echo "未知参数：$arg（支持 --dry-run / --force / --no-render / --purge）" >&2; exit 2 ;;
  esac
done
if [ "$DRY" = "1" ] && [ "$PURGE" = "1" ]; then
  echo "--dry-run 与 --purge 不能同时用" >&2; exit 2
fi

VERIFY="$ROOT/.toolchain/verify"
LOG="$VERIFY/wiki-import.log"
mkdir -p "$VERIFY"
say() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
ok()  { printf '  \033[32m✅ %s\033[0m\n' "$1"; }
bad() { printf '  \033[31m❌ %s\033[0m\n' "$1"; }

# ---------- 0/2 前置条件 ----------
say "0/2  前置条件"
MISSING=0
if [ -f "$ROOT/server/data/qa/qqbot.sqlite" ]; then ok "生产语料存在"; else bad "缺 server/data/qa/qqbot.sqlite"; MISSING=1; fi
if [ "$DRY" = "1" ]; then
  ok "dry-run：不写库、不调 embedding，所以不查 API key"
elif [ -f "$ROOT/.env" ] && grep -qE '^SILICONFLOW_API_KEY=.+' "$ROOT/.env"; then
  ok "SILICONFLOW_API_KEY 已设置（这次会真的写库并算向量）"
else
  bad ".env 里的 SILICONFLOW_API_KEY 为空 —— 写块要算向量，起不来"; MISSING=1
fi
if [ "$MISSING" = "1" ]; then bad "前置条件不满足"; exit 2; fi

# ---------- 1/2 跑导入 ----------
ARGS="--app.kb.wiki-import.import-on-start=true"
if [ "$PURGE" = "1" ]; then
  ARGS="$ARGS --app.kb.wiki-import.purge=true"
elif [ "$DRY" = "1" ]; then
  ARGS="$ARGS --app.kb.wiki-import.dry-run=true"
elif [ "$FORCE" = "1" ]; then
  ARGS="$ARGS --app.kb.wiki-import.force=true"
fi
[ "$RENDER" = "0" ] && ARGS="$ARGS --app.kb.wiki-import.render.enabled=false"
ARGS="$ARGS --spring.main.web-application-type=none"

say "1/2  跑导入"
echo "  参数：$ARGS"
echo "  隔离构建：.toolchain/server-ci，工作目录：server/（SQLite 相对路径以它为准）"
MVN_OK=0
# ⚠️ 必须显式把 fork 出来的 JVM 的 stdout/stderr 指成 UTF-8：
#    导入报告走 System.out（默认跟随控制台代码页，Windows 上是 GBK），
#    而日志行走 SLF4J（UTF-8）—— 不统一，同一个日志里会混两种编码，
#    下面的 grep 就会漏掉报告行与告警。
JVM_ARGS="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
# clean 必须带：Copy-Item/cp 会保留时间戳，增量资源复制可能跳过 application.yml
if ./scripts/build-server.sh -q clean spring-boot:run \
      -Dspring-boot.run.workingDirectory="$ROOT/server" \
      "-Dspring-boot.run.jvmArguments=$JVM_ARGS" \
      "-Dspring-boot.run.arguments=$ARGS" 2>&1 | tee "$LOG"; then
  MVN_OK=1
  ok "导入进程正常退出"
else
  bad "导入进程失败，看 $LOG"
fi

# ---------- 2/2 结果 ----------
say "2/2  结果"
if grep -q "导入完成" "$LOG"; then
  # 只认这一行：它是 SLF4J 输出的 UTF-8，含全部关键数字。
  # 导入报告块走 System.out，Windows 上跟控制台代码页（GBK），grep 抓不稳。
  grep "导入完成" "$LOG" | tail -1 | sed 's/^.*\[KB-WIKI\]/  [KB-WIKI]/'
  if [ "$PURGE" = "1" ]; then
    echo
    echo "  回滚完成。词条表里会留下这些块的孤儿词条 —— 去管理端「词条」面板点一次「清理孤儿词条」。"
  fi
  if grep -q "顶到上限" "$LOG"; then
    bad "有来源顶到 max-pages-per-source（后面还有页没列）—— 调大上限，或拆成多个来源"
  fi
  if grep -q "撞车" "$LOG"; then
    bad "有块 id 撞车（缺陷清单 §10.2，已知并接受）—— 那几页正文进不了语料"
  fi
  if grep -q "渲染兜底失败" "$LOG"; then
    bad "有页面渲染兜底失败（已退回 wikitext 正文，不影响导入）"
  fi
  echo
  echo "  下一步：① 重启服务（索引只在进程内重载）② 跑 ./scripts/golden-gate.sh 看检索有没有退化"
else
  bad "日志里没有导入报告 —— 看 $LOG"
fi

if [ "$MVN_OK" = "1" ]; then exit 0; else exit 1; fi
