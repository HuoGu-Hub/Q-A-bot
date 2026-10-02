package com.example.qqbot.kb.category;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 知识库的**玩家视角**分类定义 + 自动归类规则。

 * <h2>为什么要重新分类</h2>
 * Wiki 的原始分类是**编辑维护用的**，不是玩家导航用的。实测：
 * <ul>
 *   <li>603 个原始分类，其中 9 个是「版本更新」（如 Early Access Launch 占 669 条）
 *      —— 玩家根本不知道那是什么</li>
 *   <li>594 个内容分类极度碎片（One-handed Swords 38、Bows 35、Doors 35…）</li>
 *   <li>349 个分类只出现 1 次</li>
 * </ul>
 * 所以归结成 7 个玩家能懂的大类。

 * <h2>分类不参与检索</h2>
 * 它只影响公开站的浏览体验 —— 改分类**不会影响问答质量**，可以放心大改。
 */
public final class KbGroups {

    private KbGroups() {
    }

    /**
     * 一个大类。
     *
     * @param key   英文键（存库用）
     * @param label 中文名（给玩家看）
     * @param icon  图标
     * @param desc  一句话说明
     */
    public record Group(String key, String label, String icon, String desc) {
    }

    /** 7 个玩家视角大类 + 兜底 */
    public static final List<Group> ALL = List.of(
            new Group("combat", "战斗装备", "⚔️", "武器、护甲、弹药、饰品"),
            new Group("build", "建造装饰", "🧱", "建材、家具、灯具、染料"),
            new Group("material", "材料消耗", "🧪", "材料、食物、药水"),
            new Group("creature", "敌人生物", "👹", "怪物、野生动物、NPC"),
            new Group("world", "地点探索", "🗺️", "地区、地牢、传送点"),
            new Group("quest", "任务剧情", "📜", "任务、背景故事"),
            new Group("system", "系统机制", "⚙️", "玩法、制作台、技能"),
            new Group("guide", "大佬攻略", "📖", "群友整理的实战心得"),
            new Group("other", "其他", "📦", "还没归类的"));

