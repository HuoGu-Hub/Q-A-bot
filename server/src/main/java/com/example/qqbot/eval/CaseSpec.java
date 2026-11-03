package com.example.qqbot.eval;

import java.util.List;

/**
 * 一条用例在执行期的快照 —— 与用例表解耦。
 *
 * <p>批次引用的是**冻结版本**：用例后续被改，历史批次仍按当时的快照判定，
 * 否则"同一批次昨天 82%、今天 79%"这种读数根本没法解释。
 *
 * @param expected    可接受的块 id；空表表示"未标注期望"
 * @param expectEmpty true = 期望一条资料都不注入
 */
public record CaseSpec(long caseId, String caseKey, String category, String question,
                       List<String> expected, boolean expectEmpty) {

    public boolean labelled() {
        return expectEmpty || !expected.isEmpty();
    }
}
