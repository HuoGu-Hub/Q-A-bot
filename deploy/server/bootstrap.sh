#!/usr/bin/env bash
# ============================================================
#  服务器一次性初始化 —— Debian 12 空机器 → 能跑 jar 的状态
#
#  用法，root 执行：
#    scp deploy/server/bootstrap.sh root@服务器:/tmp/
#    ssh root@服务器 'bash /tmp/bootstrap.sh'
#
#  可重复执行：已完成的步骤会跳过
#  可选环境变量：
#    APP_USER  qqbot        运行用户
#    APP_DIR   /opt/qqbot   部署目录
#    SWAP_SIZE 4G           swap 大小
# ============================================================
set -euo pipefail

[ "$(id -u)" = "0" ] || { echo "请用 root 执行"; exit 1; }

APP_USER="${APP_USER:-qqbot}"
APP_DIR="${APP_DIR:-/opt/qqbot}"
SWAP_SIZE="${SWAP_SIZE:-4G}"

export DEBIAN_FRONTEND=noninteractive
export NEEDRESTART_MODE=a

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }

# ---------- 1. 系统与时区 ----------
step "系统包、时区与时间同步"
apt-get update -qq
apt-get -y -qq upgrade
apt-get -y -qq install curl ca-certificates gnupg rsync sqlite3 chrony fontconfig \
                        fonts-wqy-zenhei openjdk-17-jre-headless nginx

timedatectl set-timezone Asia/Shanghai
systemctl enable --now chrony
# 时间漂移会直接导致 QQ 登录失败，这条不能省
chronyc makestep >/dev/null 2>&1 || true

# ---------- 2. swap ----------
step "swap：$SWAP_SIZE"
if ! swapon --show | grep -q '/swapfile'; then
  fallocate -l "$SWAP_SIZE" /swapfile
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
else
  echo "已存在 /swapfile，跳过创建"
fi
grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab

cat > /etc/sysctl.d/99-qqbot.conf <<'EOF'
vm.swappiness=10
vm.vfs_cache_pressure=50
EOF
sysctl --system >/dev/null

# ---------- 3. 运行用户与目录 ----------
step "运行用户 $APP_USER 与目录 $APP_DIR"
id "$APP_USER" >/dev/null 2>&1 || useradd -m -s /bin/bash "$APP_USER"
mkdir -p "$APP_DIR/server/data" "$APP_DIR/server/config" "$APP_DIR/backup"
chown -R "$APP_USER:$APP_USER" "$APP_DIR"

# ---------- 4. journald 上限 ----------
step "journald 上限 200M"
sed -i 's/^#\?SystemMaxUse=.*/SystemMaxUse=200M/' /etc/systemd/journald.conf
grep -q '^SystemMaxUse=' /etc/systemd/journald.conf || echo 'SystemMaxUse=200M' >> /etc/systemd/journald.conf
systemctl restart systemd-journald

# ---------- 5. Docker 与 compose 插件 ----------
step "Docker"
if command -v docker >/dev/null 2>&1; then
  echo "Docker 已安装，跳过"
else
  install -m 0755 -d /etc/apt/keyrings
  # 优先腾讯云镜像源，拉不动再回退官方源
  if curl -fsSL --max-time 20 https://mirrors.cloud.tencent.com/docker-ce/linux/debian/gpg \
       | gpg --dearmor -o /etc/apt/keyrings/docker.gpg 2>/dev/null; then
    REPO="https://mirrors.cloud.tencent.com/docker-ce/linux/debian"
    echo "使用腾讯云 Docker 镜像源"
  else
    curl -fsSL https://download.docker.com/linux/debian/gpg \
      | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    REPO="https://download.docker.com/linux/debian"
    echo "使用 Docker 官方源"
  fi
  echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.gpg] $REPO bookworm stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -qq
  apt-get -y -qq install docker-ce docker-ce-cli containerd.io docker-compose-plugin
fi

# 容器日志不设上限，几个月就能写满系统盘
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": ["https://mirror.ccs.tencentyun.com"],
  "log-driver": "json-file",
  "log-opts": { "max-size": "20m", "max-file": "3" }
}
EOF
systemctl enable --now docker
systemctl restart docker

# ---------- 6. 收尾 ----------
step "完成"
echo "用户      : $APP_USER"
echo "目录      : $APP_DIR"
echo "Java      : $(java -version 2>&1 | head -1)"
echo "中文字体  : $(fc-list :lang=zh | wc -l) 个"
echo
echo "下一步："
echo "  1) 本地执行 scripts/release.ps1 上传产物"
echo "  2) 在 $APP_DIR 放 .env 与 system.md"
echo "  3) 安装 systemd 单元并启动"
