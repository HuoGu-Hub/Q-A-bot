# corpus

示例语料与来源数据，用于本地验证与演示。系统运行时不直接读取本目录，语料需经管理端导入或 wiki 抓取进入 SQLite。

| 路径 | 内容 |
|---|---|
| `gamedata/*.md` | 已整理的中文文档，符合知识库文档格式 |
| `gamedata/_source/*.json` | 原始条目数据 |
| `gamedata/index/*.tsv`、`*.jsonl` | 由来源数据生成的索引 |

导入前先校验格式：

```bash
./scripts/validate-kb-doc.sh corpus/gamedata/*.md
```

格式规范见 [docs/knowledge-base.md](../docs/knowledge-base.md)。

## 许可

示例语料整理自 `enshrouded.wiki.gg`。版权与许可归来源站点所有，随仓库分发或二次使用前应确认其条款。
