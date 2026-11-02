#!/usr/bin/env python3
"""比较两份 wiki 配图目录，把新增/变化的文件打成 tar.gz，供线上解包。

用法：
  python scripts/wiki-image-delta.py --baseline <线上旧目录> --current <本地导入后目录> --out <delta.tar.gz>

线上解包：
  tar xzf wiki-image-delta.tar.gz -C /opt/qqbot/server/data/wiki-images
"""

import argparse
import hashlib
import json
import os
import tarfile


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", required=True)
    ap.add_argument("--current", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--manifest", help="可选：把清单另存为 JSON")
    args = ap.parse_args()

    baseline = os.path.abspath(args.baseline)
    current = os.path.abspath(args.current)
    if not os.path.isdir(current):
        raise SystemExit("current dir missing: " + current)

    base_hash = {}
    if os.path.isdir(baseline):
        for root, _, files in os.walk(baseline):
            for name in files:
                p = os.path.join(root, name)
                rel = os.path.relpath(p, baseline).replace(os.sep, "/")
                base_hash[rel] = sha256(p)

    changed = []
    for root, _, files in os.walk(current):
        for name in files:
            p = os.path.join(root, name)
            rel = os.path.relpath(p, current).replace(os.sep, "/")
            h = sha256(p)
            if base_hash.get(rel) != h:
                changed.append((rel, p, h, os.path.getsize(p)))

    changed.sort()
    total = sum(x[3] for x in changed)
    print("baseline files:", len(base_hash))
    print("current files:", sum(len(f) for _, _, f in os.walk(current)))
    print("changed/new files:", len(changed), "bytes:", total)
    for rel, _, _, size in changed[:20]:
        print("  ", rel, size)

    if os.path.exists(args.out):
        os.remove(args.out)
    with tarfile.open(args.out, "w:gz") as tar:
        for rel, p, _, _ in changed:
            tar.add(p, arcname=rel)

    manifest = [{"path": rel, "sha256": h, "bytes": size} for rel, _, h, size in changed]
    if args.manifest:
        with open(args.manifest, "w", encoding="utf-8") as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)
    print("delta:", args.out, os.path.getsize(args.out))


if __name__ == "__main__":
    main()
