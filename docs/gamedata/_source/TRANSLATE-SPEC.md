# 物品图鉴 翻译与产出规范（给执行翻译的 AI）

## 目标
把 `docs/gamedata/_source/<组名>.json` 里的每一件物品翻译成中文，产出知识库文档
`docs/gamedata/雾锁王国-<组名>.md`（组名里已含「物品图鉴-」，所以文件名形如
`雾锁王国-物品图鉴-材料.md`）。

## 一、硬性格式（不符合会被校验器拒绝）

1. 文件第一行必须是 `<!-- doc`，文档头必须以 `-->` 收尾：

```
<!-- doc
name: 物品图鉴·<组名去掉"物品图鉴-">
source: wiki
url: https://enshrouded.wiki.gg/wiki/<对应 wiki 页面或分类页>
tags: 物品, <分类标签>
-->
```

2. 每件物品**一个块**，块之间空一行：

```
=== <中文名>（<English Name>）=== <!-- id: <json 里的 id 字段> -->
<正文>
```

3. **id 直接用 JSON 里的 `id` 字段，绝对不要加 `-0`/`-1` 这类序号。**
   一件物品只允许有一个块——带序号会让人误以为同一件物品有多个生效介绍，造成检索混乱。
4. 标题里要写 `=` 时用 `\=` 转义；正文里不要让某一行以 `=` 开头。

## 二、每块正文的写法

只写 JSON 里**存在且非空**的字段；没有的字段整行省略。

```
<desc 的中文翻译>

类型：<type 中译>｜稀有度：<rarity 中译>｜等级：<level>
舒适度：<comfort>（<comfort_cat 中译>）
护甲：物理抗性 <pResist>｜魔法抗性 <mResist>
武器：等级 <w_level>｜攻速 <w_speed>｜来源 <w_source 中译>
特性：<w_perks 逐项中译，用「、」分隔>
制作：<crafter 中译>｜耗时 <craft_time 中译>｜产出 <craft_qty>
材料：<ingredients_zh 原样照抄>
获取：<obtain 的中文翻译>
```

要点：
- 同一行里只有部分字段有值时，只保留有值的部分。
- `ingredients_zh` 已经是中文，直接照抄，不要改写。
- `craft_time` 常见取值：Instant → 瞬间、其余按字面翻译。
- `comfort` 是形如 `+4` 的数值，照抄。

## 三、翻译要求

- 术语**优先查** `server/data/kb/glossary/zh-en.tsv`（制表符分隔：英文名 TAB 中文名）。
  命中就用它的中文名，保持一致。
- 术语表里没有的自己译。**括注英文的规则**（与黄金样例一致）：
  - 专有名词（地点、敌人、任务、Boss）译文后用括号补英文，例如「拾荒者狂战士（Scavenger Berserker）」；
  - 游戏**特性名只用中文，不加英文括注**，例如「毒素伤害、淬毒之刃、凶暴、精准、原始」。
  - 同一术语在同一份文档里必须译法一致。
- 分隔符统一用全角竖线「｜」，且 `类型` 一行、`武器` 一行、`特性` 一行**分行写**，
  不要写成 `类型：X；等级：Y` 这种一行分号式。
- `desc` 里的 `<br>` 换成换行；尾部混入的模板残渣（如 `| Type = Generic Item`、
  `== Crafting == {{Crafting`）要截掉。
- 形如 `XXX`、`}}`、`X` 的占位脏值视为无值，整项省略。
- `desc` 译成自然通顺的中文，保持原意，**不要增删信息、不要编造**。
- `obtain` 为空就不写「获取」行。
- 不要把 JSON 里的字段名直接留在正文里（不要出现 `desc:`、`w_perks` 这种）。

## 四、交付前必须自查

```bash
cd /app/workspace/qqbot
env -u LANG -u LC_ALL ./scripts/validate-kb-doc.sh docs/gamedata/雾锁王国-<组名>.md
```

**退出码必须是 0**。有致命问题（id 重复、正文为空、文档头没闭合等）必须修到 0 为止。
（注意：直接跑 `./scripts/validate-kb-doc.sh` 在本容器会因为 locale 报错，务必带上
`env -u LANG -u LC_ALL`。）

## 五、参考

- 黄金样例：`docs/gamedata/雾锁王国-物品图鉴-单手剑.md`（38 块，已通过校验）
- 格式规范原文：`docs/md/知识库文档格式规范.md`
