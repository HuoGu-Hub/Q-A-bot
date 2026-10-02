package com.example.qqbot.agent;

import java.util.Locale;

/**
 * 一次回答的「知识来源模式」—— 由指令显式指定，不由模型自己猜。
 *
 * <p><b>为什么要它</b>：默认给模型的是「知识库资料 + 它自己的知识」，而系统提示词
 * 要求"以资料为准"。当知识库过期时，这个要求会把答案带偏。模式让**用户显式声明**
 * "这次别用知识库"，比让分类器自动判断可靠得多。
 *
 * <p>目前两个取值都是**今天就能跑通**的；{@code WEB}（运行时联网）在下一步加，
 * 所以这里刻意先不放 —— 宁可没有，也不要一个配了不生效的选项。
 */
public enum ReplyMode {

    /** 默认：检索知识库，按第九章的资料规则回答 */
    KB,

    /** 不用知识库：不检索、不注入资料块，纯凭模型自身知识回答 */
    NONE;

    /** 从指令配置里的字符串解析（未知值一律回落到 KB） */
    public static ReplyMode from(String raw) {
        if (raw == null) {
            return KB;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "none" -> NONE;
            default -> KB;
        };
    }

    /** 给模型看的一句话说明；KB 是默认行为，不需要提示 */
    public String promptNote() {
        return switch (this) {
            case NONE -> """
                    本次回答模式：**不使用知识库**（管理员用指令显式指定）。
                    这次没有提供任何资料块，请凭你自身的知识回答；
                    不要声称"资料里查到"，必要时说明这没有经过我们的资料库核对。""";
            case KB -> "";
        };
    }
}
