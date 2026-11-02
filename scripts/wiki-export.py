#!/usr/bin/env python3
"""从本地导入后的 SQLite 导出 wiki 语料快照（只含 wiki 表），供线上合并使用。

典型流程：
  1. 本地用 WikiImportRunner 导入到 qqbot-import.sqlite
  2. python scripts/wiki-export.py --work <工作目录> [--missing <缺失页清单>]
  3. scripts/apply-wiki-snapshot.ps1 -Snapshot <工作目录>/wiki-snapshot.sqlite.gz ...

缺失页清单是每行一个页面标题（wiki 上已删除/改名）；不传就不删除任何页面。
"""

import argparse
import gzip
import os
import shutil
import sqlite3


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True, help="包含 qqbot-import.sqlite 的工作目录")
    ap.add_argument("--db", default="qqbot-import.sqlite", help="导入后的库文件名")
    ap.add_argument("--missing", help="缺失页清单（每行一个标题）")
    ap.add_argument("--out", help="输出 .gz 路径，默认 <work>/wiki-snapshot.sqlite.gz")
    args = ap.parse_args()

    work = os.path.abspath(args.work)
    src = os.path.join(work, args.db)
    dst = os.path.join(work, "wiki-snapshot.sqlite")
    out = os.path.abspath(args.out) if args.out else os.path.join(work, "wiki-snapshot.sqlite.gz")
    if not os.path.exists(src):
        raise SystemExit("missing import db: " + src)

    con = sqlite3.connect(src)
    cur = con.cursor()
    print("import db:", cur.execute("SELECT COUNT(*) FROM kb_wiki_page").fetchone()[0], "pages")
    print("wiki blocks:", cur.execute(
        "SELECT COUNT(*) FROM kb_block WHERE doc_id LIKE 'wiki·%'").fetchone()[0])

    if args.missing:
        removed_pages = removed_blocks = 0
        with open(args.missing, encoding="utf-8") as f:
            for line in f:
                page = line.strip()
                if not page:
                    continue
                row = cur.execute("SELECT block_id FROM kb_wiki_page WHERE page=?", (page,)).fetchone()
                if not row:
                    print("missing-page not in state:", page)
                    continue
                base = row[0]
                if base:
                    if deleted := cur.execute(
                            "SELECT id FROM kb_block WHERE id=? OR id LIKE ?",
                            (base, base + "-%")).fetchall():
                        for (bid,) in deleted:
                            cur.execute("DELETE FROM kb_block_vec WHERE id=?", (bid,))
                            cur.execute("DELETE FROM kb_block_title_vec WHERE id=?", (bid,))
                        removed_blocks += len(deleted)
                    cur.execute("DELETE FROM kb_block WHERE id=? OR id LIKE ?", (base, base + "-%"))
                cur.execute("DELETE FROM kb_wiki_page WHERE page=?", (page,))
                removed_pages += 1
        con.commit()
        print("removed missing pages:", removed_pages, "blocks:", removed_blocks)

    if os.path.exists(dst):
        os.remove(dst)
    con.execute("ATTACH DATABASE ? AS snap", (dst,))
    con.execute("CREATE TABLE snap.kb_block AS SELECT * FROM main.kb_block WHERE doc_id LIKE 'wiki·%'")
    con.execute("CREATE TABLE snap.kb_block_vec AS SELECT * FROM main.kb_block_vec "
                "WHERE id IN (SELECT id FROM main.kb_block WHERE doc_id LIKE 'wiki·%')")
    con.execute("CREATE TABLE snap.kb_block_title_vec AS SELECT * FROM main.kb_block_title_vec "
                "WHERE id IN (SELECT id FROM main.kb_block WHERE doc_id LIKE 'wiki·%')")
    con.execute("CREATE TABLE snap.kb_wiki_page AS SELECT * FROM main.kb_wiki_page")
    con.execute("CREATE TABLE snap.kb_wiki_source AS SELECT * FROM main.kb_wiki_source")
    con.commit()
    con.execute("DETACH DATABASE snap")
    con.close()

    chk = sqlite3.connect(dst)
    c = chk.cursor()
    print("integrity:", c.execute("PRAGMA integrity_check").fetchone()[0])
    for t in ["kb_block", "kb_block_vec", "kb_block_title_vec", "kb_wiki_page", "kb_wiki_source"]:
        print("snapshot", t, c.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0])
    chk.close()

    if os.path.exists(out):
        os.remove(out)
    with open(dst, "rb") as f, gzip.open(out, "wb", compresslevel=9) as g:
        shutil.copyfileobj(f, g, length=1024 * 1024)
    print("snapshot:", dst, os.path.getsize(dst), "gz:", out, os.path.getsize(out))


if __name__ == "__main__":
    main()