    /**
     * 中文标签词典。
     *
     * <p>覆盖了高频分类（约 110 条，占全部条目的九成以上）。
     * 没收录的会退回显示英文原名 —— 管理员也可以在后台给单条设置中文名。
     */
    private static final java.util.Map<String, String> LABELS = java.util.Map.ofEntries(
            java.util.Map.entry("Crafting Materials", "制作材料"),
            java.util.Map.entry("Consumables", "消耗品"),
            java.util.Map.entry("Cosmetic Armor", "时装护甲"),
            java.util.Map.entry("Melee Armor", "近战护甲"),
            java.util.Map.entry("Ranged Armor", "远程护甲"),
            java.util.Map.entry("Magic Armor", "法师护甲"),
            java.util.Map.entry("Miscellaneous", "杂项"),
            java.util.Map.entry("Illumination", "照明"),
            java.util.Map.entry("Fences", "栅栏"),
            java.util.Map.entry("Armor Set", "护甲套装"),
            java.util.Map.entry("Dyes", "染料"),
            java.util.Map.entry("Seedlings", "幼苗"),
            java.util.Map.entry("Blocks", "建材"),
            java.util.Map.entry("Terrain Materials", "地形材料"),
            java.util.Map.entry("Wildlife", "野生动物"),
            java.util.Map.entry("Craftspeople", "工匠"),
            java.util.Map.entry("Tableware", "餐具"),
            java.util.Map.entry("One-handed Swords", "单手剑"),
            java.util.Map.entry("Bows", "弓"),
            java.util.Map.entry("Tables", "桌子"),
            java.util.Map.entry("Doors", "门"),
            java.util.Map.entry("Windows", "窗户"),
            java.util.Map.entry("Production Places", "制作台"),
            java.util.Map.entry("Daggers", "匕首"),
            java.util.Map.entry("Rings", "戒指"),
            java.util.Map.entry("Staff Charges", "法杖充能"),
            java.util.Map.entry("Wands", "魔杖"),
            java.util.Map.entry("One-handed Axes", "单手斧"),
            java.util.Map.entry("enemies", "敌人"),
            java.util.Map.entry("enemy", "敌人"),
            java.util.Map.entry("Staves", "法杖"),
            java.util.Map.entry("Fell", "荒原"),
            java.util.Map.entry("Arrows", "箭"),
            java.util.Map.entry("Clutter", "杂物"),
            java.util.Map.entry("One-handed Clubs", "单手棍"),
            java.util.Map.entry("Shields", "盾牌"),
            java.util.Map.entry("Gems", "宝石"),
            java.util.Map.entry("Cosmetic Armor Set", "时装套装"),
            java.util.Map.entry("Fireplaces", "壁炉"),
            java.util.Map.entry("Statues", "雕像"),
            java.util.Map.entry("Shelves", "置物架"),
            java.util.Map.entry("Festive", "节庆装饰"),
            java.util.Map.entry("Medium Flower Pots", "中型花盆"),
            java.util.Map.entry("Chairs", "椅子"),
            java.util.Map.entry("Banners", "旗帜"),
            java.util.Map.entry("Gameplay", "玩法"),
            java.util.Map.entry("Two-handed Hammers", "双手锤"),
            java.util.Map.entry("Cemetery", "墓地"),
            java.util.Map.entry("Animal Resources", "动物材料"),
            java.util.Map.entry("Essentials", "基础物资"),
            java.util.Map.entry("Tools and Components", "工具与部件"),
            java.util.Map.entry("Fossils", "化石"),
            java.util.Map.entry("Locations", "地点"),
            java.util.Map.entry("Bathroom", "卫浴"),
            java.util.Map.entry("Secret Doors", "暗门"),
            java.util.Map.entry("Storage", "储物"),
            java.util.Map.entry("Flame Mosaic", "火焰马赛克"),
            java.util.Map.entry("Weapon Mounts", "武器架"),
            java.util.Map.entry("Musical Instruments", "乐器"),
            java.util.Map.entry("Water Utilities", "水利设施"),
            java.util.Map.entry("Two-handed Axes", "双手斧"),
            java.util.Map.entry("Biomes", "生物群系"),
            java.util.Map.entry("Miscellaneous Armor", "其他护甲"),
            java.util.Map.entry("Sets", "套装"),
            java.util.Map.entry("Dividers", "隔断"),
            java.util.Map.entry("Animal Furniture", "动物家具"),
            java.util.Map.entry("Roof Blocks", "屋顶"),
            java.util.Map.entry("Bosses", "Boss"),
            java.util.Map.entry("Benches", "长凳"),
            java.util.Map.entry("Villagers", "村民"),
            java.util.Map.entry("Beds", "床"),
            java.util.Map.entry("Cupboards", "橱柜"),
            java.util.Map.entry("Building Scaffolds", "脚手架"),
            java.util.Map.entry("Small Flower Pots", "小型花盆"),
            java.util.Map.entry("Large Plant Pots", "大型花盆"),
            java.util.Map.entry("Throwing Weapons", "投掷武器"),
            java.util.Map.entry("Showcases", "展示柜"),
            java.util.Map.entry("Artifacts", "神器"),
            java.util.Map.entry("Scavenger", "拾荒者"),
            java.util.Map.entry("Assistants", "助手"),
            java.util.Map.entry("Two-handed Greatswords", "双手大剑"),
            java.util.Map.entry("Books", "书籍"),
            java.util.Map.entry("Trophies", "战利品"),
            java.util.Map.entry("Long Flower Pots", "长条花盆"),
            java.util.Map.entry("Felling Axes", "伐木斧"),
            java.util.Map.entry("Carpets", "地毯"),
            java.util.Map.entry("Pickaxes", "镐"),
            java.util.Map.entry("Two-handed Clubs", "双手棍"),
            java.util.Map.entry("Cyclops Statue", "独眼巨人雕像"),
            java.util.Map.entry("Drak Relief", "龙形浮雕"),
            java.util.Map.entry("Plant Pots", "花盆"),
            java.util.Map.entry("Quests", "任务"),
            java.util.Map.entry("Furniture", "家具"),
            java.util.Map.entry("Lunar New Year Flower Pots", "春节花盆"),
            java.util.Map.entry("Hollow", "空洞"),
            java.util.Map.entry("Dungeons", "地牢"),
            java.util.Map.entry("Overgrowth Materials", "蔓生材料"),
            java.util.Map.entry("Dragons' Map", "龙之地图"),
            java.util.Map.entry("Hollow's Eve", "空洞之夜"),
            java.util.Map.entry("Hidden Flame Shrine", "隐秘火焰神殿"),
            java.util.Map.entry("The Blacksmith's Request", "铁匠的请求"),
            java.util.Map.entry("Slaying The Corrupted Beast", "讨伐腐化野兽"),
            java.util.Map.entry("Alchemist", "炼金术士"),
            java.util.Map.entry("Blacksmith", "铁匠"),
            java.util.Map.entry("Hunter", "猎人"),
            java.util.Map.entry("Carpenter", "木匠"),
            java.util.Map.entry("Farmer", "农夫"),
            java.util.Map.entry("Collector", "收藏家"),
            java.util.Map.entry("Weapons", "武器"),
            java.util.Map.entry("Armor", "护甲"),
            java.util.Map.entry("Tools", "工具"),
            java.util.Map.entry("Food", "食物"),
            java.util.Map.entry("Potions", "药水"),
            java.util.Map.entry("Resources", "资源"),
            java.util.Map.entry("Plants", "植物"),
            java.util.Map.entry("Creatures", "生物"),
            // ── 长尾补录（2026-09-26）──
            // 下面这 22 条是实测出来的「会露给玩家的英文标签」：它们在 7 个大类里、
            // 又没进词典，公开站就退回显示英文原名。来源：chunks.jsonl 全表统计，
            // 判据是「所属大类会被展示 且 labelZh == null 且条目数 >= 2」。
            // 条目数只有 1 的（约 115 条，多为单页任务/信件）不再逐条翻译 ——
            // 公开站已改成「查不到中文名就不展示」（见 PublicController.topTags），
            // 剩下的交给后台「分类管理」按需补。
            java.util.Map.entry("Lore Weapons", "传说武器"),
            java.util.Map.entry("Building Tools", "建造工具"),
            java.util.Map.entry("Torches", "火把"),
            java.util.Map.entry("Finding And Using A Gem Forge", "宝石熔炉的使用"),
            java.util.Map.entry("Mysterious Wanderer Sightings", "神秘流浪者目击"),
            java.util.Map.entry("The Twinflame Swords", "双生火焰剑"),
            java.util.Map.entry("Writable Signs", "可书写标牌"),
            java.util.Map.entry("Basic Decoration", "基础装饰"),
            java.util.Map.entry("A Destroyed Roof", "被毁的屋顶"),
            java.util.Map.entry("Alchemist's Wall Hanging", "炼金术士挂饰"),
            java.util.Map.entry("Wall Tapestry", "挂毯"),
            java.util.Map.entry("Forever Waiting", "永恒的等待"),
            java.util.Map.entry("Seed Of Suspicion", "猜疑的种子"),
            java.util.Map.entry("Almanac of Plants and Seedlings For The Farmer", "农夫的植物图鉴"),
            java.util.Map.entry("Animal Farming", "动物养殖"),
            java.util.Map.entry("Enemies", "敌人"),
            java.util.Map.entry("Hidden Crafting Recipes - Hollow Halls", "隐藏配方（空洞大厅）"),
            java.util.Map.entry("Enshrouded Shipment", "迷雾货物"),
            java.util.Map.entry("Lost In The Shroud", "迷失于迷雾"),
            java.util.Map.entry("Rumors Of The Dragon Cave", "龙穴传闻"),
            java.util.Map.entry("Diving Mission", "潜水任务"),
            java.util.Map.entry("Scouting Mission To The Obelisk", "方尖碑侦察任务"),
            java.util.Map.entry("A Test Of Skill", "技巧的考验")
    );

