package com.example.qqbot.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份类型 {@link EntryId} 的语义 —— 它是「块 id 等于词条 en」这条约定的载体。
 *
 * <h2>为什么这些性质要钉住</h2>
 * 类型化本身不产生行为变化，但**转换规则**会：
 * <ul>
 *   <li>进领域时 trim —— 从 Excel / TSV / SQLite 读进来的值常带行尾空白，
 *       而带空白的身份会查不到、又会插进新行；</li>
 *   <li>出领域时原样 —— 日志、TSV、xlsx、异常消息里的 id 必须和以前逐字一致，
 *       否则导出的表会认不回来、错误信息也会对不上；</li>
 *   <li>相等按值 —— 两个不同转换点造出来的同一个身份必须能对上，
 *       否则"同一个块"在两张 Map 里会变成两条。</li>
 * </ul>
 * 这三条之外，本类刻意什么都不做：它不是实体，只是身份。
 */
class EntryIdTest {

    @Test
    @DisplayName("★ 进领域时 trim：带空白的身份必须和在库里的那一行对上")
    void trimsOnTheWayIn() {
        assertThat(EntryId.of("  flame-altar-0 \t")).isEqualTo(EntryId.of("flame-altar-0"));
        assertThat(EntryId.of("  flame-altar-0 ").value()).isEqualTo("flame-altar-0");
    }

    @Test
    @DisplayName("★ 出领域时原样：toString 就是原始字符串，不是 record 的默认写法")
    void toStringIsTheRawValue() {
        EntryId id = EntryId.of("flame-altar-0");

        assertThat(id.toString()).isEqualTo("flame-altar-0");
        // 日志与 TSV 拼接全靠它 —— 默认的 EntryId[value=...] 会让导出表认不回来
        assertThat("块 " + id + " 已下架").isEqualTo("块 flame-altar-0 已下架");
    }

    @Test
    @DisplayName("空值不抛异常：没有 id 给空身份，比 null 少一层判空")
    void nullBecomesBlank() {
        assertThat(EntryId.of(null)).isEqualTo(EntryId.empty());
        assertThat(EntryId.empty().isBlank()).isTrue();
        assertThat(EntryId.of("  ").isBlank()).isTrue();
        assertThat(EntryId.of("a").isBlank()).isFalse();
    }

    @Test
    @DisplayName("相等按值：同一个身份在不同转换点造出来也要能对上")
    void equalityIsByValue() {
        Map<EntryId, String> seen = new HashMap<>();
        seen.put(EntryId.of("a"), "第一次");

        // 换一个"转换点"再造一次，必须命中同一格
        seen.put(EntryId.of(" a "), "第二次");

        assertThat(seen).hasSize(1).containsEntry(EntryId.of("a"), "第二次");
        assertThat(EntryId.of("a")).isNotEqualTo(EntryId.of("b"));
    }

    @Test
    @DisplayName("★ 身份**不等于**它的原始字符串 —— 混合类型 equals 恒为 false，编译器看不住")
    void isNotEqualToItsRawString() {
        // 这条断言是在钉一个真踩过的坑：置顶逻辑里写成 entryId.equals("原字符串")，
        // 于是"已经有它"永远判不成立，同一块在结果里出现两次。
        // 类型化之后这种错不会编译报错，只能靠"比较要么两边都是身份，要么两边都取 value()"。
        assertThat(EntryId.of("a")).isNotEqualTo("a");
        assertThat("a").isNotEqualTo(EntryId.of("a"));
        // 正确写法
        assertThat(EntryId.of("a").value()).isEqualTo("a");
    }
}
