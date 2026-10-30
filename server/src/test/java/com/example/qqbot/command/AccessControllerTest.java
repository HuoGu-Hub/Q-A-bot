package com.example.qqbot.command;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.AclRepository;
import com.example.qqbot.persistence.CommandPolicyRepository;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import com.example.qqbot.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 权限与数据范围的管理接口。
 *
 * <p>这一层的错误后果特别重：配错一条名单是"某个人突然能用"，
 * 配错数据范围是"某个群突然能看到别的群"。所以用例集中在
 * <b>默认值</b>、<b>整份覆盖</b>与<b>非法输入被丢掉</b>三件事上。
 */
class AccessControllerTest {

    @TempDir
    Path base;

    private static final long CMD = 7L;

    private SqliteDatabase db;
    private AclStore aclStore;
    private CommandPolicyStore policyStore;
    private AccessController controller;

    @BeforeEach
    void setUp() {
        PersistenceProperties persist = new PersistenceProperties();
        persist.setDb(base.resolve("acl.sqlite").toString());
        db = new SqliteDatabase(persist);
        db.init();
        Jdbc jdbc = new Jdbc(db);
        ObjectMapper mapper = new ObjectMapper();
        aclStore = new AclStore(new AclRepository(jdbc));
        aclStore.init();
        policyStore = new CommandPolicyStore(new CommandPolicyRepository(jdbc), mapper);
        policyStore.init();
        controller = new AccessController(aclStore, policyStore);
    }

    @AfterEach
    void close() {
        db.close();
    }

    private static AccessController.PolicyRequest policy(String scope, List<Long> groups) {
        return new AccessController.PolicyRequest(scope, groups, null, null, null);
    }

    private static AccessController.AclItemRequest item(String type, long value) {
        return new AccessController.AclItemRequest(type, value);
    }

    private static AccessController.AclRequest acl(List<AccessController.AclItemRequest> white,
                                                   List<AccessController.AclItemRequest> black) {
        return new AccessController.AclRequest(white, black);
    }

    // ==================== 数据范围 ====================

    @Test
    @DisplayName("★ 没配过就是本群 —— 新建命令天然隔离")
    void defaultPolicyIsGroupOnly() {
        DataPolicy policy = policyStore.of(CMD);

        assertThat(policy.scope()).isEqualTo(DataPolicy.DataScope.GROUP);
        assertThat(policy.groups()).isEmpty();
        assertThat(policy.showGroup()).isFalse();
        assertThat(policy.showUser()).isFalse();
    }

    @Test
    @DisplayName("★ 存进去再读出来还是那份")
    void policyRoundTrips() {
        controller.savePolicy(CMD, new AccessController.PolicyRequest(
                "SOME", List.of(1L, 2L), true, false, null));

        DataPolicy policy = policyStore.of(CMD);
        assertThat(policy.scope()).isEqualTo(DataPolicy.DataScope.SOME);
        assertThat(policy.groups()).containsExactly(1L, 2L);
        assertThat(policy.marksGroup()).isTrue();
        assertThat(policy.marksUser()).as("QQ 号默认不给").isFalse();
    }

    @Test
    @DisplayName("换一份策略是整份覆盖，不会残留旧的群")
    void policyIsReplacedNotMerged() {
        controller.savePolicy(CMD, policy("SOME", List.of(1L, 2L)));
        controller.savePolicy(CMD, policy("SOME", List.of(3L)));

        assertThat(policyStore.of(CMD).groups()).containsExactly(3L);
    }

