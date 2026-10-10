package com.example.qqbot.kb;

/**
 * 向量相似度。
 *
 * <p><b>为什么必须只有一份</b>：A 路（正文向量）与 C 路（标题向量）算的是同一个
 * 坐标系里的余弦，两份实现迟早会漂移 —— 而漂移的表现是"某一路悄悄变得更容易/更难
 * 过阈值"，不报错。所以数学只有这一处。
 */
final class KbVectors {

    private KbVectors() {
    }

    /** 余弦相似度；任一侧为零向量时返回 0（不抛异常，也不返回 NaN） */
    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
