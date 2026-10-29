package com.example.qqbot.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 板块删除只认作者或本群管理员。编号是全局自增的，不能只按编号删。
 */
class BoardDeleteAuthTest {

    private static BoardRecord record(long groupId, long userId) {
        return new BoardRecord(12, "公告", groupId, userId, "阿明", "测试", Map.of(),
                Board.DIRECT, null, "once", List.of(), null, "pending", 0, null,
                Instant.now(), null, null);
    }

    @Test
    @DisplayName("作者可以删自己的记录，跨群也行")
    void authorCanDeleteOwn() {
        assertThat(BoardRunner.canDelete(record(100L, 200L), 200L, 300L, CommandRole.MEMBER))
                .isTrue();
    }

    @Test
    @DisplayName("本群管理员可以删本群别人的记录")
    void sameGroupAdminCanDelete() {
        assertThat(BoardRunner.canDelete(record(100L, 200L), 201L, 100L, CommandRole.ADMIN))
                .isTrue();
    }

    @Test
    @DisplayName("普通成员不能删本群别人的记录")
    void memberCannotDeleteOthers() {
        assertThat(BoardRunner.canDelete(record(100L, 200L), 201L, 100L, CommandRole.MEMBER))
                .isFalse();
    }

    @Test
    @DisplayName("别群管理员不能删这个群的记录")
    void otherGroupAdminCannotDelete() {
        assertThat(BoardRunner.canDelete(record(100L, 200L), 201L, 999L, CommandRole.ADMIN))
                .isFalse();
    }
}
