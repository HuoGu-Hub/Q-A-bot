package com.example.qqbot.kb.term;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 词条层接口 —— 「知识库」页的「词条」二级目录用。
 *
 * <p>它把原来两个割裂的页面合成一条链路：{@code 分类 → 词条 → 文本块 → 向量}。
 * 改造前「关键词」和「术语核对」是两个 tab、两套接口（{@code /kb/keywords*} 与
 * {@code /glossary*}），但它们看的是**同一批对象** —— 术语表的英文列有 99.4%
 * 就是页面标题。重复的界面背后是重复的机制，所以这里收成一套。
 *
 * <p>两类写操作在同一个控制器里，但走的是完全不同的路径：
 * <ul>
 *   <li><b>名字</b>（{@code ""} / {@code /batch} / {@code /clear}）→ 改词条表单行，
 *       零成本、立即生效</li>
 *   <li><b>内容</b>（{@code /chunk*} / {@code /retire}）→ 改语料 + 重算向量，
 *       必须维护「chunks.jsonl 行数 == index.bin 的 N」</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/api/kb/terms")
public class KbTermController {

    private static final Logger log = LoggerFactory.getLogger(KbTermController.class);

    /** 一次批量最多改多少条 */
    private static final int BATCH_MAX = 500;

    private final KbTermService service;
    private final KbTermStore store;
    /** 清理孤儿词条时要拿"现在有哪些块"来比 */
    private final com.example.qqbot.kb.block.KbBlockStore blockStore;

    public KbTermController(KbTermService service, KbTermStore store,
                            com.example.qqbot.kb.block.KbBlockStore blockStore) {
        this.service = service;
        this.store = store;
        this.blockStore = blockStore;
    }

