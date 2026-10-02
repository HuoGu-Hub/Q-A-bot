package com.example.qqbot.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 关键词表。
 *
 * <p>词表放在**独立文件**里，一行一个词，`#` 开头是注释。
 * 这样增删词不用改代码。
 *
 * <p>支持两种位置：
 * <ul>
 *   <li>`classpath:words/inbound.txt` —— 打包进 jar，改完要重新编译</li>
 *   <li>`file:./words/inbound.txt` —— 外部文件，改完重启即可（推荐给经常调整的场景）</li>
 * </ul>
 */
public class WordList {

    private static final Logger log = LoggerFactory.getLogger(WordList.class);

    private final String source;
    private final List<String> words;

    private WordList(String source, List<String> words) {
        this.source = source;
        this.words = words;
    }

    public static WordList load(ResourceLoader loader, String location) {
        List<String> words = new ArrayList<>();
        try {
            Resource resource = loader.getResource(location);
            if (!resource.exists()) {
                log.warn("[GUARD] 词表文件不存在：{}（该层检测将不生效）", location);
                return new WordList(location, Collections.emptyList());
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String word = line.trim();
                    if (word.isEmpty() || word.startsWith("#")) {
                        continue;
                    }
                    words.add(word.toLowerCase());
                }
            }
            log.info("[GUARD] 已加载词表 {}：{} 个词", location, words.size());
        } catch (Exception e) {
            log.error("[GUARD] 加载词表 {} 失败：{}", location, e.getMessage());
        }
        return new WordList(location, words);
    }

    /**
     * 找出文本里命中的第一个词。
     *
     * @return 命中的词；没有命中返回 empty
     */
    public Optional<String> firstMatch(String text) {
        if (text == null || text.isEmpty() || words.isEmpty()) {
            return Optional.empty();
        }
        String lower = text.toLowerCase();
        for (String word : words) {
            if (lower.contains(word)) {
                return Optional.of(word);
            }
        }
        return Optional.empty();
    }

    public int size() {
        return words.size();
    }

    public String source() {
        return source;
    }
}
