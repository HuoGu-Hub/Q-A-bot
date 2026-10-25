# 文档索引

本目录只存放项目文档与关键技术说明。开发计划、诊断记录与一次性评估不进入仓库。

| 文档 | 内容 | 读者 |
|---|---|---|
| [architecture.md](architecture.md) | 进程拓扑、消息流水线、模块边界、数据模型、配置体系 | 需要理解整体结构的人 |
| [retrieval.md](retrieval.md) | 三路召回、RRF 融合、重排、工具调用、公开站检索 | 修改检索逻辑的人 |
| [knowledge-base.md](knowledge-base.md) | 块模型、文档格式、导入语义、wiki 与地图摄入、运维命令 | 维护语料与知识库的人 |
| [deployment.md](deployment.md) | NapCat、systemd、Nginx、备份、升级与排障 | 部署与运维的人 |
| [development.md](development.md) | 本地环境、构建、测试分层、回归门禁 | 参与开发的人 |

## 阅读顺序

1. 先读根目录 [README](../README.md)，确认项目定位与快速启动方式。
2. 需要改代码时读 [architecture.md](architecture.md)，先确认目标模块与依赖边界。
3. 涉及问答质量时读 [retrieval.md](retrieval.md)，涉及语料时读 [knowledge-base.md](knowledge-base.md)。
4. 修改完成后按 [development.md](development.md) 跑对应测试与门禁。

## 约定

- 文档使用 Markdown，图使用 Mermaid，不提交 HTML 渲染副本。
- 文档描述当前代码行为。历史方案与取舍过程保留在 Git 历史中。
- 配置项名称、类名、表名以代码为准，文档不重复列出全部配置。