    /**
     * 清掉**没有对应块**的词条（孤儿行）—— 见 {@link KbTermStore#deleteOrphans}。
     *
     * <p>body 要带 {@code {"confirm": true}}，和「清空全部」同一套防误触。
     */
    @PostMapping("/clear-orphans")
    public ResponseEntity<?> clearOrphans(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null || !Boolean.TRUE.equals(body.get("confirm"))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "危险操作：请在 body 里带 confirm=true"));
        }
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (com.example.qqbot.kb.block.KbBlock b : blockStore.all()) {
            ids.add(b.id());
        }
        int n = store.deleteOrphans(ids);
        return ResponseEntity.ok(Map.of("ok", true, "deleted", n, "size", store.count()));
    }



    // ==================== 读 ====================

    /**
     * 词条列表 + 全表读数 + 板块规模。
     *
     * @param q     按英文名 / 中文名 / 别名模糊搜
     * @param view  视图预设：main / all / draft / verified / rejected / unnamed / nochunk
     * @param board 只看某个板块（9 个大类之一，或 __hidden__）
     */
    @GetMapping("")
    public ResponseEntity<?> list(@RequestParam(defaultValue = "") String q,
                                  @RequestParam(defaultValue = "") String view,
                                  @RequestParam(defaultValue = "") String board,
                                  @RequestParam(defaultValue = "100") int limit,
                                  @RequestParam(defaultValue = "0") int offset) {
        String v = view == null ? "" : view.trim().toLowerCase(Locale.ROOT);
        if (!v.isEmpty() && !KbTermService.VIEWS.contains(v)) {
            // 静默降级会让人以为自己刚核过的条目又冒出来了 —— 和旧术语表接口同一取舍
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "view 只能是 " + String.join(" / ", KbTermService.VIEWS)));
        }
        int lim = Math.min(Math.max(1, limit), 500);
        return ResponseEntity.ok(service.page(q, v, board, lim, Math.max(0, offset)));
    }

    /**
     * 某个词条下的文本块（按块号升序，与 index.bin 的行号一致）。
     *
     * <p>返回 {@code {title, chunks}} 而不是裸数组：前端要靠 {@code title} 对账
     * "这批块属于抽屉里现在这一条"，否则快速连点两条词条时先到的响应会串台。
     */
    @GetMapping("/chunks")
    public KbTermService.ChunksView chunks(@RequestParam String title) {
        return new KbTermService.ChunksView(title, service.chunks(title));
    }

    // ==================== 写：名字 ====================

    /**
     * 新增或更新一条词条的中文名 / 核对状态。
     *
     * <p>body：{@code {en, zh, status?}}；{@code status} 缺省 {@code verified}
     * （人工在后台补的名字，默认就是"已核对"）。
     *
     * <p>写完**立即生效**，不需要重启机器人 —— 这是闭环能快速迭代的关键：
     * 发现"灵火祭坛"没被认出来 → 后台补一条 → 马上再问一次就有资料了。
     */
    @PostMapping("")
    public ResponseEntity<?> upsert(@RequestBody Map<String, Object> body) {
        String en = asString(body.get("en"));
        String zh = body.get("zh") == null ? "" : asString(body.get("zh"));
        if (en == null || en.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "英文名不能为空"));
        }
        String status = asString(body.get("status"));
        if (status == null) {
            status = zh.isBlank() ? "draft" : "verified";
        }
        if (!KbTermStore.STATUSES.contains(status.toLowerCase(Locale.ROOT))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "status 只能是 " + String.join(" / ", KbTermStore.STATUSES)));
        }
        if (!service.saveName(en, zh, status)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "写入失败（词条存储不可用）"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "size", store.count()));
    }

    /**
     * 批量改核对状态（核对模式的"勾几条一起标"）。
     *
     * <p>body：{@code {"items":["Flame Altar","Scrap Cup"],"status":"verified"}}
     *
     * <p>找不到的英文名单独列在 {@code missing} 里，而不是让整个请求失败 ——
     * 前端一次勾几十条，其中一两条对不上不该连累其余。
     */
    @PostMapping("/batch")
    public ResponseEntity<?> batch(@RequestBody Map<String, Object> body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请求体不能为空"));
        }
        Object raw = body.get("items");
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "items 必须是非空的英文名数组"));
        }
        if (items.size() > BATCH_MAX) {
            return ResponseEntity.badRequest().body(Map.of("error", "一次最多改 " + BATCH_MAX + " 条"));
        }
        String status = asString(body.get("status"));
        String wanted = status == null ? "" : status.toLowerCase(Locale.ROOT);
        if (!KbTermStore.STATUSES.contains(wanted)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "status 只能是 " + String.join(" / ", KbTermStore.STATUSES)));
        }
        // 去重：前端勾选时可能重复传同一条
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (Object o : items) {
            String en = asString(o);
            if (en != null && !en.isBlank()) {
                targets.add(en);
            }
        }
        if (targets.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "items 里没有有效的英文名"));
        }

        KbTermStore.BatchResult r = service.setStatusBatch(new ArrayList<>(targets), wanted);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("updated", r.updated());
        out.put("unchanged", r.unchanged());
        out.put("missing", r.missing());
        out.put("size", store.count());
        // 前端批量核对之后要刷新进度条。增量更新数字容易算错，直接把重算结果给它
        out.put("counts", service.counts());
        return ResponseEntity.ok(out);
    }

    /**
     * 清掉一条的中文名（状态回 draft）。
     *
     * <p>不删行：行代表"这个页面在词条表里有位置"，删了下次启动又会被补回来。
     */
    @PostMapping("/clear")
    public ResponseEntity<?> clear(@RequestBody Map<String, Object> body) {
        String en = asString(body.get("en"));
        if (en == null || en.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 en"));
        }
        if (!service.clearName(en)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "没有这条词条"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "size", store.count()));
    }

    // ==================== 写：文本块 ====================





    /**
     * 清空**全部**词条 —— **不可逆**，删的是纯人工成果。
     *
     * <p>body 必须带 {@code {"confirm": true}}：这个操作没有撤销，
     * 要求调用方显式确认，避免被误触发（比如某个循环里手滑）。
     *
     * <p>删除后**立即生效**：词表按版本号自动重载，不用重启。
     */
    @PostMapping("/clear-all")
    public ResponseEntity<?> clearAll(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null || !Boolean.TRUE.equals(body.get("confirm"))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "危险操作：请在 body 里带 confirm=true"));
        }
        int n = store.deleteAll();
        return ResponseEntity.ok(Map.of("ok", true, "deleted", n, "size", store.count()));
    }

    // ==================== 导入 / 导出（Excel 旁路）====================

    /**
     * 导出 TSV / CSV。
     *
     * <p>返回 JSON 而不是裸文本，因为前端的 {@code adminApi} 一律按 JSON 解析。
     * 前端拿到 {@code tsv} 后自己造 Blob 触发下载。
     *
     * <p>三个筛选参数**与列表接口完全一致**（共用同一份筛选），
     * 所以「列表里看到多少条，导出就是多少条」。
     *
     * @param q     搜索词，可选
     * @param view  见 {@code KbTermService.VIEWS}，可选。例如
     *              {@code ?view=unnamed} 只导「还没有中文名」的那批（2026-10-04 实测 651 条）——
     *              在网页上逐条点不现实，导到 Excel 里填完再用 {@code /import} 导回。
     *              留空 = 导全部（保持老行为）。
     * @param board 板块 key，可选。补译名没有优先级，但**按板块分批**能一次只面对一小撮：
     *              {@code combat / build / material / creature / world / quest / system / guide / other}
     * @param format {@code xlsx}（默认）或 {@code tsv}。想要能直接双击打开的表格就选 xlsx；
     *               {@code tsv} 是**机机 / 调试**用的文本形态（不带 BOM）。
     *               **CSV 导出 2026-10-08 删除**：中文 Windows 上另存 CSV 默认 GBK，
     *               而浏览器按 UTF-8 读 → 中文**静默**变乱码（见 {@link KbTermXlsx}）。
     *               约定：人机交界用 xlsx，机机用 tsv。
     */
    @GetMapping("/export")
    public Map<String, Object> export(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "") String view,
                                      @RequestParam(defaultValue = "") String board,
                                      @RequestParam(defaultValue = "xlsx") String format) {
        String fmt = format.trim().toLowerCase(Locale.ROOT);
        // ⚠️ 明确报错，而不是"悄悄回退成 xlsx" —— 后者会让调用方以为拿到的是 CSV
        if ("csv".equals(fmt)) {
            return Map.of("ok", false, "error", "CSV 导出已弃用（中文 Windows 上 GBK 会静默乱码）："
                    + "请用 format=xlsx（人 / Excel）或 format=tsv（机机）");
        }
        // ⚠️ 数行数前先剥 BOM：CSV 以 U+FEFF 开头，会让**第一行注释**不再以 '#' 开头，
        //    于是它被算成一条数据 → 报给用户的条数比实际多 1（2026-10-04 实测踩到）。
        String tsv = service.exportTsv(q, view, board);
        long count = stripBom(tsv).lines()
                .filter(l -> !l.isBlank() && l.charAt(0) != '#').count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("count", count);
        if ("tsv".equals(fmt)) {
            out.put("tsv", tsv);
            out.put("text", tsv);
            out.put("format", "tsv");
        } else {
            // xlsx 是二进制 —— 用 base64 塞进 JSON，前端照旧走 res.json()（见 client 的注释）
            out.put("xlsxBase64", java.util.Base64.getEncoder()
                    .encodeToString(service.exportXlsx(q, view, board)));
            out.put("format", "xlsx");
        }
        return out;
    }

    /**
     * 从 TSV 导入。
     *
     * <p>只认第 1、2、5 列（英文名 / 中文名 / 状态）；中间两列是导出时算出来的
     * 派生信息，导入时一律忽略 —— 认它们就等于又把派生数据当成了真数据。
     *
     * <p>body：{@code {tsv}}
     */
    @PostMapping("/import")
    public ResponseEntity<?> importTsv(@RequestBody Map<String, Object> body) {
        // 两个字段名都收：csv（Excel 另存为 CSV）与 tsv（我们自己的导出）。
        // 里面到底是逗号还是制表符由 KbTermStore 自己认，这里不猜。
        String tsv = body == null ? null : asString(body.get("csv"));
        if (tsv == null || tsv.isBlank()) {
            tsv = body == null ? null : asString(body.get("tsv"));
        }
        // xlsx：先转成 TSV 文本，**再走下面那条完全相同的导入路径** ——
        // 校验规则（唯一性 / 状态合法 / 已存在不覆盖）一份就够，两份必然漂移
        String b64 = body == null ? null : asString(body.get("xlsxBase64"));
        if ((tsv == null || tsv.isBlank()) && b64 != null && !b64.isBlank()) {
            try {
                tsv = KbTermXlsx.read(java.util.Base64.getDecoder().decode(b64.trim()));
            } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "xlsx 读不出来：" + e.getMessage()));
            }
        }
        if (tsv == null || tsv.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "表内容不能为空（csv 或 tsv）"));
        }
        // BOM：Excel/WPS 存 UTF-8 CSV 时会加，留着会粘进第一个字段（英文名）→ 整条对不上
        tsv = stripBom(tsv);
        // ★ 乱码防护：浏览器读文件固定按 UTF-8 解码，而 WPS/Excel 在中文 Windows 上
        //   另存 CSV **默认是 GBK** → 中文变成替换字符 U+FFFD。
        //   不拦的话这些乱码会**写进词条表、污染检索**，而且全程不报错。
        if (tsv.indexOf('\uFFFD') >= 0) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "文件编码不对：中文变成了乱码（U+FFFD）。"
                    + "在 WPS/Excel 里另存时请选「CSV UTF-8」，或直接用后台导出的 CSV。"));
        }
        KbTermStore.ImportResult r = store.importTsv(tsv);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("created", r.created());
        out.put("updated", r.updated());
        out.put("skipped", r.skipped());
        return ResponseEntity.ok(out);
    }

    /** 去掉 UTF-8 BOM —— 它的存在会破坏「首字符是不是 #」这类判断 */
    private static String stripBom(String s) {
        return s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    // ==================== 工具 ====================

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
