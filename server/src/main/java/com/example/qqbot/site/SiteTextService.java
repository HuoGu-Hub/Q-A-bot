package com.example.qqbot.site;

import com.example.qqbot.persistence.SiteTextRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公开站的固定文案。
 *
 * <p><b>为什么要这个东西</b>：公开站上「首页标语」「怎么用三条」「关于页正文」
 * 「页脚版权」「404 文案」这类**固定文本**原先全部硬编码在 .vue 里（清点出 40+ 处），
 * 改一个字都要重新构建前端。这里把它们变成可编辑的内容。
 *
 * <p><b>只存覆盖值，默认值放在代码里</b>（{@link #REGISTRY}）。这样：
 * <ul>
 *   <li>空表 = 全部走默认，站点不会因为数据库没数据而变空白</li>
 *   <li>「恢复默认」就是删掉那一行，不需要把默认值抄进数据库</li>
 *   <li>默认值跟着代码走，改版时不会留下两份互相矛盾的文案</li>
 * </ul>
 *
 * <p><b>刻意不管的</b>：词条详情页、资料库列表这类**数据驱动**的页面没有固定文案；
 * 广场的答案内容来自问答库。这里只管「人写死的那些字」。
 */
@Service
public class SiteTextService {

    private static final Logger log = LoggerFactory.getLogger(SiteTextService.class);

    /** 一个可编辑的文案块 */
    public record Block(String page, String pageLabel, String key, String label,
                        String hint, boolean multiline, String defaultText) {
    }

    /** 后台列表用：块定义 + 当前生效值 + 是否被改过 */
    public record BlockView(String page, String pageLabel, String key, String label,
                            String hint, boolean multiline, String text,
                            String defaultText, boolean overridden) {
    }

    // ==================== 注册表 ====================
    //
    // ⚠️ 这里的 defaultText 必须是**与前端硬编码一致**的当前文案，
    //    否则「没改过」的页面会与新前端显示的内容对不上。
    //    支持**受限的行内标记**：[文字](链接) 与 **加粗**，由前端的 renderInline()
    //    渲染 —— 它先整体转义再替换标记，所以既能让链接可点，又不会开 XSS 口子。
    //    只支持这两种，每多一种就多一处要论证安全性的地方。
    private static final List<Block> REGISTRY = List.of(
            // ---- 全站 ----
            new Block("layout", "全站", "footer_note", "页脚 · 来源声明",
                    "资料版权说明，出现在每页底部", true,
                    "资料来源于 Enshrouded Wiki（CC BY-NC-SA 3.0）· 游戏素材版权归 Keen Games 所有"),
            new Block("layout", "全站", "footer_copy", "页脚 · 版权行",
                    "年份会自动替换 {year}", false,
                    "© {year} 示例助手 · 由示例作者提供技术支持"),

            // ---- 首页 ----
            new Block("home", "首页", "tagline", "副标语",
                    "{kb} 会自动替换成知识库条目数", false,
                    "在《雾锁王国》的迷雾里，问一句就好 —— 我帮你翻遍 {kb} 条资料。"),
            // 首页的「搜索框提示」已随搜索框一起删除（2026-09-27）——
            // 注册表里留着它就等于后台会显示一个页面上根本不存在、改了也没用的块。
            new Block("home", "首页", "howto_title", "小标题", "", false, "怎么用"),
            // 「怎么用」原来是 howto_1/2/3 三块、后台三个输入框；「关于」的正文却是
            // 一块多行、一行一条。同一件事两种编辑方式，改一条要点三个框。
            // 2026-09-27 统一成后者：一块多行，一行一条（公开站按行拆成有序列表）。
            // ⚠️ 老键 howto_1/2/3 已下线；若库里还留着覆盖行，publicOverrides() 会打
            //    WARN 提示清掉（实测生产库里这三块从没被改过，所以没有文案丢失）。
            new Block("home", "首页", "howto_body", "条目", "一行一条，每行渲染成列表的一项", true,
                    "在群里 @ 我提问 —— 群里直接问，我会查资料后回答。\n"
                            + "或者去 [资料库](/library) 翻 —— 和群里回答用的是同一套 Wiki 资料。\n"
                            + "查不到就问点别的 —— 资料来自官方 Wiki，游戏更新后可能有延迟。"),

            // ---- 关于页 ----
            // 「关于」这一组原先的标签是「标题 · 是什么」「正文 · 是什么」——
            // 角色和段落名挤在同一个字符串里，一列看下去全在重复「标题 ·」。
            // 中途试过把段落名挪到 hint（右侧浅色小字），但这类注释小字本身就是噪声
            // （2026-09-28 决定）：标签只留角色，hint 一律留空。
            // 分不清哪块是哪块？看右边预览 —— 它就是这一页的版式。
            new Block("about", "关于", "what_title", "标题", "", false, "示例助手是什么"),
            new Block("about", "关于", "what_body", "正文", "", true,
                    "一个《雾锁王国》（Enshrouded）的问答助手。在 QQ 群里 @ 我提问，"
                            + "我会先在本地资料库里检索，再依据检索到的内容回答 —— 而不是凭空编。"),
            new Block("about", "关于", "source_title", "标题", "", false, "资料从哪来"),
            new Block("about", "关于", "source_body", "正文", "", true,
                    "全部来自 [官方 Enshrouded Wiki](https://enshrouded.wiki.gg/wiki/Main_Page)，采用 "
                            + "[CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/) 许可。"
                            + "游戏内容与素材的版权归 [Keen Games](https://www.keengames.com/) 所有。本站非商业用途。"),
            new Block("about", "关于", "how_title", "标题", "", false, "回答是怎么产生的"),
            new Block("about", "关于", "how_body", "正文", "", true,
                    "把问题变成向量，在本地资料库里找最相关的几段\n同时用中英术语表做一次关键词匹配\n两路结果按排名融合，取前几条\n把资料交给大模型，让它依据资料作答"),
            new Block("about", "关于", "privacy_title", "标题", "", false, "隐私"),
            new Block("about", "关于", "privacy_body", "正文", "", true,
                    "这个公开站**不展示任何群成员的提问记录**。站上能看到的只有游戏资料本身，"
                            + "以及几个不敏感的汇总数字。"),

            // ---- 资料库 ----
            new Block("library", "资料库", "subtitle", "副标题", "", false,
                    "《雾锁王国》Wiki 的中文索引 —— 直接说人话就行，比如「木头怎么弄」「等级上限」。"),
            new Block("library", "资料库", "empty_title", "搜索无结果 · 标题", "", false, "没找到相关资料"),
            new Block("library", "资料库", "empty_hint", "搜索无结果 · 提示", "", true,
                    "换个更具体的说法（比如把「怎么搞木头」说成「木头」），或者到群里直接问机器人"),

            // ---- 问答广场 ----
            new Block("plaza", "问答广场", "intro", "广场说明", "", true,
                    "这里是群友们问过、并且被点赞认可的答案。点关键词查看 —— "
                            + "如果都不满意，可以投票让更好的答案浮现出来。"),

            // ---- 404 ----
            new Block("notfound", "404", "title", "标题", "", false, "迷失在雾里了"),
            new Block("notfound", "404", "desc", "说明", "", false,
                    "这个页面不存在。也许你该回到有火光的地方。"));

    private static final Map<String, Block> BY_KEY = new LinkedHashMap<>();
    /** 页面顺序 = 注册表里第一次出现的顺序，后台二级目录按它排 */
    private static final Map<String, String> PAGE_LABELS = new LinkedHashMap<>();

    static {
        for (Block b : REGISTRY) {
            BY_KEY.put(b.page() + "." + b.key(), b);
            PAGE_LABELS.putIfAbsent(b.page(), b.pageLabel());
        }
    }

    private final SiteTextRepository repo;
    private volatile boolean available;

    public SiteTextService(SiteTextRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    void init() {
        try {
            if (!repo.isAvailable()) {
                log.warn("[SITE] 问答库不可用，页面文案将全部走默认值");
                return;
            }
            repo.initSchema();
            available = true;
            log.info("[SITE] 页面文案就绪：注册 {} 块，已覆盖 {} 块", REGISTRY.size(), overrides().size());
        } catch (Exception e) {
            log.warn("[SITE] 初始化失败（不影响问答，站点走默认文案）：{}", e.getMessage());
            available = false;
        }
    }

    // ==================== 读 ====================

    /** 后台用：按页面分组的全部文案块 */
    public Map<String, List<BlockView>> grouped() {
        Map<String, String> ov = overrides();
        Map<String, List<BlockView>> out = new LinkedHashMap<>();
        for (String page : PAGE_LABELS.keySet()) {
            out.put(page, new ArrayList<>());
        }
        for (Block b : REGISTRY) {
            String cur = ov.get(b.page() + "." + b.key());
            out.get(b.page()).add(new BlockView(
                    b.page(), b.pageLabel(), b.key(), b.label(), b.hint(), b.multiline(),
                    cur != null ? cur : b.defaultText(), b.defaultText(), cur != null));
        }
        return out;
    }

    public Map<String, String> pageLabels() {
        return PAGE_LABELS;
    }

    /**
     * 公开站用：{@code key → 生效文案}，键是 {@code page.block}。
     *
     * <p>只返回**被改过**的块；没改过的由前端用自带的默认文案渲染。
     * 这样接口小、且站点在后端不可用时也不会变空白。
     *
     * <p><b>只发注册表里还有的键</b>：注册表删掉一个块（比如首页搜索框的提示）
     * 之后，数据库里那行覆盖值就成了孤儿 —— 后台看不到它（列表按注册表渲染），
     * 继续发给公开站则是一段谁也读不到的幽灵文案。这里直接不发，并记一条 WARN
     * 让人知道库里有该清的残行。
     */
    public Map<String, String> publicOverrides() {
        Map<String, String> all = overrides();
        Map<String, String> out = new LinkedHashMap<>();
        List<String> orphans = new ArrayList<>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (BY_KEY.containsKey(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            } else {
                orphans.add(e.getKey());
            }
        }
        if (!orphans.isEmpty()) {
            log.warn("[SITE] 库里有 {} 行已下线的文案块（{}）—— 不会发到公开站，"
                    + "要彻底清掉请执行：DELETE FROM site_text WHERE page_key || '.' || block_key IN (...)",
                    orphans.size(), orphans);
        }
        return out;
    }

    private Map<String, String> overrides() {
        Map<String, String> out = new LinkedHashMap<>();
        if (!available) {
            return out;
        }
        try {
            for (SiteTextRepository.Override o : repo.all()) {
                out.put(o.page() + "." + o.key(), o.text());
            }
        } catch (Exception e) {
            log.warn("[SITE] 读取覆盖文案失败：{}", e.getMessage());
        }
        return out;
    }

    // ==================== 写 ====================

    /**
     * 保存一块文案。传空串等于「恢复默认」（删掉覆盖行）。
     *
     * @return false 表示这个 key 不在注册表里
     */
    public boolean save(String page, String key, String text) throws Exception {
        if (!BY_KEY.containsKey(page + "." + key)) {
            return false;
        }
        if (text == null || text.trim().isEmpty()) {
            return reset(page, key);
        }
        repo.save(page, key, text, Instant.now().toString());
        return true;
    }

    /** 恢复默认：删掉覆盖行 */
    public boolean reset(String page, String key) throws Exception {
        if (!BY_KEY.containsKey(page + "." + key)) {
            return false;
        }
        repo.delete(page, key);
        return true;
    }
}
