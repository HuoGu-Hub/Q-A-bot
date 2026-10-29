package com.example.qqbot.ops;

import com.example.qqbot.time.AppTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 读本机的 TLS 证书，算出还剩几天到期。
 *
 * <p>存在的理由很具体：证书是 90 天的 DV，到期那天整站 HTTPS 会直接不可用，
 * 而且是「某天早上突然打不开」这种最难查的故障。把它变成一个可被定时播报的变量，
 * 比记日历可靠。
 *
 * <p>三个实现约定：
 * <ol>
 *   <li>**只读文件，不联网** —— 证书是本地文件，问 CA 是多余的网络依赖</li>
 *   <li>**缓存 5 分钟** —— 变量会在每条群消息里被求值，不能每次都读盘解析</li>
 *   <li>**读不到不抛异常** —— 返回空，由调用方渲染成「未知」，
 *       不能因为证书文件没了就让整条命令发不出去</li>
 * </ol>
 */
@Component
public class TlsCertificate {

    private static final Logger log = LoggerFactory.getLogger(TlsCertificate.class);

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 证书快照。
     *
     * @param daysLeft 剩余天数；已过期为负数
     */
    public record Info(String subject, Instant notBefore, Instant notAfter,
                       long daysLeft, String notAfterDate) {

        /** 给群消息用的一句话 */
        public String status() {
            if (daysLeft < 0) {
                return "已过期 " + (-daysLeft) + " 天";
            }
            if (daysLeft <= 7) {
                return "⚠️ 仅剩 " + daysLeft + " 天";
            }
            return "正常（还剩 " + daysLeft + " 天）";
        }

        public String daysText() {
            return String.valueOf(daysLeft);
        }
    }

    private final TlsPolicy policy;

    private volatile Optional<Info> cached = Optional.empty();
    private volatile Instant readAt = Instant.EPOCH;
    /** 上一次的失败原因：只在原因变化时打 WARN，避免每 5 分钟刷一行 */
    private volatile String lastError;

    public TlsCertificate(TlsPolicy policy) {
        this.policy = policy;
    }

    public Optional<Info> current() {
        Instant now = Instant.now();
        if (readAt.plus(TTL).isAfter(now)) {
            return cached;
        }
        synchronized (this) {
            if (readAt.plus(TTL).isAfter(now)) {
                return cached;
            }
            cached = read(now);
            readAt = now;
            return cached;
        }
    }

    private Optional<Info> read(Instant now) {
        String file = policy.getCertFile();
        if (file == null || file.isBlank()) {
            warnOnce("没有配置证书路径（app.tls.cert-file）");
            return Optional.empty();
        }
        Path path = Path.of(file);
        if (!Files.isReadable(path)) {
            warnOnce("读不到证书文件：" + file);
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(path)) {
            X509Certificate x = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(in);
            Instant notAfter = x.getNotAfter().toInstant();
            long days = Duration.between(now, notAfter).toDays();
            String date = LocalDateTime.ofInstant(notAfter, AppTime.ZONE).format(DATE);
            lastError = null;
            return Optional.of(new Info(x.getSubjectX500Principal().getName(),
                    x.getNotBefore().toInstant(), notAfter, days, date));
        } catch (Exception e) {
            warnOnce("证书解析失败：" + file + " —— " + e.getMessage());
            return Optional.empty();
        }
    }

    private void warnOnce(String message) {
        if (!message.equals(lastError)) {
            lastError = message;
            log.warn("[TLS] {}", message);
        }
    }
}
