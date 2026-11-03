package com.example.qqbot.eval;

import java.util.Map;

/**
 * 一条判定结论。
 *
 * @param verdict    PASS / FAIL / SKIP
 * @param tag        失败标签，仅 FAIL 时有值
 * @param evidence   判定依据，落库供复核对照
 */
public record Judgement(String dimension, String verdict, double score, String reason,
                        String tag, Map<String, Object> evidence) {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String SKIP = "SKIP";

    public boolean failed() {
        return FAIL.equals(verdict);
    }
}