    /**
     * 元页面：不是游戏内容，是 Wiki 的编辑/模板/分类页。
     * 这些不该出现在资料库里 —— 玩家点进去只会一脸茫然。
     */
    private static final java.util.Set<String> META_PAGES = java.util.Set.of(
            "Enshrouded Wiki", "editing guides", "Main page boxes", "Help",
            "Datamined Content", "Item catalogs", "Collections",
            // 实测发现它混进了「地点探索」大类：这是 Wiki 的服务器托管商广告页，
            // 玩家点进去看到的是售卖广告，属于元页面
            "Enshrouded Server Hosting Providers");

    /** 取中文标签；没有收录的返回 null（调用方决定退回英文还是隐藏） */
    public static String labelZh(String raw) {
        if (raw == null) {
            return null;
        }
        return LABELS.get(raw.trim());
    }

    /** 是否是 Wiki 元页面（应隐藏） */
    public static boolean isMetaPage(String raw) {
        return raw != null && META_PAGES.contains(raw.trim());
    }
    /** 群友贡献内容的固定分类标签 */
    public static final String CONTRIB_TAG = "大佬攻略";

    /** 默认大类（没匹配到任何规则时） */
    /**
     * 精确匹配的分类归属。
     *
     * <p><b>为什么需要它</b>：{@link #autoGroup} 是子串启发式，对下面这些名字天然失效 ——
     * 复数和专名不含关键词（{@code Shelves} 不含 {@code shelf}），或者关键词压根不在词表里
     * （{@code Two-handed Hammers} 的 {@code hammer} 从来没进过武器词表）。
     * 实测这 28 个分类原本**全被丢进「其他」**，合计 463 条 —— 占该桶的 47%。
     *
     * <p><b>为什么用精确表而不是继续加子串</b>：子串会误伤。例如 {@code statue}
     * 同时命中家具「雕像」（该进建造装饰）和地标「独眼巨人雕像」（该进地点探索），
     * 一个子串规则没法同时满足两者。精确表只影响列在这里的名字，改动可审计。
     *
     * <p>清单来源：把 604 个 Wiki 原始分类逐个过 autoGroup + LABELS，
     * 挑出「词典里已经有中文名、但规则没归类」的那些。
     */
    private static final Map<String, String> EXACT_GROUPS = Map.ofEntries(
            // ── 建造装饰：家具与装饰（子串规则漏掉的复数/专名）──
            Map.entry("Statues", "build"),
            Map.entry("Shelves", "build"),
            Map.entry("Festive", "build"),
            Map.entry("Banners", "build"),
            Map.entry("Bathroom", "build"),
            Map.entry("Storage", "build"),
            Map.entry("Dividers", "build"),
            Map.entry("Benches", "build"),
            Map.entry("Cupboards", "build"),
            Map.entry("Showcases", "build"),
            Map.entry("Flame Mosaic", "build"),
            Map.entry("Musical Instruments", "build"),
            Map.entry("Water Utilities", "build"),
            Map.entry("Drak Relief", "build"),
            Map.entry("Medium Flower Pots", "build"),
            Map.entry("Small Flower Pots", "build"),
            Map.entry("Long Flower Pots", "build"),
            Map.entry("Lunar New Year Flower Pots", "build"),
            // ── 战斗装备 ──
            Map.entry("Two-handed Hammers", "combat"),
            Map.entry("Sets", "combat"),
            // ── 地点探索：地标 ──
            Map.entry("Cemetery", "world"),
            Map.entry("Cyclops Statue", "world"),
            // ── 材料消耗：可拾取物品 ──
            Map.entry("Fossils", "material"),
            Map.entry("Artifacts", "material"),
            Map.entry("Trophies", "material"),
            Map.entry("Essentials", "material"),
            // ── 敌人生物：NPC 助手 ──
            Map.entry("Assistants", "creature"),
            // ── 任务剧情：书籍属于世界观读物 ──
            Map.entry("Books", "quest"));

