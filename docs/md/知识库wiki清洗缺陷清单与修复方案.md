# wiki 语料清洗：缺陷清单与修复方案

> **审计日期**：2026-10-06　**对象**：`WikiApiClient` → `WikitextCleaner` → `KbTextChunker` → `WikiArticleImporter` 这条"从 wiki 抓攻略进语料"的链路。
> **范围**：只记**缺陷与改法**，本文不改代码。
> **结论先看**：枚举**没漏**（线上 637 页 = 库里 637 页，逐来源对齐）、0 个失败页、语料与当前清洗器**一致**。
> 下面 6 条是"现在没炸、但一定会炸"或"已经在悄悄丢内容"的地方。

---

## 0. 审计怎么做的（可复现）

1. **枚举对账**：从 `kb_wiki_page` 取全部页名，再按配置的每个 `categories` / `prefixes` 调 wiki API 数成员，和库里逐来源比。
2. **清洗重跑**：拉每页原文，用 `server/target/classes` 里的**真清洗器**（不是复刻逻辑）重跑一遍，和库里 `kb_block.body` 比对。
3. **逐级 bisect**：写一个同包驱动（`com.example.qqbot.kb.wiki`）依次调用 `removeComments → stripTags → stripTemplates → stripTables → stripLinks`，打印每一步的长度，定位"内容是在哪一步没的"。
4. **重定向审计**：对全部 637 页带 `redirects=1` 查一遍，看有没有"重定向到集合外"的页。

**基线数字**（2026-10-06 实测）：

| 指标 | 值 |
|---|---|
| 导入页数（9 个来源） | 637，与 wiki 逐来源**完全一致** |
| 失败页（`error` 非空） | 0 |
| 写入块 | 767 块 / 539,025 字 |
| 原文 → 清洗后 | 935,670 → 543,040 字（保留 **58.0%**）|
| 原文 >100 字但清洗为 0 的页 | **11 页** |

---

## 1. `stripTables` 丢行 —— 表格页会整页变空（严重，已复现）

**症状**：一整页表格内容 → 清洗后 0 字，块不生成、状态行 `chars=0`，**全程不报错**。

**证据**（`Equipment` 页逐级 bisect）：

```
原文 1542 → 去注释 1542 → 去标签 1488 → 去模板 1488 → 去表格 954 → 去链接 23 → clean() = 0
```

**根因**是两处叠加：

1. [`WikitextCleaner.stripTables`](../server/src/main/java/com/example/qqbot/kb/wiki/WikitextCleaner.java) 只处理以 `|` / `!` / `|-` 开头的行，**没有 else 分支** —— 表格内其它行（不含这些前缀的）被**直接丢弃**。
2. [`WikitextCleaner.clean`](../server/src/main/java/com/example/qqbot/kb/wiki/WikitextCleaner.java) 的执行顺序把 `stripTags` 放在 `stripTables` **之前**，而 `stripTags` 会把 `<br>` 变成换行 —— 于是**单元格里 `<br>` 之后的内容都变成了"表格内不以 | 开头的行"，全部消失**。

`Equipment` 整页都是 `| [[File:…]]<br>[[X|Y]]` 形式的图片链接表格：`<br>` 后面那半截被丢，剩下的 `[[File:…]]` 又被 `stripLinks` 整条删掉 → 0 字。

**影响面**：当前 11 页受影响（`Equipment` 1542 字 + 9 个 `Baby *` + `Bees`，见下条）；**所有含表格的页**都在悄悄丢 `<br>` 之后的内容，只是没到 0 所以没被发现。

**改法**（两处，约 3 行）：

- `stripTables` 补兜底分支：表格内不匹配前三种前缀的行，**原样保留**（`else { out.append(t).append('\n'); }`）。
- `clean()` 顺序调整为：`removeComments → stripTemplates → stripTables → stripTags → stripLinks → stripEmphasis → 收尾空白`（**先处理表格结构，再把 `<br>` 变换行**）。
- 注意 `stripTables` 目前依赖"一行 = 一个单元格"，顺序改了以后单元格里的 `<br>` 不再提前变成换行，这个前提反而更成立。

