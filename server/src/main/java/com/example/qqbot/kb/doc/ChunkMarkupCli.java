package com.example.qqbot.kb.doc;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库文档校验器 + 导入计划预览（命令行）。
 *
 * <p><b>它解决什么</b>：文档由**系统外部**产出（另一个 AI / 手工 / 脚本）。
 * 那些产出者看不到我们的代码，所以必须有一个"拿文件过去一跑，就告诉你
 * 第几行、哪里不合规"的东西 —— 否则错误会一路带到导入，表现为"少了几块"
 * 或"整份导入失败"，而那时已经很难定位了。
 *
 * <p>只依赖 {@link ChunkMarkup} / {@link ChunkImportPlan}，**不启动 Spring、
 * 不碰数据库、不联网**，所以既快又不会影响运行中的服务。
 *
 * <pre>
 * 只校验格式：
 *   ./scripts/validate-kb-doc.sh 文档.md
 *
 * 带上基线，看"这次导入会改动多少条"：
 *   ./scripts/validate-kb-doc.sh --against 现有资料.md 新文档.md
 * </pre>
 *
 * <p>退出码：0 = 可导入；1 = 有致命问题；2 = 用法错误。
 */
public final class ChunkMarkupCli {

    private static final int MAX_SHOWN = 12;

    /**
     * ⚠️ 必须自己包一层 UTF-8。
     *
     * <p>{@code System.out} 用的是**平台默认编码**，容器里通常是 POSIX/ASCII，
     * 于是所有中文报错都会变成 {@code ?} —— 一个"专门用来看哪里不合规"的工具，
     * 报错看不懂就等于没有。
     */
    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private ChunkMarkupCli() {
    }

    public static void main(String[] args) throws IOException {
        List<String> files = new ArrayList<>();
        String against = null;
        for (int i = 0; i < args.length; i++) {
            if ("--against".equals(args[i]) && i + 1 < args.length) {
                against = args[++i];
            } else {
                files.add(args[i]);
            }
        }
        if (files.isEmpty()) {
            OUT.println("用法:");
            OUT.println("  ChunkMarkupCli 文档...                    # 只校验格式");
            OUT.println("  ChunkMarkupCli --against 基线.md 文档...  # 再算「这次会改动多少条」");
            System.exit(2);
            return;
        }

        // 基线 = 系统里现有的资料（同样是一份文档格式的文件）
        Map<String, ChunkMarkup.Block> baseline = null;
        if (against != null) {
            Path bp = Path.of(against);
            if (!Files.isRegularFile(bp)) {
                OUT.println("[找不到基线] " + against);
                System.exit(2);
                return;
            }
            ChunkMarkup.Parsed bp2 = ChunkMarkup.parse(Files.readString(bp, StandardCharsets.UTF_8));
            if (!bp2.ok()) {
                OUT.println("[基线文件本身有致命问题] " + against);
                for (String e : bp2.errors()) {
                    OUT.println("      " + e);
                }
                System.exit(2);
                return;
            }
            baseline = library(bp2.blocks());
            OUT.println("基线：" + against + "（" + baseline.size() + " 个块）");
        }

        int bad = 0;
        for (String a : files) {
            OUT.println("==================================================================");
            Path p = Path.of(a);
            if (!Files.isRegularFile(p)) {
                OUT.println("[找不到] " + a);
                bad++;
                continue;
            }
            ChunkMarkup.Parsed r = ChunkMarkup.parse(Files.readString(p, StandardCharsets.UTF_8));
            report(p.getFileName().toString(), r);
            if (baseline != null && r.ok()) {
                printPlan(baseline, r);
            }
            if (!r.ok()) {
                bad++;
            }
        }
        OUT.println("==================================================================");
        OUT.println(bad == 0
                ? "结论：格式全部通过，可以导入。"
                : "结论：" + bad + " 份文档有致命问题，**不能导入**（修完再跑一次）。");
        OUT.flush();
        System.exit(bad == 0 ? 0 : 1);
    }

