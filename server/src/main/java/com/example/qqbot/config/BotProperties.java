package com.example.qqbot.config;

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
 */
@ConfigurationProperties(prefix = "app.bot")
public class BotProperties {

    /** 机器人昵称 —— 用于 {bot} 变量、限流话术等 */
    private String name = "飘雪喵";

    /** 机器人所属组织 */
    private String org = "碧潭飘雪";

    /** 技术支持署名 */
    private String author = "示例作者";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOrg() {
        return org;
    }

    public void setOrg(String org) {
        this.org = org;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }
}