    public static final String DEFAULT_GROUP = "other";

    /** 特殊值：标记为隐藏（不展示给玩家） */
    public static final String HIDDEN = "__hidden__";

    /**
     * 中文标签 → 大类。
     *
     * <p><b>为什么必须单独一张表</b>：上面那套规则是给**旧 Wiki 的英文分类**写的
     * （604 个英文 category，如 {@code Cosmetic Armor}、{@code Crafting Materials}）。
     * 2026-09-29 改成"文档导入"之后，标签由文档头 {@code tags:} 给出，而语料是中文的 ——
     * 于是**所有中文标签一个规则都匹配不上，整库落进「其他」**。这张表就是补这个缺口。
     *
     * <p>清单来源：把库里实际出现过的 78 个中文标签逐个归类（2026-10-01）。
     * 管理人员仍然可以在「分类」面板里手动覆盖任何一条。
     */
    private static final Map<String, String> EXACT_GROUPS_ZH = Map.ofEntries(
            // 「物品」是"这是个物品"的内容类型标记：97% 的块都有它，没有导航价值 → 隐藏。
            // 实测**没有任何块**只带这一个标签，所以隐藏它不会让任何块从浏览里消失。
            Map.entry("物品", HIDDEN),
            // ── 版本更新类标记：隐藏（同英文那条规则）──
            Map.entry("版本更新", HIDDEN),
            Map.entry("水之觉醒更新", HIDDEN),
            Map.entry("铸路更新", HIDDEN),
            Map.entry("水之觉醒", HIDDEN),
            Map.entry("Bug修复", HIDDEN),
            Map.entry("补丁说明", HIDDEN),
            Map.entry("热修", HIDDEN),
            // ── 战斗装备 ──
            Map.entry("武器", "combat"),
            Map.entry("护甲", "combat"),
            Map.entry("法师护甲", "combat"),
            Map.entry("杂项护甲", "combat"),
            Map.entry("头部", "combat"),
            Map.entry("足部", "combat"),
            Map.entry("战士", "combat"),
            Map.entry("游侠", "combat"),
            Map.entry("匕首", "combat"),
            Map.entry("单手武器", "combat"),
            Map.entry("双手武器", "combat"),
            Map.entry("单手剑", "combat"),
            Map.entry("远程武器", "combat"),
            Map.entry("法杖", "combat"),
            Map.entry("魔杖", "combat"),
            Map.entry("法术", "combat"),
            Map.entry("盾牌", "combat"),
            Map.entry("戒指", "combat"),
            Map.entry("饰品", "combat"),
            Map.entry("战利品", "combat"),
            Map.entry("滑翔翼", "combat"),
            Map.entry("装备升级", "combat"),
            Map.entry("战斗", "combat"),
            // ── 建造装饰 ──
            Map.entry("装饰", "build"),
            Map.entry("家具", "build"),
            Map.entry("建筑", "build"),
            Map.entry("建造装饰", "build"),
            Map.entry("建造", "build"),
            Map.entry("门", "build"),
            Map.entry("围栏", "build"),
            Map.entry("窗", "build"),
            Map.entry("方块", "build"),
            Map.entry("结构", "build"),
            Map.entry("地形", "build"),
            Map.entry("陈列", "build"),
            Map.entry("收纳", "build"),
            Map.entry("餐具", "build"),
            Map.entry("外观", "build"),
            Map.entry("旗帜", "build"),
            Map.entry("节庆", "build"),
            Map.entry("染料", "build"),
            Map.entry("照明", "build"),
            Map.entry("舒适度", "build"),
            Map.entry("花盆", "build"),
            Map.entry("水管", "build"),
            // ── 材料消耗 ──
            Map.entry("材料", "material"),
            Map.entry("制作材料", "material"),
            Map.entry("消耗品", "material"),
            Map.entry("食物", "material"),
            Map.entry("药水", "material"),
            Map.entry("宝石", "material"),
            Map.entry("鱼类", "material"),
            Map.entry("工具", "material"),
            Map.entry("收集品", "material"),
            Map.entry("种植", "material"),
            Map.entry("幼苗", "material"),
            Map.entry("植物", "material"),
            Map.entry("植被", "material"),
            Map.entry("化石", "material"),
            // ── 敌人生物 ──
            Map.entry("动物", "creature"),
            Map.entry("牧场", "creature"),
            // ── 地点探索 ──
            Map.entry("雾水盆地", "world"),
            Map.entry("生态区域", "world"),
            // ── 系统机制 ──
            Map.entry("技能树", "system"),
            Map.entry("玩法机制", "system"),
            Map.entry("生产设施", "system"),
            Map.entry("钓鱼", "system"),
            Map.entry("水体", "system"),
            // ── 大佬攻略 / 其他 ──
            Map.entry("攻略", "guide"),
            Map.entry("其他", DEFAULT_GROUP));

