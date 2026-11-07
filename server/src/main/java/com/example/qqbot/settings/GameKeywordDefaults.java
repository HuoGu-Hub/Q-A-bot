package com.example.qqbot.settings;

import java.util.List;

/**
 * 游戏相关词的**出厂默认值**。
 *
 * <p>它只用于配置项的初始值，不参与判定：判定只读后台保存的三张词表。
 * 这样才能保证输入框里看到什么，运行时就用什么，不会有一份看不见的词表在背后生效。
 */
public final class GameKeywordDefaults {

    private GameKeywordDefaults() {
    }

    /** 强相关：游戏名、资料片名、本群专属地名，4 分 */
    public static final List<String> STRONG = List.of(
            "雾锁王国", "雾锁", "enshrouded", "我的世界", "minecraft",
            "泰拉瑞亚", "terraria", "星露谷物语", "星露谷", "stardew",
            "幻兽帕鲁", "palworld", "艾尔登法环", "elden ring", "黑神话",
            "博德之门", "塞尔达", "原神", "崩坏", "星穹铁道",
            "魔兽世界", "world of warcraft", "最终幻想14", "ff14",
            "英雄联盟", "league of legends", "无畏契约", "valorant",
            "csgo", "cs2", "apex", "永劫无间", "命运方舟",
            "流放之路", "path of exile", "暗黑破坏神", "diablo",
            "饥荒", "don't starve", "英灵神殿", "valheim", "七日杀",
            "7 days to die", "方舟生存", "ark survival", "腐蚀", "rust",
            "星界边境", "starbound", "煤矿");

    /** 通用：游戏行为词，2 分 */
    public static final List<String> GENERIC = List.of(
            "游戏", "开服", "服务器", "联机", "存档", "模组", "mods", "mod",
            "副本", "boss", "npc", "任务", "装备", "武器", "技能", "天赋",
            "职业", "等级", "经验", "掉落", "合成", "配方", "矿洞", "挖矿",
            "矿石", "矿物", "钥匙", "机关", "地图", "坐标", "传送", "建造",
            "基地", "采集", "制作", "刷怪", "组队", "队友", "房主",
            "steam", "epic", "手柄");

    /** 弱相关：单独出现不足以说明是游戏问题，1 分 */
    public static final List<String> WEAK = List.of(
            "卡顿", "帧数", "显卡", "客户端", "版本", "更新", "补丁", "报错",
            "崩溃", "启动", "安装", "开门", "这门", "门怎么", "门在哪",
            "打怪", "怪", "材料", "资源");
}
