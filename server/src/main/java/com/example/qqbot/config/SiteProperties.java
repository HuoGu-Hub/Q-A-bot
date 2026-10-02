package com.example.qqbot.config;

import com.example.qqbot.site.Carousel;
import com.example.qqbot.site.SitePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 站点信息绑定 —— 对应 application.yml 里的 {@code app.site.*}
 *
 * <p>这些是**热生效**的 —— 后台改完立即起作用，不用重启。
 *
 * <h2>2026-10-02：{@code Carousel} 搬去了 {@code site} 包</h2>
 * 它是<b>公开站自己的领域词汇</b>，只是碰巧从 yml 绑定过来 —— 留在本类的嵌套类里，
 * 会让 {@code site} 包看起来"依赖配置的形状"。
 *
 * <p>搬迁是**纯搬运**：yml 的键一个都没变，绑定关系也没变 ——
 * 本类仍然持有那个对象、仍然由 Spring 填值。
 *
 * <p>业务侧只读视图见 {@link SitePolicy} —— 业务包只依赖它，不依赖本类。
 */
@ConfigurationProperties(prefix = "app.site")
public class SiteProperties implements SitePolicy {

    /** 群名称 */
    private String groupName = "";

    /** 群号（留空则不在公开站显示） */
    private String groupNumber = "";

    /** 群说明：这个群是干什么的 */
    private String groupDesc = "";

    /** 加群提示：怎么加 */
    private String joinHint = "";

    /** 首页图片轮播 */
    private Carousel carousel = new Carousel();

    @Override
    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    @Override
    public String getGroupNumber() {
        return groupNumber;
    }

    public void setGroupNumber(String groupNumber) {
        this.groupNumber = groupNumber;
    }

    @Override
    public String getGroupDesc() {
        return groupDesc;
    }

    public void setGroupDesc(String groupDesc) {
        this.groupDesc = groupDesc;
    }

    @Override
    public String getJoinHint() {
        return joinHint;
    }

    public void setJoinHint(String joinHint) {
        this.joinHint = joinHint;
    }

    @Override
    public Carousel getCarousel() {
        return carousel;
    }

    public void setCarousel(Carousel carousel) {
        this.carousel = carousel;
    }
}
