package com.example.qqbot.ops;

/**
 * 证书监控需要的配置 —— 业务侧声明的只读视图，由 {@code config.TlsProperties} 实现。
 *
 * <p>和 {@code AdminPolicy} 同一个套路：ops 包不 import config，
 * 依赖方向是「config → ops」，不是反过来。
 */
public interface TlsPolicy {

    /** PEM 证书文件路径；空串表示不监控 */
    String getCertFile();
}