    private static Map<String, ChunkMarkup.Block> library(List<ChunkMarkup.Block> blocks) {
        Map<String, ChunkMarkup.Block> out = new LinkedHashMap<>();
        for (ChunkMarkup.Block b : blocks) {
            out.put(b.key(), b);
        }
        return out;
    }

    /** 「这次导入会改动多少条」—— 只比对，不改任何数据 */
    private static void printPlan(Map<String, ChunkMarkup.Block> baseline, ChunkMarkup.Parsed r) {
        ChunkImportPlan<ChunkMarkup.Block> plan = ChunkImportPlan.of(baseline, r.blocks());

        OUT.println("  导入计划：新增 " + plan.added().size()
                + " 条，覆盖 " + plan.updated().size()
                + " 条，无变化 " + plan.unchanged().size()
                + " 条；库里未被触及 " + plan.untouchedExisting() + " 条");

        for (ChunkMarkup.Block b : firstN(plan.added())) {
            OUT.println("      + 新增 [" + b.key() + "] " + b.title());
        }
        for (ChunkMarkup.Block b : firstN(plan.updated())) {
            OUT.println("      ~ 覆盖 [" + b.key() + "] " + b.title());
        }
        for (String w : plan.warnings()) {
            OUT.println("      ! " + w);
        }
    }

    private static void report(String fileName, ChunkMarkup.Parsed r) {
        OUT.println((r.ok() ? "[可导入] " : "[有问题] ") + fileName);

        String url = r.meta("url");
        List<String> tags = ChunkMarkup.splitTags(r.meta("tags"));
        OUT.println("  文档头 : " + (r.header().isEmpty()
                ? "（没有；建议补上 url / tags，否则资料没有出处、也归不了类）"
                : "name=" + r.meta("name")
                + (url.isEmpty() ? "  ⚠缺 url" : "  url=" + url)
                + (tags.isEmpty() ? "  ⚠缺 tags" : "  tags=" + tags)));

        int chars = r.blocks().stream().mapToInt(b -> b.body().length()).sum();
        OUT.println("  分块   : " + r.blocks().size() + " 块，正文共 " + chars + " 字"
                + (r.blocks().isEmpty() ? "" : "（平均 " + (chars / r.blocks().size()) + " 字/块）"));
        long tooLong = r.blocks().stream().filter(b -> b.body().length() > 1600).count();
        if (tooLong > 0) {
            OUT.println("  ! 有 " + tooLong + " 块超过 1600 字 —— 建议拆小，否则检索会变粗");
        }
        if (!r.preamble().isEmpty()) {
            OUT.println("  前言   : " + r.preamble().split("\\n").length + " 行（不属于任何块，不会被当成资料）");
        }

        if (!r.errors().isEmpty()) {
            OUT.println("  ✗ 致命问题 " + r.errors().size() + " 条：");
            for (String e : head(r.errors())) {
                OUT.println("      " + e);
            }
        }
        if (!r.warnings().isEmpty()) {
            OUT.println("  ! 提醒 " + r.warnings().size() + " 条：");
            for (String w : head(r.warnings())) {
                OUT.println("      " + w);
            }
        }

        int n = Math.min(2, r.blocks().size());
        for (int i = 0; i < n; i++) {
            ChunkMarkup.Block b = r.blocks().get(i);
            String preview = b.body().replace('\n', ' ');
            if (preview.length() > 100) {
                preview = preview.substring(0, 100) + "…";
            }
            OUT.println("  · 示例块 [" + (b.id() == null ? "无 id" : b.id()) + "] " + b.title() + "：" + preview);
        }
    }

    private static <T> List<T> firstN(List<T> all) {
        return all.size() <= MAX_SHOWN ? all : all.subList(0, MAX_SHOWN);
    }

    private static List<String> head(List<String> all) {
        return all.size() <= MAX_SHOWN ? all : all.subList(0, MAX_SHOWN);
    }
}
