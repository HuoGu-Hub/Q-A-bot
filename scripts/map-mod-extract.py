#!/usr/bin/env python3
"""从 The Worst Map Mod 的源码里取出点位，写成 JSON/CSV。

mod 自己把游戏世界文件编译进了 DLL，压缩包里没有点位表。可读的是作者仓库里
与 mod.json 版本对应的生成头文件：

  src/ore_deposits.h   矿脉/材料/植物/诱饵/树木/传送点/水源/笔记/首领/独特装备
  src/chest_spawns.h   金/银/木箱与石棺
  src/regions.h        八块区域的网格

用法（源码目录可以换成任意一次 git clone / 官方 zip 解压目录）：

  python scripts/map-mod-extract.py --zip "server/data/...1.3.26.zip" \
      --src ".tmp-pw/worst-map-1.3.26/worst-map-mod-enshrouded-1.3.26" \
      --out "server/data/map-mod"

只给 --zip 时按 mod.json 的版本去 GitHub 拉同版本的源码，要求能联网。
版本对不上会直接退出：点位表和游戏版本是一一对应的，拿错版本坐标就会错。
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

REPO = "Greeneye0/worst-map-mod-enshrouded"
CATEGORY_KEYS = {
    0: "quests", 1: "fast-travel", 2: "shroud", 3: "world",
    5: "mining", 6: "materials", 7: "plants", 8: "bait", 9: "trees",
    10: "creature",
}

# 顶层过滤类型。点位表里每一行落进哪一类，决定前端资源面板的分区。
ROW_KINDS = {
    **{i: "ore" for i in range(0, 21)},
    **{i: "material" for i in range(21, 33)},
    **{i: "plant" for i in range(33, 63)},
    **{i: "bait" for i in range(63, 69)},
    **{i: "tree" for i in range(69, 87)},
    87: "beacon",
    88: "water",
    89: "water",
    90: "lore",
    91: "boss",
    92: "unique_weapon",
    93: "unique_equipment",
    94: "collectible",
    95: "summon",
    **{i: "plant" for i in range(96, 103)},
    # 1.3.26 起 kOreDeposits 里增加了生物刷新点；行号 103-238 是生物
    **{i: "creature" for i in range(103, 239)},
}

# 宝箱的 9 个变体。作者在 map_key_panel.cpp 里用同一组枚举。
CHEST_TYPES = [
    {"index": 0, "key": "gold", "name": "Gold Chest", "nameZh": "金箱"},
    {"index": 1, "key": "gold_sarcophagus", "name": "Gold Sarcophagus", "nameZh": "金棺"},
    {"index": 2, "key": "gold_locked", "name": "Gold Chest (Locked)", "nameZh": "上锁的金箱"},
    {"index": 3, "key": "silver", "name": "Silver Chest", "nameZh": "银箱"},
    {"index": 4, "key": "silver_sarcophagus", "name": "Silver Sarcophagus", "nameZh": "银棺"},
    {"index": 5, "key": "silver_locked", "name": "Silver Chest (Locked)", "nameZh": "上锁的银箱"},
    {"index": 6, "key": "wood", "name": "Wooden Chest", "nameZh": "木箱"},
    {"index": 7, "key": "wood_sarcophagus", "name": "Wooden Sarcophagus", "nameZh": "木棺"},
    {"index": 8, "key": "wood_locked", "name": "Wooden Chest (Locked)", "nameZh": "上锁的木箱"},
]

# Windows 控制台默认不一定是 UTF-8；点位名里有中文，输出别变成乱码
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")
    except Exception:
        pass


def mod_version_from_zip(path: Path) -> str:
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if name.endswith("/mod.json") or name == "mod.json":
                return json.loads(z.read(name).decode("utf-8"))["version"]
    raise SystemExit(f"{path} 里没有 mod.json")


def fetch_source(version: str, workdir: Path) -> Path:
    """按版本拉 GitHub 源码包；只在没给 --src 时走这条路。"""
    url = f"https://codeload.github.com/{REPO}/zip/refs/tags/v{version}"
    archive = workdir / f"worst-map-{version}.zip"
    print(f"[fetch] {url}")
    urllib.request.urlretrieve(url, archive)
    with zipfile.ZipFile(archive) as z:
        z.extractall(workdir)
    return workdir / f"worst-map-mod-enshrouded-{version}"


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def c_array(text: str, name: str) -> str:
    """截出 name[...] = { ... }; 的花括号内容。

    不能用 name 的第一次出现：regions.h 的注释里就写着 kRegionRowStart[z]，
    从那里往后找 "{" 会落到 struct RegionRun 上。必须匹配完整的声明形式。
    """
    match = re.search(re.escape(name) + r"\s*(?:\[[^\]]*\])+\s*=\s*\{", text)
    if match is None:
        match = re.search(re.escape(name) + r"\s*=\s*\{", text)
    if match is None:
        raise SystemExit(f"找不到 {name} 的数组声明")
    start = match.end() - 1
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
    raise SystemExit(f"{name} 的数组没有闭合")


def c_strings(chunk: str) -> list[str]:
    return re.findall(r'"((?:[^"\\]|\\.)*)"', chunk)


def c_ints(chunk: str) -> list[int]:
    return [int(v, 0) for v in re.findall(r"-?(?:0[xX][0-9a-fA-F]+|\d+)", chunk)]


def parse_chinese_names(src: Path, expected: int) -> list[str]:
    """lang.cpp 的 kResourceNames 第 13 列是简体中文，和 kOreLabels 同序。"""
    text = read_text(src / "src" / "lang.cpp")
    rows = re.findall(r"\{([^{}]*)\}", c_array(text, "kResourceNames"))
    names = []
    for row in rows:
        values = c_strings(row)
        names.append(values[12] if len(values) >= 13 else "")
    if len(names) > expected:
        raise SystemExit(f"lang.cpp 有 {len(names)} 行资源名，ore_deposits.h 只有 {expected} 行")
    # 1.3.26 的生物行没有本地化名；用英文名兜底
    return names + [""] * (expected - len(names))


def parse_ore_deposits(src: Path) -> dict:
    text = read_text(src / "src" / "ore_deposits.h")
    labels = c_strings(c_array(text, "kOreLabels"))
    chinese = parse_chinese_names(src, len(labels))
    categories = c_ints(c_array(text, "kOreCategory"))
    # 林地形状色：0xAABBGGRR，只有树木行非零
    area_colors = c_ints(c_array(text, "kOreAreaColors"))
    deposits = [
        [int(ore), int(x), int(z), int(top), int(amount)]
        for ore, x, z, top, amount in re.findall(
            r"\{\s*(\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(\d+)\s*\}",
            c_array(text, "kOreDeposits"),
        )
    ]
    declared = int(re.search(r"kOreDepositCount\s*=\s*(\d+)", text).group(1))
    if declared != len(deposits):
        raise SystemExit(f"ore_deposits.h 声明 {declared} 条，实际解析 {len(deposits)} 条")

    counts: dict[int, int] = {}
    for ore, *_ in deposits:
        counts[ore] = counts.get(ore, 0) + 1
    rows = []
    for i, label in enumerate(labels):
        category = categories[i] if i < len(categories) else 0
        area = area_colors[i] if i < len(area_colors) else 0
        area_color = "" if area == 0 else "#%02x%02x%02x" % (
            area & 0xFF, (area >> 8) & 0xFF, (area >> 16) & 0xFF)
        kind = ROW_KINDS.get(i, "world")
        rows.append({
            "index": i,
            "key": re.sub(r"[^a-z0-9]+", "_", label.lower()).strip("_"),
            "name": label,
            # 生物行的中文名表和 kOreLabels 顺序对不上，统一用英文名，避免张冠李戴
            "nameZh": "" if kind == "creature" else (chinese[i] or label),
            "kind": kind,
            "category": CATEGORY_KEYS.get(category, "world"),
            "count": counts.get(i, 0),
            "areaColor": area_color,
        })
    return {"rows": rows, "points": deposits}


def parse_chests(src: Path) -> list[list]:
    text = read_text(src / "src" / "chest_spawns.h")
    body = c_array(text, "kChestSpawns")
    chests = []
    for m in re.finditer(
        r"\{\s*(\d+)u?\s*,\s*(\d+)u?\s*,\s*(\d+)u?\s*,\s*(\d+)u?\s*,"
        r"\s*(\d+)u?\s*,\s*(\d+)u?\s*,\s*(\d+)u?\s*\}",
        body,
    ):
        px0, px1, py0, py1, pz0, pz1, sub = (int(v) for v in m.groups())
        # 作者在 chest_glow.inl 里的还原方式：整数部分 + 小数部分 / 65536
        x = px1 + px0 / 65536.0
        y = py1 + py0 / 65536.0
        z = pz1 + pz0 / 65536.0
        chests.append([round(x, 2), round(y, 2), round(z, 2), sub])
    total = re.search(r"kTotalChestCount\s*=\s*(\d+)", text)
    if total:
        declared = int(total.group(1))
    else:
        # 1.3.18 起是 kWoodChestFirst + kWoodChestCount，没有字面量
        first = int(re.search(r"kWoodChestFirst\s*=\s*(\d+)", text).group(1))
        wood = int(re.search(r"kWoodChestCount\s*=\s*(\d+)", text).group(1))
        declared = first + wood
    if declared != len(chests):
        raise SystemExit(f"chest_spawns.h 声明 {declared} 个箱子，实际解析 {len(chests)} 个")
    return chests


def parse_trees(src: Path) -> list[list]:
    text = read_text(src / "src" / "ore_deposits.h")
    body = c_array(text, "kTreeShapeRects")
    rects = [
        [int(ore), int(z0), int(z1), int(x0), int(x1)]
        for ore, z0, z1, x0, x1 in re.findall(
            r"\{\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\}", body
        )
    ]
    return rects


def parse_regions(src: Path) -> dict:
    text = read_text(src / "src" / "regions.h")
    row_start = c_ints(c_array(text, "kRegionRowStart"))
    runs = [
        [int(cells), int(region)]
        for cells, region in re.findall(r"\{\s*(\d+)\s*,\s*(\d+)\s*\}", c_array(text, "kRegionRuns"))
    ]
    names = c_strings(c_array(text, "kRegionNameEnglish"))
    return {"cellMeters": 8, "gridSize": len(row_start) - 1, "names": names,
            "rowStart": row_start, "runs": runs}


def main() -> int:
    parser = argparse.ArgumentParser(description="把 The Worst Map Mod 的点位导出成 JSON/CSV")
    parser.add_argument("--zip", help="mod 压缩包，用来读版本与校验")
    parser.add_argument("--src", help="与压缩包版本对应的 mod 源码目录")
    parser.add_argument("--out", default="server/data/map-mod", help="输出目录")
    args = parser.parse_args()

    if not args.zip and not args.src:
        parser.error("--zip 与 --src 至少要给一个")

    version = mod_version_from_zip(Path(args.zip)) if args.zip else "unknown"
    if args.src:
        src = Path(args.src)
    else:
        workdir = Path(tempfile.mkdtemp(prefix="map-mod-"))
        src = fetch_source(version, workdir)
    if not (src / "src" / "ore_deposits.h").exists():
        raise SystemExit(f"{src} 不是 mod 源码目录：找不到 src/ore_deposits.h")
    mod_json = src / "mod.json"
    if version != "unknown" and mod_json.exists():
        src_version = json.loads(mod_json.read_text(encoding="utf-8")).get("version")
        if src_version != version:
            raise SystemExit(f"源码版本 {src_version} 与压缩包 {version} 不一致：点位表会错位")

    ore = parse_ore_deposits(src)
    chests = parse_chests(src)
    trees = parse_trees(src)
    regions = parse_regions(src)
    total_points = len(ore["points"])
    total_chests = len(chests)
    expected = {"1.3.18": (33084, 3042), "1.3.26": (61423, 3042)}
    if version in expected:
        want_points, want_chests = expected[version]
        if total_points != want_points:
            raise SystemExit(f"{version} 应有 {want_points} 个点位，实际 {total_points}")
        if total_chests != want_chests:
            raise SystemExit(f"{version} 应有 {want_chests} 个箱子，实际 {total_chests}")

    chest_counts = {t["index"]: 0 for t in CHEST_TYPES}
    for chest in chests:
        chest_counts[chest[3]] = chest_counts.get(chest[3], 0) + 1
    chest_types = [{**t, "count": chest_counts.get(t["index"], 0)} for t in CHEST_TYPES]

    meta = {
        "source": {
            "mod": "The Worst Map Mod",
            "version": version,
            "repo": f"https://github.com/{REPO}",
            "license": "MIT",
        },
        "map": "Embervale",
        "crs": {"order": "xy", "topLeft": [0, 10240], "bottomRight": [10240, 0]},
        "counts": {
            "points": total_points,
            "chests": total_chests,
            "treeRects": len(trees),
            "all": total_points + total_chests,
        },
        "regions": regions,
        "chestTypes": chest_types,
        "rows": ore["rows"],
        "points": ore["points"],
        "chests": chests,
        "trees": trees,
    }

    out = Path(args.out) / version
    out.mkdir(parents=True, exist_ok=True)
    (out / "points.json").write_text(json.dumps(meta, ensure_ascii=False, separators=(",", ":")),
                                     encoding="utf-8")

    with (out / "points.csv").open("w", newline="", encoding="utf-8-sig") as fh:
        w = csv.writer(fh)
        w.writerow(["kind", "row_key", "row_name", "row_name_zh", "x", "z", "top_y", "amount"])
        by_index = {r["index"]: r for r in ore["rows"]}
        for ore_i, x, z, top, amount in ore["points"]:
            row = by_index[ore_i]
            w.writerow([row["kind"], row["key"], row["name"], row["nameZh"], x, z, top, amount])

    print(f"[ok] {src}")
    print(f"     {out / 'points.json'}")
    print(f"     {out / 'points.csv'}")
    print(f"     点位 {total_points} / 箱子 {total_chests} / 林地矩形 {len(trees)}")
    for r in ore["rows"]:
        if r["count"]:
            print(f"       {r['category']:11s} {r['name']:32s} {r['count']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
