package com.example.qqbot.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 准入判定：档位 + 两层名单。
 *
 * <p>这段逻辑有五层、两个维度，最容易出错的地方不是"某一条对不对"，
 * 而是<b>先后顺序</b>：具体的要先于笼统的，单命令要先于全局。
 * 所以用例按顺序逐层钉。
 */
class AccessRulesTest {

    private static final long USER = 1001L;
    private static final long OTHER = 1002L;
    private static final long GROUP = 2001L;
    private static final long OTHER_GROUP = 2002L;

    private static AclEntry white(long uid) {
        return new AclEntry(AclEntry.WHITE, AclEntry.UID, uid);
    }

    private static AclEntry black(long uid) {
        return new AclEntry(AclEntry.BLACK, AclEntry.UID, uid);
    }

    private static AclEntry whiteGroup(long gid) {
        return new AclEntry(AclEntry.WHITE, AclEntry.GID, gid);
    }

    private static AclEntry blackGroup(long gid) {
        return new AclEntry(AclEntry.BLACK, AclEntry.GID, gid);
    }

    private static boolean allows(CommandAccess access, CommandRole role,
                                  List<AclEntry> commandAcl, List<AclEntry> globalAcl) {
        return AccessRules.allows(access, role, USER, GROUP, commandAcl, globalAcl);
    }

    // ==================== 第一层：这条命令自己的名单 ====================

    @Test
    @DisplayName("★ 命令白名单里有他的 QQ 号 → 放行，全局黑名单也拦不住")
    void commandWhitelistBeatsGlobalBlacklist() {
        assertThat(allows(CommandAccess.ADMIN, CommandRole.MEMBER,
                List.of(white(USER)), List.of(black(USER))))
                .as("全局封了，但这条命令给他开了口子")
                .isTrue();
    }

    @Test
    @DisplayName("★ 命令黑名单里有他的 QQ 号 → 拒绝，哪怕档位是全体")
    void commandBlacklistBeatsAll() {
        assertThat(allows(CommandAccess.ALL, CommandRole.OWNER,
                List.of(black(USER)), List.of()))
                .isFalse();
    }

    @Test
    @DisplayName("命令名单里的群号同样生效")
    void commandListAcceptsGroupId() {
        assertThat(allows(CommandAccess.ALL, CommandRole.MEMBER,
                List.of(whiteGroup(GROUP)), List.of())).isTrue();
        assertThat(allows(CommandAccess.ALL, CommandRole.OWNER,
                List.of(blackGroup(GROUP)), List.of())).isFalse();
    }

    @Test
    @DisplayName("★ QQ 号比群号具体：群里放开但单独挡掉某个人")
    void userEntryBeatsGroupEntry() {
        // 群进白名单，他个人进黑名单
        assertThat(allows(CommandAccess.ALL, CommandRole.MEMBER,
                List.of(whiteGroup(GROUP), black(USER)), List.of()))
                .isFalse();
    }

    @Test
    @DisplayName("名单里的群号对别的群无效")
    void groupEntryOnlyMatchesItsOwnGroup() {
        // 档位设成白名单，这样"没命中"才会体现成拒绝；
        // 用全体档测不出东西 —— 反正谁都能进
        assertThat(allows(CommandAccess.WHITELIST, CommandRole.MEMBER,
                List.of(whiteGroup(OTHER_GROUP)), List.of()))
                .as("别的群的白名单不该放过这个群的人")
                .isFalse();
        assertThat(allows(CommandAccess.WHITELIST, CommandRole.MEMBER,
                List.of(whiteGroup(GROUP)), List.of()))
                .as("本群的群号应该放行")
                .isTrue();
    }

    @Test
    @DisplayName("名单里的 QQ 号对别人无效")
    void entryForSomebodyElseDoesNotApply() {
        assertThat(allows(CommandAccess.ADMIN, CommandRole.MEMBER,
                List.of(white(OTHER)), List.of())).isFalse();
    }

    // ==================== 第二层：全局名单 ====================

    @Test
    @DisplayName("★ 全局白名单能给普通成员提权：档位是管理员，但他照样能用")
    void globalWhitelistGrantsAccess() {
        assertThat(allows(CommandAccess.ADMIN, CommandRole.MEMBER,
                List.of(), List.of(white(USER)))).isTrue();
    }