    public static Group get(String key) {
        for (Group g : ALL) {
            if (g.key().equals(key)) {
                return g;
            }
        }
        return ALL.get(ALL.size() - 1);
    }

    /**
     * 自动归类规则。
     *
     * <p><b>顺序敏感</b>：从上往下匹配，第一个命中就返回。
     * 所以更具体的规则（如 cosmetic armor）要放在更宽的规则（armor）前面。
     *
     * <p>只做「打底」—— 管理员可以在后台手动覆盖任何一条。
     */
    public static String autoGroup(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_GROUP;
        }
        // 贡献内容固定带这个标签，直接归到「大佬攻略」
        if (CONTRIB_TAG.equals(raw.trim())) {
            return "guide";
        }
        if (isMetaPage(raw)) {
            return HIDDEN;
        }
        // 精确表优先：这些名字子串规则抓不到（复数/专名），见 EXACT_GROUPS 的注释
        String exact = EXACT_GROUPS.get(raw.trim());
        if (exact != null) {
            return exact;
        }
        // 中文标签（文档导入之后的主要形态）走单独一张表 —— 见 EXACT_GROUPS_ZH 的注释
        String exactZh = EXACT_GROUPS_ZH.get(raw.trim());
        if (exactZh != null) {
            return exactZh;
        }

