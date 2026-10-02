#!/usr/bin/env bash
# 构建前端并同步到后端的静态资源目录
#
# 用法：./scripts/build-web.sh [--refresh]
#   --refresh  强制重装容器专用依赖（依赖清单变了之后用）
#
# ══════════════════════════════════════════════════════════════════════
# 为什么容器**不能**用 web/node_modules（这条踩过，代价很大）
# ══════════════════════════════════════════════════════════════════════
# 这个项目同时被两个环境使用：Windows 宿主机（人工开发、跑 pnpm dev）与
# Linux 容器（自动化验证与构建）。两边共用 web/node_modules 会互相破坏：
#
#   1. **store 路径冲突**：pnpm 把 store 的绝对路径写进 node_modules/.modules.yaml。
#      Windows 侧是 E:\.pnpm-store\v11，容器侧是工作区内的 .toolchain/pnpm-store。
#      任一侧发现记录与自己不符，就判定"装得不对" → 重装整个 node_modules。
#   2. **平台二进制冲突**：Windows 需要 rollup-win32-x64-msvc / esbuild.exe，
#      Linux 需要 rollup-linux-x64-gnu / esbuild/linux-x64。同名包只能留一个。
#   3. **符号链接删不掉**：pnpm 默认 isolated 布局会建符号链接。Linux 创建的链接
#      在 Windows 上普通用户 lstat 会 EACCES —— 表现就是宿主机
#      「pnpm dev 报 EACCES: permission denied, lstat node_modules\typescript，
#       必须手动删 node_modules 才能起来」。
#
# 解法：容器**完全不用** web/node_modules，改用一个工作副本：
#   .toolchain/web-ci/  —— 复制 package.json / lockfile / 配置 / 源码，在那里安装与构建
# 该目录在 .gitignore 里（整个 .toolchain/ 都被忽略）。
#
# ⚠️ 为什么不直接把 web 软链过去：pnpm 会沿真实路径往上找 workspace 根，
#    软链会把安装又落回 web/node_modules。所以这里用**复制**，
#    每次构建前把源码重新同步一遍（源码很小，复制开销可忽略）。
# ══════════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")/.."

WEB=web
DIST=server/web-dist
ROOT="$(pwd)"
CI="$ROOT/.toolchain/web-ci"
STORE="$ROOT/.toolchain/pnpm-store"

REFRESH=0
[ "${1:-}" = "--refresh" ] && REFRESH=1

# ---------- 0) 串行化：工作副本是共享的 ----------
# $CI 是**唯一一份**工作副本：一个构建刚 `rm -rf $CI/src`，另一个正在编译它，
# 报出来的是「Could not load .../src/shared/api/client: ENOENT」——
# 看着像代码坏了，其实是两个构建互相删源码（2026-09-28 踩过一次，
# 并行跑两个 build-web.sh 才复现）。
# 用文件锁把「并发」变成「排队」。没有 flock 的环境（部分自带 bash 的 Windows/Mac）
# 只提示一句就继续，不让脚本因为一个锁直接跑不起来。
if command -v flock >/dev/null 2>&1; then
  mkdir -p "$ROOT/.toolchain"
  exec 9>"$ROOT/.toolchain/.build-web.lock"
  if ! flock -w 900 9; then
    echo "==> 另一个构建跑了 15 分钟还没放锁，先看看它卡在哪"; exit 1
  fi
else
  echo "==> 提示：本机没有 flock，跳过构建串行化（别同时跑两个 build-web.sh）"
fi

# ---------- 1) 同步源码到工作副本 ----------
echo "==> 同步源码到容器工作副本 $CI"
mkdir -p "$CI"
rm -rf "$CI/src" "$CI/dist"
cp -r "$WEB/src" "$CI/src"
cp "$WEB/package.json" "$WEB/pnpm-lock.yaml" "$WEB/tsconfig.json" "$WEB/vite.config.ts" "$CI/"
for f in "$WEB"/*.html; do
  [ -e "$f" ] && cp "$f" "$CI/"
done
# 有些页面会引用同级目录（如 ../theme），保持目录层级一致
[ -d "$WEB/public" ] && cp -r "$WEB/public" "$CI/public" 2>/dev/null || true

# ---------- 2) 依赖 ----------
if [ "$REFRESH" = "1" ] || [ ! -x "$CI/node_modules/.bin/vite" ]; then
  echo "==> 安装容器专用依赖（不碰 web/node_modules）"
  rm -rf "$CI/node_modules"
  # esbuild 的构建脚本默认被 pnpm 拦截；不批准它就拿不到平台二进制。
  # 用 approve-builds 的非交互等价物：写进 package.json 的 pnpm 字段。
  node -e '
    const fs = require("fs");
    const p = process.argv[1];
    const j = JSON.parse(fs.readFileSync(p, "utf8"));
    j.pnpm = Object.assign({}, j.pnpm, { onlyBuiltDependencies: ["esbuild"] });
    fs.writeFileSync(p, JSON.stringify(j, null, 2) + "\n");
  ' "$CI/package.json"
  # ERR_PNPM_IGNORED_BUILDS 只是"构建脚本未批准"的提示，依赖其实已装好，
  # 所以这里允许非零退出，随后用可执行文件是否存在来判定成功。
  (cd "$CI" && pnpm install --store-dir "$STORE" --config.confirmModulesPurge=false) || true
  [ -x "$CI/node_modules/.bin/vite" ] || { echo "依赖安装失败"; exit 1; }
else
  echo "==> 依赖已就绪（跳过安装；改动依赖请加 --refresh）"
fi

# ---------- 3) 类型检查 + 构建 ----------
echo "==> 类型检查"
(cd "$CI" && ./node_modules/.bin/vue-tsc --noEmit)

echo "==> 构建"
(cd "$CI" && ./node_modules/.bin/vite build)

# ---------- 4) 同步产物 ----------
echo "==> 同步到 $DIST"
rm -rf "$DIST"
mkdir -p "$DIST"
cp -r "$CI/dist/." "$DIST/"

echo "==> 完成"
find "$DIST" -maxdepth 1 -type f | sed 's/^/    /'
