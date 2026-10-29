#!/bin/bash
# 手动备份。与自动备份完全隔离：只读 /opt/qqbot 下的数据，只写 /opt/qqbot/backup/manual。
# 保留策略：超过 30 天的备份删除，但最新两份无论多旧都保留。
set -euo pipefail

SRC=/opt/qqbot/server/data
DEST=/opt/qqbot/backup/manual
KEEP_DAYS=30
KEEP_MIN=2

STAMP=$(date +%F_%H%M%S)
TMP="$DEST/.tmp-$STAMP"
FINAL="$DEST/$STAMP"

mkdir -p "$DEST"

# 同一时间只允许一个实例运行，避免两份备份互相踩
exec 9>"$DEST/.lock"
flock -n 9 || { echo "已有备份正在运行"; exit 1; }

# 中途失败时清掉半成品，避免半份备份被当成一份
trap 'if [ -d "$TMP" ]; then rm -rf -- "$TMP"; fi' EXIT

# 上一次备份，用来给本次的媒体文件做硬链接去重
PREV=$(find "$DEST" -maxdepth 1 -type d -name '20*-*_*' -printf '%f\n' | sort -r | head -n1)
PREV=${PREV:+"$DEST/$PREV"}

mkdir -p "$TMP/media"

# 1) 数据库：在线一致性快照，不锁库、不中断服务
sqlite3 "$SRC/qa/qqbot.sqlite" ".backup '$TMP/qqbot.sqlite'"

# 2) 人工产物：--link-dest 让未改动的文件与上一份共享，实际只占新增空间
# link-dest 的匹配基准是"目标目录上传递的那一层"，所以逐个目录单独给
for d in site-images voices kb-images config; do
  [ -d "$SRC/$d" ] || continue
  L=()
  if [ -n "$PREV" ] && [ -d "$PREV/media/$d" ]; then
    L=(--link-dest="$PREV/media/$d")
  fi
  rsync -a --delete "${L[@]}" "$SRC/$d/" "$TMP/media/$d/"
done

# 3) 备份完整性自检，不通过就不落地
[ -s "$TMP/qqbot.sqlite" ] || { echo "数据库备份为空，终止"; exit 1; }
[ "$(sqlite3 "$TMP/qqbot.sqlite" 'pragma quick_check;')" = "ok" ] || { echo "数据库自检失败，终止"; exit 1; }

# 4) 全部完成才重命名成正式备份，中途失败不会留下半份
mv -T -- "$TMP" "$FINAL"

# 5) 过期清理：按时间倒序，跳过最新两份，其余超过 30 天的删除
mapfile -t ALL < <(find "$DEST" -maxdepth 1 -type d -name '20*-*_*' -printf '%f\n' | sort -r)
COUNT=${#ALL[@]}
for ((i=KEEP_MIN; i<COUNT; i++)); do
  d="$DEST/${ALL[$i]}"
  if [ -n "$(find "$d" -maxdepth 0 -mtime +$KEEP_DAYS)" ]; then
    rm -rf -- "$d"
    echo "删除过期：$(basename "$d")"
  fi
done

echo "备份完成：$(date '+%F %T')"
echo "  本份  $FINAL  $(du -sh "$FINAL" | cut -f1)"
echo "  现有  $(find "$DEST" -maxdepth 1 -type d -name '20*-*_*' | wc -l) 份，最早 $(find "$DEST" -maxdepth 1 -type d -name '20*-*_*' -printf '%f\n' | sort | head -n1)"