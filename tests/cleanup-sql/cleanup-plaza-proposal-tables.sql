-- ============================================================
--  清理已下线功能遗留的表与探针数据：问答广场 / 旧提案 / 旧投稿
--
--  什么时候执行：
--    新版代码（不含 plaza / kb.proposal）已经部署，旧版本已停止。
--    旧代码仍会读写这些表，运行中执行会让旧进程报错。
--
--  执行前必须备份：
--    cp server/data/qa/qqbot.sqlite server/data/qa/qqbot-before-remove-plaza.sqlite
--
--  当前库的实际情况：
--    answer_vote   0 行
--    help_request  1 行，来自 2026-10-10 的探针，status=pending
--    kb_proposal   0 行
--    kb_contribution / kb_token  0 行，旧投稿功能遗留
--    qa_stat.source='plaza'  1 行，同一批探针生成
-- ============================================================

-- ---------- 执行前预览 ----------
SELECT 'answer_vote' AS tbl, COUNT(*) AS rows FROM answer_vote
UNION ALL SELECT 'help_request', COUNT(*) FROM help_request
UNION ALL SELECT 'kb_proposal', COUNT(*) FROM kb_proposal
UNION ALL SELECT 'kb_contribution', COUNT(*) FROM kb_contribution
UNION ALL SELECT 'kb_token', COUNT(*) FROM kb_token
UNION ALL SELECT 'qa_stat.source=plaza', COUNT(*) FROM qa_stat WHERE source = 'plaza';

BEGIN;

-- ---------- 1. 删掉广场生成的那条问答及附属行 ----------
DELETE FROM qa_keyword WHERE stat_id IN (SELECT id FROM qa_stat WHERE source = 'plaza');
DELETE FROM qa_raw     WHERE id       IN (SELECT id FROM qa_stat WHERE source = 'plaza');
DELETE FROM qa_stat    WHERE source = 'plaza';

-- ---------- 2. 删掉功能表 ----------
DROP TABLE IF EXISTS answer_vote;
DROP TABLE IF EXISTS help_request;
DROP TABLE IF EXISTS kb_proposal;

-- ---------- 3. 顺带删掉旧投稿 / 邀请码的空表 ----------
DROP TABLE IF EXISTS kb_contribution;
DROP TABLE IF EXISTS kb_token;

COMMIT;

-- ---------- 执行后核对 ----------
-- 1) 应为 0
SELECT COUNT(*) AS 残留广场问答 FROM qa_stat WHERE source = 'plaza';

-- 2) 这些表应该都不存在了
SELECT name FROM sqlite_master
 WHERE type = 'table'
   AND name IN ('answer_vote', 'help_request', 'kb_proposal', 'kb_contribution', 'kb_token');

-- 3) 问答总量应该只少 1 行
SELECT COUNT(*) AS 问答总量 FROM qa_stat;
