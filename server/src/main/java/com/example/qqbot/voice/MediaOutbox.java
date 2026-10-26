package com.example.qqbot.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一次回复的**待发语音清单** —— 工具往里记，路由按它发。
 *
 * <h2>为什么工具不直接发</h2>
 * 工具在模型生成过程中执行。直接发会把顺序钉死成"语音先"，而且模型还在生成，
 * 后面可能还有文字要说。记进清单、等文字生成完再统一发，顺序才能由路由决定。
 *
 * <h2>为什么最多两段</h2>
 * 一条回复配两三段语音已经很不像人了。上限放在这里，模型多调几次也只能记两条。
 *
 * <p>对象随本次请求创建，通过 {@code InvocationParameters} 传给工具；
 * 请求结束就没人引用，不需要清理。
 */
public final class MediaOutbox {

    /** 放进 InvocationParameters 的键 */
    public static final String KEY = "voice.outbox";

    /** 一条回复最多两段语音 */
    public static final int MAX_INTENTS = 2;

    public enum Kind {
        /** 从素材库里取现成的 */
        ASSET,
        /** 现场用 TTS 合成 */
        SPEECH
    }

    /**
     * 一条待发语音。
     *
     * @param assetId 素材 id，{@link Kind#ASSET} 时有意义
     * @param label   给日志看的名字
     * @param text    要说的话，{@link Kind#SPEECH} 时有意义
     */
    public record Intent(Kind kind, long assetId, String label, String text) {

        public static Intent asset(long id, String label) {
            return new Intent(Kind.ASSET, id, label == null ? "" : label, "");
        }

        public static Intent speech(String text) {
            String t = text == null ? "" : text.trim();
            return new Intent(Kind.SPEECH, 0, t.length() > 12 ? t.substring(0, 12) + "…" : t, t);
        }
    }

    private final List<Intent> items = new CopyOnWriteArrayList<>();

    /**
     * 记一条。
     *
     * @return false = 已经到上限，这条被丢弃
     */
    public boolean add(Intent intent) {
        if (intent == null) {
            return false;
        }
        synchronized (items) {
            if (items.size() >= MAX_INTENTS) {
                return false;
            }
            items.add(intent);
            return true;
        }
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    /** 取走全部并清空。路由发完之后调用；重复调用返回空列表 */
    public List<Intent> drain() {
        synchronized (items) {
            List<Intent> copy = new ArrayList<>(items);
            items.clear();
            return copy;
        }
    }
}
