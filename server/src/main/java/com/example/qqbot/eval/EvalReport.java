package com.example.qqbot.eval;

import com.example.qqbot.persistence.EvalRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 批次报告 —— 一个可以直接归档的单文件 HTML。
 *
 * <p>为什么服务端拼 HTML 而不是让前端导出：报告要能脱开这个页面单独存在，
 * 发给没登录后台的人也能看。所以样式内联、不引外部资源、不依赖脚本。
 *
 * <p>P1 是即时生成，报告头部写生成时间与批次 id；不可变快照与 digest 留到
 * 报告中心上线时再做，这里不假装它已经不可变。
 */
@Component
public class EvalReport {

    private final EvalRepository repo;
    private final EvalService service;

    public EvalReport(EvalRepository repo, EvalService service) {
        this.repo = repo;
        this.service = service;
    }

    public String html(long batchId) {
        EvalRepository.BatchRow batch = repo.findBatch(batchId);
        if (batch == null) {
            return null;
        }
        EvalViews.Summary summary = service.summary(batchId);
        List<EvalViews.TagStat> tags = summary.tags();
        List<EvalViews.FailureCase> failures = service.failures(batchId, null, 20);
        List<EvalViews.Item> items = service.batchDetail(batchId, 200).items();

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<title>评测报告 · ").append(esc(batch.name())).append("</title>")
                .append("<style>body{font:15px/1.7 -apple-system,'PingFang SC','Microsoft YaHei',sans-serif;")
                .append("max-width:960px;margin:40px auto;padding:0 24px;color:#1f2328}")
                .append("h1{font-size:24px;border-bottom:2px solid #d8dee4;padding-bottom:8px}")
                .append("h2{font-size:18px;margin-top:28px;border-left:4px solid #2563eb;padding-left:8px}")
                .append("table{width:100%;border-collapse:collapse;font-size:13px;margin:12px 0}")
                .append("th,td{border-bottom:1px solid #e6eaef;padding:6px 8px;text-align:left;vertical-align:top}")
                .append("th{background:#f7f8fa;font-weight:600}code{background:#f4f6f8;padding:1px 4px;border-radius:4px}")
                .append(".kpi{display:flex;gap:18px;flex-wrap:wrap}.kpi div{border:1px solid #e6eaef;border-radius:8px;padding:8px 14px}")
                .append(".kpi b{display:block;font-size:20px}.muted{color:#8b949e;font-size:12px}")
                .append("</style></head><body>");

        sb.append("<h1>评测报告 · ").append(esc(batch.name())).append("</h1>")
                .append("<p class=\"muted\">批次 ").append(batchId)
                .append(" · 生成于 ").append(Instant.now().toString())
                .append(" · 用例 ").append(batch.itemTotal())
                .append(" 条 · 状态 ").append(esc(batch.status()))
                .append(" · 门禁 ").append(esc(batch.gateStatus()))
                .append(batch.gateReason() == null ? "" : "（" + esc(batch.gateReason()) + "）")
                .append("</p>");

        sb.append("<h2>结论</h2><div class=\"kpi\">")
                .append(kpi("通过率", pct(summary.passRate())))
                .append(kpi("已判定", summary.judged()))
                .append(kpi("通过 / 失败", summary.pass() + " / " + summary.fail()))
                .append(kpi("P50 / P95", summary.p50Ms() + " / " + summary.p95Ms() + " ms"))
                .append(kpi("单条成本", summary.pricedRuns() == 0 ? "未计价"
                        : yuan((double) summary.costMicro() / summary.pricedRuns())))
                .append("</div>");

        sb.append("<h2>维度</h2><table><tr><th>维度</th><th>通过</th><th>失败</th><th>跳过</th></tr>");
        for (EvalViews.DimStat d : summary.dimensions()) {
            sb.append("<tr><td>").append(esc(dim(d.dimension()))).append("</td><td>").append(d.pass())
                    .append("</td><td>").append(d.fail()).append("</td><td>").append(d.skip())
                    .append("</td></tr>");
        }
        sb.append("</table>");

        sb.append("<h2>失败归因</h2>");
        if (tags.isEmpty()) {
            sb.append("<p>没有失败标签。</p>");
        } else {
            sb.append("<table><tr><th>标签</th><th>次数</th></tr>");
            for (EvalViews.TagStat t : tags) {
                sb.append("<tr><td><code>").append(esc(t.tag())).append("</code></td><td>")
                        .append(t.count()).append("</td></tr>");
            }
            sb.append("</table>");
            sb.append("<table><tr><th>用例</th><th>问题</th><th>原因</th></tr>");
            for (EvalViews.FailureCase f : failures) {
                sb.append("<tr><td><code>").append(esc(f.caseKey())).append("</code></td><td>")
                        .append(esc(f.question())).append("</td><td>").append(esc(f.reason()))
                        .append("</td></tr>");
            }
            sb.append("</table>");
        }

        sb.append("<h2>用例</h2><table><tr><th>用例</th><th>类别</th><th>问题</th><th>结论</th><th>标签</th></tr>");
        for (EvalViews.Item i : items) {
            sb.append("<tr><td><code>").append(esc(i.caseKey())).append("</code></td><td>")
                    .append(esc(i.category())).append("</td><td>").append(esc(i.question()))
                    .append("</td><td>").append(esc(i.verdict() == null ? i.status() : i.verdict()))
                    .append("</td><td>").append(esc(i.tag() == null ? "" : i.tag())).append("</td></tr>");
        }
        sb.append("</table>");
        sb.append("<p class=\"muted\">本报告为即时生成，数据随批次与判定变化；不可变快照在报告中心上线后提供。</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String kpi(String label, Object value) {
        return "<div><span>" + esc(label) + "</span><b>" + esc(String.valueOf(value)) + "</b></div>";
    }

    private static String pct(double v) {
        return Math.round(v * 1000) / 10.0 + "%";
    }

    private static String yuan(double micro) {
        return String.format("%.4f 元", micro / 1_000_000);
    }

    private static String dim(String d) {
        return switch (d) {
            case "quality" -> "结果质量";
            case "rag" -> "RAG 检索";
            case "stability" -> "稳定性";
            case "cost" -> "成本效率";
            case "safety" -> "安全限制";
            default -> d;
        };
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