    @Test
    @DisplayName("非法的数据范围被拒，且不会把原策略改坏")
    void badScopeIsRejected() {
        controller.savePolicy(CMD, policy("ALL", List.of()));

        assertThatThrownBy(() -> controller.savePolicy(CMD, policy("到处都看", List.of())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("GROUP");
        assertThat(policyStore.of(CMD).scope()).as("原值保持不动").isEqualTo(DataPolicy.DataScope.ALL);
    }

    @Test
    @DisplayName("★ 私聊开关默认关，配了之后读得回来")
    void privateSwitchDefaultsOffAndRoundTrips() {
        assertThat(policyStore.of(CMD).allowPrivate()).as("没配过就是关").isFalse();

        controller.savePolicy(CMD, new AccessController.PolicyRequest(
                null, null, null, null, true));

        assertThat(policyStore.of(CMD).allowPrivate()).isTrue();
    }

    @Test
    @DisplayName("本群策略下，两个显示开关都不生效 —— 没有来源可标")
    void groupScopeNeverMarksOrigin() {
        DataPolicy policy = new DataPolicy(DataPolicy.DataScope.GROUP, List.of(), true, true, false);

        assertThat(policy.marksGroup()).isFalse();
        assertThat(policy.marksUser()).isFalse();
    }

    // ==================== 名单 ====================

    @Test
    @DisplayName("★ 名单整份覆盖，白黑两色分开存")
    void aclRoundTrips() {
        controller.saveCommandAcl(CMD, acl(
                List.of(item("uid", 1001)),
                List.of(item("gid", 2001))));

        List<AclEntry> entries = aclStore.ofCommand(CMD);
        assertThat(entries).hasSize(2);
        assertThat(entries).anyMatch(e -> e.isWhite() && e.isUser() && e.value() == 1001L);
        assertThat(entries).anyMatch(e -> !e.isWhite() && !e.isUser() && e.value() == 2001L);
    }

    @Test
    @DisplayName("再存一次是替换，不是追加")
    void aclIsReplaced() {
        controller.saveCommandAcl(CMD, acl(List.of(item("uid", 1)), List.of()));
        controller.saveCommandAcl(CMD, acl(List.of(item("uid", 2)), List.of()));

        assertThat(aclStore.ofCommand(CMD)).extracting(AclEntry::value).containsExactly(2L);
    }

    @Test
    @DisplayName("★ 认不出来的条目类型与坏值被丢掉，不会写进库")
    void badEntriesAreDropped() {
        controller.saveCommandAcl(CMD, acl(
                List.of(item("uid", 1001), item("什么都不是", 1002), item("uid", 0)),
                List.of()));

        assertThat(aclStore.ofCommand(CMD)).extracting(AclEntry::value).containsExactly(1001L);
    }

    @Test
    @DisplayName("重复条目只留一条")
    void duplicatesCollapse() {
        controller.saveCommandAcl(CMD, acl(
                List.of(item("uid", 1001), item("uid", 1001)), List.of()));

        assertThat(aclStore.ofCommand(CMD)).hasSize(1);
    }

    @Test
    @DisplayName("★ 板块名单与指令名单分开存：同 id 不串")
    void boardAclIsSeparateFromCommandAcl() {
        controller.saveCommandAcl(7L, acl(List.of(item("uid", 1001)), List.of()));
        controller.saveBoardAcl(7L, acl(List.of(), List.of(item("gid", 2001))));

        assertThat(aclStore.ofCommand(7L)).extracting(AclEntry::value).containsExactly(1001L);
        assertThat(aclStore.ofBoard(7L)).extracting(AclEntry::value).containsExactly(2001L);
    }

    @Test
    @DisplayName("板块删除时名单一起清掉")
    void deleteBoardClearsAcl() {
        controller.saveBoardAcl(7L, acl(List.of(item("uid", 1001)), List.of()));

        aclStore.deleteBoard(7L);

        assertThat(aclStore.ofBoard(7L)).isEmpty();
    }

    @Test
    @DisplayName("全局名单与某条命令的名单互不干扰")
    void globalAndCommandListsAreSeparate() {
        controller.saveCommandAcl(CMD, acl(List.of(item("uid", 1)), List.of()));
        controller.saveGlobalAcl(acl(List.of(), List.of(item("uid", 2))));

        assertThat(aclStore.ofCommand(CMD)).extracting(AclEntry::value).containsExactly(1L);
        assertThat(aclStore.global()).extracting(AclEntry::value).containsExactly(2L);
    }

    @Test
    @DisplayName("清空名单就是传空表")
    void emptyListClears() {
        controller.saveCommandAcl(CMD, acl(List.of(item("uid", 1)), List.of()));
        controller.saveCommandAcl(CMD, acl(null, null));

        assertThat(aclStore.ofCommand(CMD)).isEmpty();
    }
}
