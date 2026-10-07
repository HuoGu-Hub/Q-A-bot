package com.example.qqbot.config;

import com.example.qqbot.site.BotPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 机器人的身份配置，对应 application.yml 里的 app.bot.*
 *
 * <p>为什么单独抽出来：名字散落在提示词、变量渲染、前端页面好几处，
 * 改一次要动 6 个文件容易漏。抽成配置项后**只改这里**。
 *
 * <p>⚠️ 注意：{@code AGENTS.md} 里的名字是**写死在提示词里的** ——
 * 那是模型人格的一部分，不适合运行时替换（而且提示词是纯文本文件）。
 * 改提示词里的名字需要手动改 AGENTS.md。
 *
 * <p>业务侧只读视图见 {@link BotPolicy} —— 业务包只依赖它，不依赖本类。
 */
@ConfigurationProperties(prefix = "app.bot")
public class BotProperties implements BotPolicy {

    // ⚠️ 默认值是**示例占位**：仓库公开，真实昵称不要写回来。
    //    真实昵称在后台「设置 → 机器人身份」改（热生效、持久化到 overrides.yml），
    //    或首次部署时用 .env 的 APP_BOT_NAME 打底。

    /** 机器人昵称 —— 用于 {bot} 变量、限流话术等 */
    private String name = "示例助手";

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** 运营方 / 组织名 —— 公开站「关于」页展示（留空则不显示那一行） */
    private String org = "示例组织";

    /** 技术支持署名 —— 公开站「关于」页展示（留空则不显示那一行） */
    private String author = "示例作者";

    @Override
    public String getOrg() {
        return org;
    }

    public void setOrg(String org) {
        this.org = org;
    }

    @Override
    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }
}