    @Test
    @DisplayName("★ 全局黑名单能挡住群主")
    void globalBlacklistBeatsOwnerRole() {
        assertThat(allows(CommandAccess.ALL, CommandRole.OWNER,
                List.of(), List.of(black(USER)))).isFalse();
    }

    @Test
    @DisplayName("命令名单没命中时，才轮到全局名单")
    void globalAppliesOnlyWhenCommandListIsSilent() {
        assertThat(allows(CommandAccess.ALL, CommandRole.MEMBER,
                List.of(black(OTHER)), List.of(black(USER))))
                .as("命令名单里没有他，于是落到全局")
                .isFalse();
    }

    // ==================== 第三层：档位 ====================

    @Test
    @DisplayName("全体：谁都能用")
    void allAllowsEveryone() {
        assertThat(allows(CommandAccess.ALL, CommandRole.MEMBER, List.of(), List.of())).isTrue();
    }

    @Test
    @DisplayName("管理员：群管与群主能用，普通成员不能")
    void adminNeedsAdminRole() {
        assertThat(allows(CommandAccess.ADMIN, CommandRole.MEMBER, List.of(), List.of())).isFalse();
        assertThat(allows(CommandAccess.ADMIN, CommandRole.ADMIN, List.of(), List.of())).isTrue();
        assertThat(allows(CommandAccess.ADMIN, CommandRole.OWNER, List.of(), List.of())).isTrue();
    }

    @Test
    @DisplayName("群主：只有群主能用")
    void ownerNeedsOwnerRole() {
        assertThat(allows(CommandAccess.OWNER, CommandRole.ADMIN, List.of(), List.of())).isFalse();
        assertThat(allows(CommandAccess.OWNER, CommandRole.OWNER, List.of(), List.of())).isTrue();
    }

    @Test
    @DisplayName("★ 白名单档但名单是空的 → 谁都进不来")
    void whitelistTierWithEmptyListAllowsNobody() {
        assertThat(allows(CommandAccess.WHITELIST, CommandRole.OWNER, List.of(), List.of()))
                .as("这档的意思就是'只对名单里的人开'，名单空就是没开")
                .isFalse();
    }

    @Test
    @DisplayName("★ 黑名单档且他不在名单里 → 照常能用")
    void blacklistTierAllowsEveryoneElse() {
        assertThat(allows(CommandAccess.BLACKLIST, CommandRole.MEMBER,
                List.of(black(OTHER)), List.of())).isTrue();
    }

    @Test
    @DisplayName("私聊没有群号，群条目不会误命中")
    void privateChatIgnoresGroupEntries() {
        assertThat(AccessRules.allows(CommandAccess.ADMIN, CommandRole.MEMBER, USER, 0L,
                List.of(whiteGroup(GROUP)), List.of())).isFalse();
    }

    // ==================== 档位解析 ====================

    @Test
    @DisplayName("旧值 member 等价于全体，不会把老库的人全挡在外面")
    void legacyMemberMeansAll() {
        assertThat(CommandAccess.of("member")).isEqualTo(CommandAccess.ALL);
        assertThat(CommandAccess.of(null)).isEqualTo(CommandAccess.ALL);
        assertThat(CommandAccess.of("")).isEqualTo(CommandAccess.ALL);
    }

    @Test
    @DisplayName("认不出来的值按全体处理 —— 这个字段配错不该锁死所有人")
    void unknownValueFallsBackToAll() {
        assertThat(CommandAccess.of("admni")).isEqualTo(CommandAccess.ALL);
    }

    @Test
    @DisplayName("五个档位都能解析出来")
    void allTiersRoundTrip() {
        for (CommandAccess access : CommandAccess.values()) {
            assertThat(CommandAccess.of(access.code())).isEqualTo(access);
        }
    }

    @Test
    @DisplayName("私聊里的拒绝理由要说「去群里用」，不说「只有群主」")
    void privateChatDenialWording() {
        assertThat(CommandAccess.ADMIN.describe(false)).contains("要在群里用");
        assertThat(CommandAccess.OWNER.describe(false)).contains("要在群里用");
        assertThat(CommandAccess.ADMIN.describe(true)).contains("群主或管理员");
    }
}
