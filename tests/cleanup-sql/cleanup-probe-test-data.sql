-- ============================================================
--  清理 probe-kb-e2e.mjs 留在生产库里的一次性测试数据
--
--  背景：
--    tests/manual/probe-kb-e2e.mjs 把假事件 POST 到 8080 上【真实运行的业务层】，
--    而且刻意「每条用不同用户 + 不同群」来绕开限流 —— 于是凭空多出 5 个群。
--    跑完没有任何清理，这些行至今还在，把后台的统计口径整体抬高。
--    2026-09-28 在真实库上实测（数字会随真实数据增长而变，以文件末尾的核对查询为准）：
--      独立群     8 → 3          （虚高 +5）
--      独立用户 125 → 121        （虚高 +4）
--      实际作答  87 → 77         （虚高 +10）
--    ⚠️ 其中「独立群 8」正是「数据大屏 · 提问用户 / 群」显示 8 的原因 ——
--       真实只在 3 个群里有过提问记录。
--    并且因为它们的 guard_action 是 'pass'，还混进了「关键词排行」与
--    「该补什么」未命中榜（假热词：爆炸箭 II / 废料杯 / 卷毛山羊）。
--
--  怎么定位到这些行的（不是猜的）：
--    group_id 700000~700004 是连号，逐字段对得上 probe-kb-e2e.mjs:80 的
--      ev(2000 + i, 700000 + i, ..., 9000 + i)
--    → user_id 2000~2004、message_id 9000~9004、时间集中在 2026-09-22
--    它们正好是最早的 id 1~10；真实数据从 id 11 才开始，边界干净。
--
--  ⚠️ 必须按 id / group_id 删，【绝不能】按问题文本删：
--     测试用例里的「灵火祭坛是干什么的？」真实群友也问过，
--     按文本匹配会连真实问答一起删掉。
--
--  用法（在**宿主机**上跑，sqlite3 与 bot 并发写由 SQLite 自己串行化，不用停服务）：
--     备份： cp server/data/qa/qqbot.sqlite server/data/qa/qqbot-before-cleanup.sqlite
--     执行： sqlite3 server/data/qa/qqbot.sqlite < tests/cleanup-sql/cleanup-probe-test-data.sql
--
--  ⚠️ 不要在**容器里**跑：工作区是 9p 挂载，WAL 需要共享内存段，SQLite 会直接
--     报 disk I/O error（SQLITE_IOERR_SHMOPEN）—— 这是环境限制，不是权限问题。
-- ============================================================

-- ---------- 执行前预览：应恰好 10 行 ----------
SELECT s.id, s.group_id, s.user_id, s.ts, substr(r.question, 1, 30) AS question
FROM qa_stat s
LEFT JOIN qa_raw r ON r.id = s.id
WHERE s.group_id BETWEEN 700000 AND 700004
ORDER BY s.id;

BEGIN;

-- 先删引用方（qa_raw.id / qa_keyword.stat_id 都外键指向 qa_stat.id）
DELETE FROM answer_vote
 WHERE stat_id IN (SELECT id FROM qa_stat WHERE group_id BETWEEN 700000 AND 700004);

DELETE FROM qa_keyword
 WHERE stat_id IN (SELECT id FROM qa_stat WHERE group_id BETWEEN 700000 AND 700004);

DELETE FROM qa_raw
 WHERE id IN (SELECT id FROM qa_stat WHERE group_id BETWEEN 700000 AND 700004);

-- 最后删主表
DELETE FROM qa_stat
 WHERE group_id BETWEEN 700000 AND 700004;

COMMIT;

-- ---------- 执行后核对 ----------
-- 1) 应为 0
SELECT COUNT(*) AS 残留测试行 FROM qa_stat WHERE group_id BETWEEN 700000 AND 700004;

-- 2) 应为 0（孤儿行）
SELECT COUNT(*) AS 孤儿raw FROM qa_raw
 WHERE id NOT IN (SELECT id FROM qa_stat);
SELECT COUNT(*) AS 孤儿keyword FROM qa_keyword
 WHERE stat_id NOT IN (SELECT id FROM qa_stat);

-- 3) 全表口径（应为 独立群=3、提问量=77 上下，用户数随真实数据增长）
SELECT COUNT(DISTINCT group_id) AS 独立群,
       COUNT(DISTINCT user_id)  AS 独立用户,
       SUM(CASE WHEN guard_action = 'pass' THEN 1 ELSE 0 END) AS 实际作答
FROM qa_stat;

-- 4) ★ 数据大屏「提问用户 / 群」用的就是这个式子（QaAnalytics.overview）：
--    pass 且 group_id > 0。清理后 群 应该等于真实群数。
SELECT COUNT(DISTINCT CASE WHEN guard_action = 'pass' AND group_id > 0 THEN user_id END) AS 大屏用户,
       COUNT(DISTINCT CASE WHEN guard_action = 'pass' AND group_id > 0 THEN group_id END) AS 大屏群,
       COUNT(*) AS 群消息总数
FROM qa_stat WHERE group_id > 0;
