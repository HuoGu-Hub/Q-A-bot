#!/usr/bin/env bash
# ============================================================
#  校验「知识库文档」格式
#
#  文档由系统外部产出（另一个 AI / 手工 / 脚本），这个脚本回答的是
#  「我这份材料哪里不合规」——不启动服务、不碰数据库、不联网。
#
#  用法：./scripts/validate-kb-doc.sh 文档1.md 文档2.md ...
#  退出码：0 = 全部可导入；1 = 有致命问题
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

export JAVA_HOME="${JAVA_HOME:-$ROOT/.toolchain/jdk-17.0.2}"
CLASSES="$ROOT/server/target/classes"

# 中文文件名/中文正文需要 UTF-8 locale：否则 JVM 的 sun.jnu.encoding 会退化成
# ASCII，Path.of("中文.md") 直接抛 InvalidPathException。只在当前不是 UTF-8 且
# 系统确有可用的 UTF-8 locale 时才切换，避免在已有 UTF-8 环境的机器上帮倒忙。
if ! locale charmap 2>/dev/null | grep -qi 'utf-\?8'; then
  for cand in C.UTF-8 en_US.UTF-8 zh_CN.UTF-8; do
    # locale -a 里的写法五花八门（C.utf8 / en_US.utf8），统一去掉大小写和连字符再比
    norm="$(printf '%s' "$cand" | tr 'A-Z' 'a-z' | tr -d '-')"
    if locale -a 2>/dev/null | tr 'A-Z' 'a-z' | tr -d '-' | grep -qx "$norm"; then
      export LANG="$cand" LC_ALL="$cand"
      break
    fi
  done
fi

if [ ! -d "$CLASSES" ]; then
  echo "还没编译后端。先跑："
  echo "  export JAVA_HOME=$ROOT/.toolchain/jdk-17.0.2"
  echo "  export PATH=\$JAVA_HOME/bin:$ROOT/.toolchain/apache-maven-3.9.9/bin:\$PATH"
  echo "  mvn -o -s $ROOT/.toolchain/settings.xml -Dmaven.repo.local=$ROOT/.toolchain/m2 -f server/pom.xml compile"
  exit 2
fi

exec "$JAVA_HOME/bin/java" -cp "$CLASSES" com.example.qqbot.kb.doc.ChunkMarkupCli "$@"