**验收**：重跑 bisect，`Equipment` 期望 >1000 字；再抽查 3 个含表格的页（`Combat Mechanics` / `Craftspeople` 类），确认长度只增不减。

---

## 2. 白名单外的模板整段丢 —— `{{NPC Infobox}}` 的 description 没了（中）

**症状**：9 个 `Baby *` 页 + `Bees`，原文各有 170~540 字，清洗后 **0**；`Wolf` / `Draconian Vulture` 只保住 0.8%（5 字）。

**证据**：这些页的正文**只有**一个模板：

```wikitext
{{NPC Infobox
| images = Baby Capybara.png
| description = A tiny Capybara. It enjoys headpats, chewing on daylilies…
| Behavior = Fleeting
| Tameable = No
}}
```

[`WikitextCleaner.FIRST_ARG_TEMPLATES`](../server/src/main/java/com/example/qqbot/kb/wiki/WikitextCleaner.java) 白名单里没有 `NPC Infobox` → `resolveTemplate` 返回空串 → 整页为空。

**这是"刻意为之"的策略**（类注释：*宁可少留，不要留错*），但 `description` 是**真人写的描述句**，不是噪声，丢掉它等于这几页白导。

**改法**：给 `NPC Infobox` 加一条**按参数名取值的模板规则**（现有 `LABEL_TEMPLATES` 只按位置取，不够用）：

- 新增 `KEYED_TEMPLATES`：`{"npc infobox": List.of("description")}`，取 `description`（可加 `Behavior` / `Tameable` 这类短字段，但**别把 `images` 收进来** —— 那是文件名，不是正文）。
- 解析时要按 `=` 切 key/value，并只取指定 key。

**验收**：重跑后 `Baby Capybara` 期望 ≈ description 那句话的长度（~180 字），且 `{{NPC Infobox}}` 里没有第二个被误收的参数。

---

## 3. 列表不翻页 —— 超过上限就静默截断（中，潜伏）

**症状**：某个分类/前缀的成员数超过上限时，多出来的页**不会被列到**，日志仍然显示"来源 X 列到 N 页"，**不报错、不写状态行、导入报告里看不出来**。

**证据**：
- [`WikiApiClient.listCategoryMembers`](../server/src/main/java/com/example/qqbot/kb/wiki/WikiApiClient.java) / `listByPrefix` / `listNamespacePages` 都是**单次请求**，都不跟 MediaWiki 的 `continue` 续页令牌。
- 上限来自 [`application.yml`](../server/src/main/resources/application.yml)：`wiki-import.max-pages-per-source: 300`、`map-sync.max-pages: 500`。
- 实测 9 个来源**全部 `continue=false`**，最大的是 `Lore` 264、`Quests/` 155 → **现在没踩到**。

**风险**：`Lore` 距 300 只剩 36 页。分类一旦涨过去，第 301 页起会**静默消失**，而且没人会发现 —— 这是"遗漏"里最难查的一类。

**改法**：
1. 三个列表方法都**跟 `continue`**（`continue=`/`apcontinue=`/`cmcontinue=` 原样回传），循环到没有令牌为止。
2. **务必加一条断言/告警**：`列到的页数 == limit` 时打 `WARN`（"可能被截断，检查 max-pages-per-source"）。护栏比修好更重要 —— 上限可以调，静默不行。
3. 导入报告的 `bySource` 里带上"是否顶到上限"。

**验收**：临时把 `max-pages-per-source` 调成 50，跑导入，期望**恰好列出 50 页 + 一条 WARN**，而不是静默 50。

---

## 4. 分类成员不区分类型 —— 子分类/文件被当正文拉（低）

**症状**：`Category:Gameplay` 的 11 个成员里有 **2 个是 `Category:` 子分类**（库里就是 `Category:Debuffs` / `Category:Skills`，原文 45 / 21 字，清洗后 0）。它们进了状态表、占了导入页数，但一个块都不产。

