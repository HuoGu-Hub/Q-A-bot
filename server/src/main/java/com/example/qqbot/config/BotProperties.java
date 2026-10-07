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

    // ⚠️ 下面三个默认值是**示例占位**：仓库公开，真实身份不要写回来。
    //    真实值走 .env（APP_BOT_NAME / APP_BOT_ORG / APP_BOT_AUTHOR）或 overrides.yml。

    /** 机器人昵称 —— 用于 {bot} 变量、限流话术等 */
    private String name = "示例助手";

    /** 机器人所属组织 */
    private String org = "示例组织";

    /** 技术支持署名 */
    private String author = "示例作者";

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

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
