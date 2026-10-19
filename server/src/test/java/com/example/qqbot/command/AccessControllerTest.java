package com.example.qqbot.command;

import com.example.qqbot.config.PersistenceProperties;
import com.example.qqbot.persistence.AclRepository;
import com.example.qqbot.persistence.CommandPolicyRepository;
import com.example.qqbot.persistence.Jdbc;
import com.example.qqbot.persistence.SqliteDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
        controller.savePolicy(CMD, Map.of("scope", "SOME", "groups", List.of(1, 2),
                "showGroup", true, "showUser", false));

        DataPolicy policy = policyStore.of(CMD);
        assertThat(policy.scope()).isEqualTo(DataPolicy.DataScope.SOME);
        assertThat(policy.groups()).containsExactly(1L, 2L);
        assertThat(policy.marksGroup()).isTrue();
        assertThat(policy.marksUser()).as("QQ 号默认不给").isFalse();
    }

    @Test
    @DisplayName("换一份策略是整份覆盖，不会残留旧的群")
    void policyIsReplacedNotMerged() {
        controller.savePolicy(CMD, Map.of("scope", "SOME", "groups", List.of(1, 2)));
        controller.savePolicy(CMD, Map.of("scope", "SOME", "groups", List.of(3)));

        assertThat(policyStore.of(CMD).groups()).containsExactly(3L);
    }

    @Test
    @DisplayName("非法的数据范围被拒，且不会把原策略改坏")
    void badScopeIsRejected() {
        controller.savePolicy(CMD, Map.of("scope", "ALL"));

        var response = controller.savePolicy(CMD, Map.of("scope", "到处都看"));

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(policyStore.of(CMD).scope()).as("原值保持不动").isEqualTo(DataPolicy.DataScope.ALL);
    }

    @Test
    @DisplayName("本群策略下，两个显示开关都不生效 —— 没有来源可标")
    void groupScopeNeverMarksOrigin() {
        DataPolicy policy = new DataPolicy(DataPolicy.DataScope.GROUP, List.of(), true, true);

        assertThat(policy.marksGroup()).isFalse();
        assertThat(policy.marksUser()).isFalse();
    }

    // ==================== 名单 ====================

    @Test
    @DisplayName("★ 名单整份覆盖，白黑两色分开存")
    void aclRoundTrips() {
        controller.saveCommandAcl(CMD, Map.of(
                "white", List.of(Map.of("type", "uid", "value", 1001)),
                "black", List.of(Map.of("type", "gid", "value", 2001))));

        List<AclEntry> entries = aclStore.ofCommand(CMD);
        assertThat(entries).hasSize(2);
        assertThat(entries).anyMatch(e -> e.isWhite() && e.isUser() && e.value() == 1001L);
        assertThat(entries).anyMatch(e -> !e.isWhite() && !e.isUser() && e.value() == 2001L);
    }

    @Test
    @DisplayName("再存一次是替换，不是追加")
    void aclIsReplaced() {
        controller.saveCommandAcl(CMD, Map.of("white", List.of(Map.of("type", "uid", "value", 1))));
        controller.saveCommandAcl(CMD, Map.of("white", List.of(Map.of("type", "uid", "value", 2))));

        assertThat(aclStore.ofCommand(CMD)).extracting(AclEntry::value).containsExactly(2L);
    }

    @Test
    @DisplayName("★ 认不出来的条目类型与坏值被丢掉，不会写进库")
    void badEntriesAreDropped() {
        controller.saveCommandAcl(CMD, Map.of(
                "white", List.of(
                        Map.of("type", "uid", "value", 1001),
                        Map.of("type", "什么都不是", "value", 1002),
                        Map.of("type", "uid", "value", "abc"),
                        Map.of("type", "uid", "value", 0))));

        assertThat(aclStore.ofCommand(CMD)).extracting(AclEntry::value).containsExactly(1001L);
    }

    @Test
    @DisplayName("重复条目只留一条")
    void duplicatesCollapse() {
        controller.saveCommandAcl(CMD, Map.of("white", List.of(
                Map.of("type", "uid", "value", 1001),
                Map.of("type", "uid", "value", 1001))));

        assertThat(aclStore.ofCommand(CMD)).hasSize(1);
    }

    @Test
    @DisplayName("全局名单与某条命令的名单互不干扰")
    void globalAndCommandListsAreSeparate() {
        controller.saveCommandAcl(CMD, Map.of("white", List.of(Map.of("type", "uid", "value", 1))));
        controller.saveGlobalAcl(Map.of("black", List.of(Map.of("type", "uid", "value", 2))));

        assertThat(aclStore.ofCommand(CMD)).extracting(AclEntry::value).containsExactly(1L);
        assertThat(aclStore.global()).extracting(AclEntry::value).containsExactly(2L);
    }

    @Test
    @DisplayName("清空名单就是传空表")
    void emptyListClears() {
        controller.saveCommandAcl(CMD, Map.of("white", List.of(Map.of("type", "uid", "value", 1))));
        controller.saveCommandAcl(CMD, Map.of());

        assertThat(aclStore.ofCommand(CMD)).isEmpty();
    }
}
