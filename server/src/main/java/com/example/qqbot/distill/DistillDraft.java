package com.example.qqbot.distill;

import java.util.List;

/** 模型返回的一条经验笔记，已通过基础校验 */
public record DistillDraft(String title, String titleNorm, String aliases, String category,
                           String body, String bodyHash, double confidence,
                           List<Long> sourceMessageIds, List<Long> imageIds) {
}
