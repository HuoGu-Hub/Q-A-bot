package com.example.qqbot.config;

import com.example.qqbot.ops.TlsPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 证书监控的配置。
 *
 * <p>默认值指向部署脚本约定的固定路径：Nginx 配置里证书也指向这里，
 * 续期时只替换文件、不动配置。文件不存在时变量输出「未知」，不会报错。
 */
@ConfigurationProperties(prefix = "app.tls")
public class TlsProperties implements TlsPolicy {

    /** PEM 证书文件；留空则关闭监控 */
    private String certFile = "/etc/nginx/certs/qqbot.crt";

    @Override
    public String getCertFile() {
        return certFile;
    }

    public void setCertFile(String certFile) {
        this.certFile = certFile;
    }
}
