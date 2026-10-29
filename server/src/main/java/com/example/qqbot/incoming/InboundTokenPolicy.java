package com.example.qqbot.incoming;

/**
 * 入站事件的共享密钥。
 *
 * <p>只声明一个只读值：配置类实现它，过滤器消费它。这样 incoming 不依赖 config。
 */
public interface InboundTokenPolicy {

    /** 与 NapCat「HTTP 客户端 → Token」一致；为空表示未配置，入口必须拒绝 */
    String getInboundToken();
}