        String s = raw.toLowerCase(Locale.ROOT).trim();

        // ── 版本更新类：隐藏（对玩家无导航价值）──
        if (containsAny(s, "update", "early access", "launch", "newsletter",
                "patch", "hotfix", "release notes", "roadmap", "version history",
                "forging the path", "game/")) {
            return HIDDEN;
        }
        // 中文的版本更新类（表里没列到的新标签也兜住）
        if (containsAny(s, "更新", "补丁", "热修", "bug")) {
            return HIDDEN;
        }

        // ── 系统机制 ──
        if (containsAny(s, "production place", "crafting station", "workbench",
                "mechanic", "gameplay", "skill", "perk", "upgrade", "progression",
                "controls", "settings", "interface", "system", "tutorial", "guide")) {
            return "system";
        }

        // ── 战斗装备 ──
        if (containsAny(s, "armor", "weapon", "sword", "bow", "dagger", "axe", "club",
                "staff", "staves", "wand", "shield", "arrow", "ammo", "ring", "gem",
                "melee", "ranged", "magic", "helmet", "glove", "boot", "cape",
                "trinket", "accessor", "tool", "pickaxe", "torch", "spell", "scroll")) {
            return "combat";
        }

        // ── 建造装饰 ──
        if (containsAny(s, "block", "fence", "door", "window", "table", "chair", "bed",
                "illumination", "dye", "clutter", "fireplace", "terrain", "building",
                "furniture", "decorat", "cosmetic", "roof", "floor", "wall", "stair",
                "pillar", "beam", "rug", "painting", "sign", "candle", "lamp", "shelf")) {
            return "build";
        }

        // ── 材料消耗 ──
        if (containsAny(s, "material", "consumable", "ingredient", "seedling", "seed",
                "food", "potion", "elixir", "plant", "herb", "ore", "wood", "fabric",
                "leather", "metal", "resin", "extract", "shard", "dust", "essence")) {
            return "material";
        }

        // ── 敌人生物 ──
        if (containsAny(s, "enemy", "enemies", "wildlife", "creature", "animal", "npc",
                "craftspeople", "thrall", "boss", "monster", "beast", "fell creature",
                "mount", "pet", "villager")) {
            return "creature";
        }

        // ── 地点探索 ──
        if (containsAny(s, "biome", "location", "region", "place", "dungeon", "hollow",
                "point of interest", "landmark", "cave", "ruin", "shrine", "altar",
                "spire", "fell", "shroud", "map", "world")) {
            return "world";
        }

        // ── 任务剧情 ──
        if (containsAny(s, "quest", "story", "lore", "mission", "objective", "dialogue",
                "journal", "character", "background")) {
            return "quest";
        }

        // ── 中文标签的子串规则：表里没列到的**新标签**也能落到大致对的地方 ──
        if (containsAny(s, "武器", "护甲", "剑", "斧", "弓", "盾", "法杖", "魔杖",
                "戒指", "饰品", "装备")) {
            return "combat";
        }
        if (containsAny(s, "家具", "装饰", "建筑", "门", "窗", "染料", "照明",
                "方块", "旗帜", "结构", "地形", "收纳", "陈列")) {
            return "build";
        }
        if (containsAny(s, "材料", "食物", "药水", "消耗", "种子", "幼苗", "矿石",
                "宝石", "植物", "鱼类", "工具")) {
            return "material";
        }
        if (containsAny(s, "动物", "生物", "怪物", "敌人", "牧场")) {
            return "creature";
        }
        if (containsAny(s, "地点", "区域", "盆地", "地牢", "生态")) {
            return "world";
        }
        if (containsAny(s, "任务", "剧情", "故事")) {
            return "quest";
        }
        if (containsAny(s, "技能", "玩法", "系统", "机制", "设施", "钓鱼")) {
            return "system";
        }
        if (containsAny(s, "攻略", "心得")) {
            return "guide";
        }

        return DEFAULT_GROUP;
    }

    private static boolean containsAny(String s, String... words) {
        for (String w : words) {
            if (s.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
