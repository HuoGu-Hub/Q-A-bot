package com.example.qqbot.admin;

import com.example.qqbot.onebot.outbound.OutboundSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 后台的 QQ 私聊验证码。
 *
 * <p>不依赖邮箱/SMTP。验证码只通过 OneBot 私聊发到管理员绑定的 QQ；
 * 只存哈希，5 分钟过期，一个 challenge 只能用一次。
 */
@Service
public class AdminQqCodeService {

    private static final Logger log = LoggerFactory.getLogger(AdminQqCodeService.class);

    public static final String PURPOSE_LOGIN = "login";
    public static final String PURPOSE_BIND = "bind";
    public static final String PURPOSE_CREATE_USER = "create-user";

    private static final Pattern QQ = Pattern.compile("^[1-9]\\d{4,11}$");
    private static final long TTL_MS = 5 * 60 * 1000L;
    private static final long COOLDOWN_MS = 60 * 1000L;
    private static final int MAX_PER_HOUR = 5;
    private static final int MAX_ATTEMPTS = 5;

    private final AdminQqCodeRepository codes;
    private final OutboundSender outbound;
    private final SecureRandom random = new SecureRandom();

    public AdminQqCodeService(AdminQqCodeRepository codes, OutboundSender outbound) {
        this.codes = codes;
        this.outbound = outbound;
    }

    public record Issued(String challengeId, String qqMasked) {
    }

    public record Consumed(long userId, String qq, String payload) {
    }

    public static class CodeException extends RuntimeException {
        public CodeException(String message) {
            super(message);
        }
    }

    /**
     * 生成并发送一条验证码。
     *
     * @param payload 仅用于“创建账号”这类需要把待创建数据暂存的场景，可为 null
     */
    public Issued issue(long userId, String qq, String purpose, String payload) {
        String target = qq == null ? "" : qq.trim();
        if (!QQ.matcher(target).matches()) {
            throw new CodeException("QQ 号格式不对");
        }
        long now = System.currentTimeMillis();
        if (codes.countSince(userId, purpose, now - 60 * 60 * 1000L) >= MAX_PER_HOUR) {
            throw new CodeException("验证码发得太频繁，请稍后再试");
        }
        long last = codes.latestCreatedAt(userId, purpose);
        if (last > 0 && now - last < COOLDOWN_MS) {
            throw new CodeException("刚发过验证码，请 " + ((COOLDOWN_MS - (now - last)) / 1000 + 1) + " 秒后再试");
        }

        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        String challengeId = UUID.randomUUID().toString().replace("-", "");
        long id = codes.create(userId, target, purpose, challengeId,
                AdminPasswords.hash(code), now, now + TTL_MS, payload);

        String text = "qqbot 验证码：" + code + "，5 分钟内有效。\n"
                + "不是你本人操作请忽略，不要把这个码发给任何人。";
        try {
            outbound.sendToPrivate(Long.parseLong(target), text);
        } catch (RuntimeException e) {
            codes.delete(id);
            log.warn("[ADMIN] QQ 验证码发送失败：userId={} qq={}", userId, mask(target));
            throw new CodeException("QQ 验证码发送失败，请确认已添加 bot 好友");
        }
        log.info("[ADMIN] 已发送 QQ 验证码：userId={} qq={} purpose={}", userId, mask(target), purpose);
        return new Issued(challengeId, mask(target));
    }

    /** 校验并消费一个 challenge；失败抛 CodeException */
    public Consumed consume(String challengeId, String code, String purpose) {
        AdminQqCodeRepository.Row row = codes.find(challengeId);
        if (row == null || row.used() || !purpose.equals(row.purpose())) {
            throw new CodeException("验证码无效或已过期");
        }
        long now = System.currentTimeMillis();
        if (row.expiresAt() < now) {
            throw new CodeException("验证码已过期，请重新获取");
        }
        if (row.attempts() >= MAX_ATTEMPTS) {
            throw new CodeException("验证码错误次数过多，请重新获取");
        }
        codes.incrementAttempts(row.id());
        if (code == null || !AdminPasswords.verify(code.trim(), row.codeHash())) {
            if (row.attempts() + 1 >= MAX_ATTEMPTS) {
                codes.markUsed(row.id());
            }
            throw new CodeException("验证码不对");
        }
        codes.markUsed(row.id());
        return new Consumed(row.userId(), row.qq(), row.payload());
    }

    /** 注册前的欢迎消息 / 注册后的通知；发不出去就中止注册 */
    public void sendNotice(String qq, String text) {
        try {
            outbound.sendToPrivate(Long.parseLong(qq), text);
        } catch (RuntimeException e) {
            throw new CodeException("QQ 消息发送失败，请确认对方已添加 bot 好友");
        }
    }

    static String mask(String qq) {
        if (qq == null || qq.length() < 5) {
            return "***";
        }
        return qq.substring(0, 3) + "***" + qq.substring(qq.length() - 2);
    }
}
