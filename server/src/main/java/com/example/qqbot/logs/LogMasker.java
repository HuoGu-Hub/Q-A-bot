package com.example.qqbot.logs;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 日志脱敏。
 *
 * <p><b>为什么需要</b>：日志页可能被截图外发，而日志里会出现
 * token、密钥、QQ 号、带签名的完整 URL。默认全部打码。
 *
 * <p>策略：**保留可识别性，去掉敏感值**。
 * 比如 §Token：已设置（16 位）§ 这类本来就只打了长度，不用动；
 * 但万一某处真打了完整 token，就会被替换成 §abc***xyz§。
 */
@Component
public class LogMasker {

    /** key=value / "key":"value" 形式的密钥 */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)((?:token|password|passwd|secret|api[_-]?key|access[_-]?token|authorization)"
                    + "[\"']?\\s*[:=]\\s*[\"']?)([A-Za-z0-9_\\-+/=.]{6,})");

    /** Bearer xxx */
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(bearer\\s+)([A-Za-z0-9_\\-+/=.]{8,})");

    /** QQ 号（5~11 位连续数字，且前后不是数字/字母）—— 日志里到处是 */
    private static final Pattern QQ = Pattern.compile("(?<![0-9A-Za-z_])\\d{5,11}(?![0-9A-Za-z_])");

    /** 带 rkey/fileid 的完整 URL —— rkey 是签名，泄露等于拿到临时下载权 */
    private static final Pattern SIGNED_URL = Pattern.compile(
            "(https?://[^\\s\"']*?)([?&](?:rkey|fileid|token|sig)=[^\\s\"'&]+)");

    private final LogPolicy props;

    public LogMasker(LogPolicy props) {
        this.props = props;
    }

    /** 脱敏。开关关掉时原样返回 */
    public String mask(String text) {
        if (text == null || !props.isMaskSensitive()) {
            return text;
        }
        String out = text;

        // 1) 密钥：保留前 3 后 3，中间打码
        out = replace(SECRET, out, m -> m.group(1) + abbrev(m.group(2)));
        out = replace(BEARER, out, m -> m.group(1) + abbrev(m.group(2)));

        // 2) 带签名的 URL：砍掉查询串
        out = replace(SIGNED_URL, out, m -> m.group(1) + "?…（签名已隐去）");

        // 3) QQ 号：保留后 4 位
        out = replace(QQ, out, m -> {
            String v = m.group();
            // 时间戳、纯数字 ID 之类也会被匹配到 —— 这是刻意的保守策略：
            // 宁可多打码，也不要漏掉真的 QQ 号
            return v.length() >= 5 ? "***" + v.substring(v.length() - 4) : v;
        });

        return out;
    }

    /** 中段缩写：abcdefghijklmn → abc***lmn */
    static String abbrev(String s) {
        if (s == null || s.length() <= 8) {
            return "***";
        }
        return s.substring(0, 3) + "***" + s.substring(s.length() - 3);
    }

    private interface Replacer {
        String apply(java.util.regex.Matcher m);
    }

    private static String replace(Pattern p, String input, Replacer fn) {
        var m = p.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(fn.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
