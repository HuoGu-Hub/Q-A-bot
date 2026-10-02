#!/usr/bin/env bash
# ============================================================
#  服务健康自检
#
#  重启服务后跑这个，一条命令看清所有环节是否正常。
#  用法：./scripts/health-check.sh [服务地址]
#        默认 http://127.0.0.1:8080
# ============================================================
set -uo pipefail

B="${1:-http://127.0.0.1:8080}"
FAILED=0
ok()  { printf "  \033[32m✓ %s\033[0m\n" "$1"; }
bad() { printf "  \033[31m✗ %s\033[0m\n" "$1"; FAILED=1; }
code() { curl -s -m 8 -o /dev/null -w "%{http_code}" "$1" 2>/dev/null || echo "000"; }

echo "自检目标：$B"
echo

echo "=== 1. 服务可达 ==="
C=$(code "$B/robots.txt")
[ "$C" = "200" ] && ok "服务在跑" || bad "服务不可达（HTTP $C）—— 检查是否已启动"

echo
echo "=== 2. 后端接口 ==="
C=$(code "$B/api/public/stats")
[ "$C" = "200" ] && ok "公开接口正常" || bad "公开接口异常（HTTP $C）"

echo
echo "=== 3. 前端页面（最容易漏的一项）==="
C=$(code "$B/public.html")
if [ "$C" = "200" ]; then
  ok "公开站页面正常"
else
  bad "公开站页面 $C —— 多半是 web-dist 没构建，或服务启动时工作目录不对"
  echo "     处理：./scripts/build-web.sh  然后重启服务"
fi
C=$(code "$B/admin.html")
if [ "$C" = "200" ]; then
  ok "管理后台页面正常"
elif [ "$C" = "503" ]; then
  bad "管理后台未启用（503）—— .env 里没配 ADMIN_PASSWORD"
else
  bad "管理后台页面 $C"
fi

echo
echo "=== 4. 前端静态资源 ==="
A=$(curl -s -m 8 "$B/public.html" 2>/dev/null | grep -oE '/assets/[^"]+[.]js' | head -1)
if [ -n "$A" ]; then
  C=$(code "$B$A")
  [ "$C" = "200" ] && ok "静态资源可加载" || bad "静态资源 $C —— web-dist 没被正确挂载"
else
  bad "读不到页面里的资源引用 —— 页面本身可能没加载"
fi

echo
echo "=== 5. 前端路由 fallback ==="
C=$(code "$B/library")
[ "$C" = "200" ] && ok "SPA 路由正常（/library）" || bad "SPA 路由 $C —— 刷新子页面会 404"

echo
echo "=== 6. 安全边界 ==="
C=$(code "$B/admin/api/models")
if [ "$C" = "401" ]; then
  ok "管理接口需鉴权（401）"
elif [ "$C" = "503" ]; then
  bad "管理后台未启用（503）—— 配 ADMIN_PASSWORD"
else
  bad "管理接口状态异常（$C），期望 401"
fi

echo
echo "=== 7. 公开接口不含隐私字段 ==="
LEAK=$(curl -s -m 8 "$B/api/public/stats" 2>/dev/null | grep -oE '"(userId|groupId|user_id|group_id|bestCosine)"' | head -1)
[ -z "$LEAK" ] && ok "无隐私字段泄露" || bad "发现隐私字段：$LEAK"

echo
if [ "$FAILED" = "0" ]; then
  printf "\033[32m\033[1m全部通过\033[0m\n"
  exit 0
else
  printf "\033[31m\033[1m有检查未通过，见上方 ✗\033[0m\n"
  exit 1
fi