**证据**：wiki API 返回 `ns0=9 ns14=2`；代码里 `categorymembers` 请求**没有 `cmtype=page`**。

**影响**：不会漏内容（是多了不是少了），但让"导入页数"和"文章数"对不上，也让第 1、2 条的 0 产出更难被注意到。

**改法**：请求加 `&cmtype=page`（或 `cmtype=page|subcat` 但把 subcat 单独统计、不建块）。同时把 ns 分布写进导入日志。

---

## 5. 重定向不解析（低，潜伏）

**症状**：分类里如果有**重定向页**，`prop=revisions` 拿回的是 `#REDIRECT [[X]]` 而不是目标页正文 → 这页等于没导。若目标页也在导入集合内，只是浪费一页；**若目标页在集合外，那份正文就彻底漏了**。

**证据**：当前 637 页里只有 **3 个重定向**，且 3 个目标**都在**导入集合内 → 现在无损失。请求里没有 `redirects=1`。

**改法**：列页/取正文时带 `redirects=1`，并在返回里记录"谁被解析到了谁"；目标不在本次来源集合内时**单独记一条 WARN**（说明它只通过重定向可达）。

---

## 6. 流程性风险：清洗器一改，老页永远不会重洗（流程，非代码 bug）

**机制**：增量判据是 `revid`（[`WikiArticleImporter`](../server/src/main/java/com/example/qqbot/kb/wiki/WikiArticleImporter.java) 第 ③ 步）。页面在 wiki 上没变 → `revid` 不变 → **跳过**。所以**清洗逻辑改了以后，已经导入的页不会自动重洗**，库里留的还是旧清洗器的产物。

**现状**：我逐页比对过，当前语料与当前清洗器**一致**（差异只有"中文名。"前缀和块边界的空白归一化）—— 所以现在没这个问题。

**规矩**：**动 `WikitextCleaner` / `KbTextChunker` 之后，必须**
```bash
# 1) 回滚（连状态一起清，之后能重导）
... --app.kb.wiki-import.import-on-start=true --app.kb.wiki-import.purge=true --spring.main.web-application-type=none
# 2) 重导（会重新 embedding，按量计费）
... --app.kb.wiki-import.import-on-start=true --spring.main.web-application-type=none
```
这条要写进《知识库运营手册》。另：`purge` 后词条表会留孤儿词条，去管理端「词条」面板点一次**清理孤儿词条**。

---

## 7. 落地顺序与验收

| 序 | 改动 | 文件 | 风险 | 验收 |
|---|---|---|---|---|
| 1 | `stripTables` 补 else + 调整 `clean()` 顺序 | `WikitextCleaner` | 低（有 `WikitextCleanerTest`） | `Equipment` 1542→>1000 字；`KbTextChunkerTest` 不回归 |
| 2 | `NPC Infobox` 按 key 取 `description` | `WikitextCleaner` | 低 | `Baby Capybara` ≈180 字 |
| 3 | 列表跟 `continue` + 顶到上限告警 | `WikiApiClient` | 低 | 限 50 时列出 50 + WARN |
| 4 | `cmtype=page` + ns 分布进日志 | `WikiApiClient` | 低 | `Gameplay` 只列 9 页 |
| 5 | `redirects=1` + 集合外目标告警 | `WikiApiClient` | 低 | 3 个重定向都被解析 |
| 6 | 导入报告加"0 产出页清单" | `WikiArticleImporter` | 低 | 13 页被列出（改完后应降到 0~2）|
| 7 | 把"改清洗器必须 purge 重导"写进手册 | `docs/md/知识库运营手册.md` | 无 | — |

**做 1~3 之后必须重导一次语料**（第 6 条的流程），否则改动对**已经导入的 637 页**不生效。

---

## 8. 修复记录（2026-10-07）

