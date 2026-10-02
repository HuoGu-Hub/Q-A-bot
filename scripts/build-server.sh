#!/usr/bin/env bash
# ============================================================
#  后端构建 —— 容器侧【隔离】构建，绝不写 server/target
#
#  为什么必须隔离（2026-10-01 事故复盘）：
#    宿主机（Windows / IntelliJ）跑的是「直接跑编译输出」模式，
#    它的 classpath 就是仓库里的 server/target/classes。
#    而容器里只要对着 server/pom.xml 跑一次 mvn（clean / test-compile /
#    package），就会【删掉并重写】这个目录下的 .class 文件。
#    正在运行的 JVM 是懒加载的：启动时用不到的类（尤其是嵌套类，
#    如 BudgetGuard$Verdict、SiteTextService$BlockView）要等第一次调用
#    才去磁盘上找 —— 这时文件已经被 clean 删掉、或还没被 javac 写出来，
#    于是就有了：
#      java.lang.NoClassDefFoundError: com/example/qqbot/guard/BudgetGuard$Verdict
#      Caused by: java.lang.ClassNotFoundException: ...$Verdict
#    代码本身没问题，是运行中的 classpath 被人从脚底下抽走了。
#
#  所以：把 pom.xml + src 复制到 .toolchain/server-ci/ 再构建，
#  产物落在 .toolchain/server-ci/target/，与宿主机正在运行的目录互不干扰。
#  （与 scripts/build-web.sh 用 .toolchain/web-ci/ 是同一套做法。）
#
#  用法：
#    ./scripts/build-server.sh                       # clean test（默认，跑测试）
#    ./scripts/build-server.sh package -DskipTests
#    ./scripts/build-server.sh compile
#    MAVEN_OFFLINE=0 ./scripts/build-server.sh ...   # 需要联网拉新依赖时
#
#  产物：.toolchain/server-ci/target/
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CI="$ROOT/.toolchain/server-ci"

export JAVA_HOME="${JAVA_HOME:-$ROOT/.toolchain/jdk-17.0.2}"
export PATH="$JAVA_HOME/bin:$ROOT/.toolchain/apache-maven-3.9.9/bin:$PATH"

MVN_ARGS=(
  -s "$ROOT/.toolchain/settings.xml"
  -Dmaven.repo.local="$ROOT/.toolchain/m2"
  -Dfile.encoding=UTF-8
  -f "$CI/pom.xml"
)
# 默认离线（本机仓库已够用；避免容器里没网时卡在下载重试上）
[ "${MAVEN_OFFLINE:-1}" = "1" ] && MVN_ARGS+=(-o)

# ---------- 同步源码到隔离副本 ----------
mkdir -p "$CI"
rm -rf "$CI/src"
cp -f "$ROOT/server/pom.xml" "$CI/pom.xml"
cp -a "$ROOT/server/src" "$CI/src"
# pom 的 resources 里有 ${project.basedir}/../AGENTS.md —— 从 server-ci 看就是 .toolchain/AGENTS.md
cp -f "$ROOT/AGENTS.md" "$ROOT/.toolchain/AGENTS.md"

# ---------- 构建 ----------
GOALS=("$@")
[ ${#GOALS[@]} -eq 0 ] && GOALS=(clean test)

echo "[build-server] 隔离副本：$CI"
echo "[build-server] mvn ${GOALS[*]}"
exec mvn "${MVN_ARGS[@]}" "${GOALS[@]}"
