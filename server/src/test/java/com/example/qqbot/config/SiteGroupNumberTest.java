package com.example.qqbot.config;

import com.example.qqbot.site.SitePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 群号的规范形式。
 *
 * <p><b>真实故障（2026-10-29）</b>：后台「站点信息」里粘进去的群号被 Excel 改成科学计数法，
 * 覆盖层文件里落成 {@code group-number: 2.09127588E8}，公开站「关于」页就原样显示
 * 「2.09127588E8」—— 群号从来没有这种写法。
 */
class SiteGroupNumberTest {

    @Test
    @DisplayName("★ 科学计数法要展开成整数：2.09127588E8 → 209127588")
    void expandsScientificNotation() {
        assertThat(SitePolicy.normalizeGroupNumber("2.09127588E8")).isEqualTo("209127588");
        assertThat(SitePolicy.normalizeGroupNumber("2.09127588e+8")).isEqualTo("209127588");
        assertThat(SitePolicy.normalizeGroupNumber("1.2E9")).isEqualTo("1200000000");
    }

    @Test
    @DisplayName("本来就是对的不动：纯数字、前后空格、.0 结尾")
    void keepsPlainDigits() {
        assertThat(SitePolicy.normalizeGroupNumber("209127588")).isEqualTo("209127588");
        assertThat(SitePolicy.normalizeGroupNumber(" 209127588 ")).isEqualTo("209127588");
        assertThat(SitePolicy.normalizeGroupNumber("209127588.0")).isEqualTo("209127588");
    }

    @Test
    @DisplayName("不是群号的别乱改：文本原样返回，小数不四舍五入")
    void leavesNonNumbersAlone() {
        assertThat(SitePolicy.normalizeGroupNumber("见群公告")).isEqualTo("见群公告");
        assertThat(SitePolicy.normalizeGroupNumber("123.45")).isEqualTo("123.45");
        assertThat(SitePolicy.normalizeGroupNumber("")).isEmpty();
        assertThat(SitePolicy.normalizeGroupNumber(null)).isEmpty();
    }

    @Test
    @DisplayName("getter 只对外给规范形式 —— 覆盖层里的脏值不会漏到公开站")
    void getterNormalizes() {
        SiteProperties p = new SiteProperties();
        p.setGroupNumber("2.09127588E8");
        assertThat(p.getGroupNumber()).isEqualTo("209127588");
    }
}
