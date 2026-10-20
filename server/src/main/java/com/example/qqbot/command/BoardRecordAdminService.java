package com.example.qqbot.command;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 板块记录的后台查看与恢复。
 *
 * <h2>为什么只做"看、撤销、恢复"</h2>
 * 记录是群里发出来的数据，后台不该替群友改内容 —— 那会让"谁发的"变得不可信。
 * 但撤销与恢复必须给管理员一个入口：软删之后数据还在，只是群里看不见。
 */
@Service
public class BoardRecordAdminService {

    private final BoardStore boards;
    private final BoardRecordStore records;

    public BoardRecordAdminService(BoardStore boards, BoardRecordStore records) {
        this.boards = boards;
        this.records = records;
    }

    /**
     * 一条记录的后台形状。
     *
     * @param when  人话时间：定时的是「每天 21:30」，立即展示的是「立即展示」
     * @param line  与群里 {@code /板块} 列表同一行格式
     */
    public record RecordView(long id, String board, long groupId, long userId, String nickname,
                             String text, Map<String, String> params, String delivery,
                             String when, String line, String status, int attempts,
                             String lastError, Instant fireAt, Instant createdAt,
                             Instant sentAt, Instant deletedAt, boolean deleted) {
    }

    /**
     * @param board       板块名
     * @param groupId     0 = 所有群；>0 = 只看这个群
     * @param withDeleted 连已撤销的一起看，用来恢复
     */
    public List<RecordView> list(String board, long groupId, boolean withDeleted, int limit) {
        Board found = boards.find(board);
        if (found == null) {
            throw new IllegalArgumentException("板块不存在：/" + board);
        }
        int cap = Math.max(1, Math.min(limit, 500));
        List<BoardRecord> rows = withDeleted
                ? records.queryWithDeleted(found, groupId, cap)
                : records.query(found, groupId, cap);
        List<RecordView> out = new ArrayList<>(rows.size());
        for (BoardRecord row : rows) {
            out.add(view(row));
        }
        return out;
    }

    /** 撤销：只打标记，记录还在 */
    public void softDelete(long id) {
        BoardRecord record = require(id);
        if (record.isDeleted()) {
            throw new IllegalArgumentException("这条已经撤销过了");
        }
        if (!records.softDelete(id, Instant.now())) {
            throw new IllegalArgumentException("撤销失败：没有这条记录");
        }
    }

    /** 从「已撤销」里捡回来 */
    public void restore(long id) {
        BoardRecord record = require(id);
        if (!record.isDeleted()) {
            throw new IllegalArgumentException("这条没有被撤销");
        }
        if (!records.restore(id)) {
            throw new IllegalArgumentException("恢复失败：没有这条记录");
        }
    }

    private BoardRecord require(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("缺少记录编号");
        }
        BoardRecord record = records.find(id);
        if (record == null) {
            throw new IllegalArgumentException("没有 #" + id + " 这条记录");
        }
        return record;
    }

    private static RecordView view(BoardRecord record) {
        return new RecordView(record.id(), record.board(), record.groupId(), record.userId(),
                record.nickname(), record.text(), record.params(), record.delivery(),
                record.whenText(), record.displayLine(), record.status(), record.attempts(),
                record.lastError(), record.fireAt(), record.createdAt(), record.sentAt(),
                record.deletedAt(), record.isDeleted());
    }
}