| 缺陷 | 状态 | 改法 | 验证 |
|---|---|---|---|
| 1 `stripTables` 丢行 | ✅ 已修 | `stripTables` 补 else 兜底（表格内其余行原样保留）+ `clean()` 顺序改成 `stripTemplates → stripTables → stripTags`（先按行看懂表格，再让 `<br>` 变换行）| `WikitextCleanerTest.tableKeepsEverything`；真实语料 `Equipment` **0 → 177 字** |
| 2 `{{NPC Infobox}}` 整块丢 | ✅ 已修 | 新增 `KEYED_TEMPLATES`（按参数名取 `description`，只取这一个字段）| `WikitextCleanerTest.npcInfobox`；`Baby Capybara` **0 → 124 字** |
| 3 列页不翻页 | ✅ 已修 | 新增 `WikiApiClient.collect`（跟 `continue` 拉完 + 轮数上限兜底）；**顶到上限必须告警**，并进导入报告的 `errors` | 新增 `WikiApiClientPagingTest`（5 条，离线） |
| 4 分类成员不分类型 | ✅ 已修（顺带）| 请求加 `cmtype=page` | dry-run：`Gameplay` **11 → 9**、总页数 **637 → 635** |
| 5 重定向不解析 | ⏳ 未修 | 当前 3 个重定向的目标都在集合内，暂无实际损失 | — |
| 6 改清洗器必须 purge 重导 | ⏳ 流程约定 | 见下面 §9.3 | — |

### 真实语料上的前后对比（637 页，离线免费跑，未联网写库）

```
原文合计                       935,670 字
清洗后（改前，库里存的）       538,998 字
清洗后（改后）                 572,994 字   （至少 +33,996，+6.3%）
原文 >100 字却清洗为 0 的页     11 页  →  0 页
```

> 「改前」用的是库里存的数：它每块还带一个 `中文名。` 前缀，所以真实增幅比 6.3% 再大一点。
> 逐页抽样：`Equipment` 0 → 177、`Baby Capybara` 0 → 124、`Bees` 0 → 15、
> `Wolf` 5 → 13、`Draconian Vulture` 5 → 26。

---

## 9. 这轮新发现（未修，留档）

### 9.1 上游删页 / 改分类后，状态行与块不会清（低）

导入器只对"这次列到的页"做增改，**不会清掉"状态表里有、这次没列到"的页**。后果：

- 页在 wiki 上被删、或从分类里移走之后，它的块**仍然留在语料里** —— 检索还答得出，内容却是旧的。
  这是知识库最忌讳的一类问题（"已经不存在的东西还答得头头是道"）。
- 本次加 `cmtype=page` 之后，`Category:Debuffs` / `Category:Skills` 这两行会变成**永久残留**（0 块 0 字）。
  现在无害，但它说明"来源里消失的页"没有出口。

**建议修法**：导入结束时比 "本次列到的页" 与 "状态表里同来源的页"，差集**记 warn 并列出来**
（先不自动删 —— 一次 API 抖动不该把整批语料删掉）。要真删走 `purge`（按来源整体回滚）。

### 9.2 版本戳是进程内的（运维注意，不是 bug）

`KbBlockStore.version` / `KbBlockIndex` 的自失效都在**同一个 JVM 内**。CLI 导入或 purge 之后，
**正在跑的机器人进程**内存里还是旧快照 —— 必须重启才看得到新语料。
改造前其实也一样（那时也是另一个进程调 `reload()`），写下来只是为了不再误会。

### 9.3 改完清洗器之后的必做动作（本轮的收尾）

```bash
# ① 回滚（连状态一起清，否则下次导入会空转）
mvn spring-boot:run -Dspring-boot.run.arguments="--app.kb.wiki-import.import-on-start=true --app.kb.wiki-import.purge=true --spring.main.web-application-type=none"
# ② 重导（会重新 embedding，按量计费）
mvn spring-boot:run -Dspring-boot.run.arguments="--app.kb.wiki-import.import-on-start=true --spring.main.web-application-type=none"
# ③ 重启机器人进程（见 §9.2）
# ④ 管理端「词条」面板点一次「清理孤儿词条」（purge 会留下孤儿词条）
```