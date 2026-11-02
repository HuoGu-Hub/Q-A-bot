-- 用本地导出的 wiki 语料替换线上 wiki 表，其他表一行不动。
--
-- 用法（在 snapshot 所在目录执行；服务必须先停）：
--   cd /root
--   sqlite3 /opt/qqbot/server/data/qa/qqbot.sqlite < wiki-merge.sql
--
-- snapshot 由本地 scripts 的 wiki-export.py 生成，包含：
--   kb_block / kb_block_vec / kb_block_title_vec / kb_wiki_page / kb_wiki_source
PRAGMA foreign_keys = OFF;
ATTACH DATABASE 'wiki-snapshot.sqlite' AS snap;

BEGIN IMMEDIATE;

DELETE FROM kb_block_vec
 WHERE id IN (SELECT id FROM kb_block WHERE doc_id LIKE 'wiki·%');
DELETE FROM kb_block_title_vec
 WHERE id IN (SELECT id FROM kb_block WHERE doc_id LIKE 'wiki·%');
DELETE FROM kb_block WHERE doc_id LIKE 'wiki·%';

INSERT INTO kb_block (id, doc_id, title, body, url, tags, source, retired, updated_at, alias)
SELECT id, doc_id, title, body, url, tags, source, retired, updated_at, alias
  FROM snap.kb_block;

INSERT INTO kb_block_vec (id, dim, vec)
SELECT id, dim, vec FROM snap.kb_block_vec;

INSERT INTO kb_block_title_vec (id, title, dim, vec)
SELECT id, title, dim, vec FROM snap.kb_block_title_vec;

DELETE FROM kb_wiki_page;
INSERT INTO kb_wiki_page
       (page, source, revid, page_updated_at, synced_at, chars, block_id, block_count, error)
SELECT page, source, revid, page_updated_at, synced_at, chars, block_id, block_count, error
  FROM snap.kb_wiki_page;

DELETE FROM kb_wiki_source;
INSERT INTO kb_wiki_source (name, kind, enabled, updated_at)
SELECT name, kind, enabled, updated_at FROM snap.kb_wiki_source;

COMMIT;
PRAGMA foreign_keys = ON;

SELECT 'wiki blocks', COUNT(*) FROM kb_block WHERE doc_id LIKE 'wiki·%';
SELECT 'wiki pages', COUNT(*) FROM kb_wiki_page;
SELECT 'wiki sources', COUNT(*) FROM kb_wiki_source;
