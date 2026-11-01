# 文档索引

本目录只存放当前有效的项目文档。开发计划、诊断记录与一次性评估不进入仓库。

| 文档 | 内容 | 读者 |
|---|---|---|
| [architecture.md](architecture.md) | 进程拓扑、消息流水线、模块边界、数据模型、配置体系 | 需要理解整体结构的人 |
| [retrieval.md](retrieval.md) | 三路召回、RRF 融合、重排、工具调用、公开站检索 | 修改检索与作答逻辑的人 |
| [knowledge-base.md](knowledge-base.md) | 块模型、文档格式、导入语义、wiki、地图与资源点位摄入 | 维护语料与知识库的人 |
| [intent.md](intent.md) | 意图判定、检索跳过、会话纠正与回答契约 | 调整问答行为的人 |
| [voice.md](voice.md) | 语音条链路、素材库、TTS、触发规则与配置 | 维护语音能力的人 |
| [security.md](security.md) | 信任边界、身份授权、入站、数据保护与上线验收 | 部署与安全审查的人 |
| [deployment.md](deployment.md) | NapCat、systemd、Nginx、备份、升级与排障 | 部署与运维的人 |
| [development.md](development.md) | 本地环境、构建、测试分层、接口契约与回归门禁 | 参与开发的人 |
| [admin-qq-auth.md](admin-qq-auth.md) | 后台账号、QQ 验证码、信任设备与角色 | 管理后台运维的人 |
| [cloudflare-access.md](cloudflare-access.md) | Cloudflare Access 接入与源站锁定 | 需要额外身份层的人 |
| [openapi.json](openapi.json) | 由测试生成的 OpenAPI 3 规范 | 对接 HTTP 接口的人 |
| [samples/](samples/) | 知识库文档格式样例 | 编写语料的人 |

## 阅读顺序

1. 先读根目录 [README](../README.md)，确认项目定位与快速启动方式。
2. 需要改代码时读 [architecture.md](architecture.md)，先确认目标模块与依赖边界。
3. 涉及问答质量时读 [retrieval.md](retrieval.md) 与 [intent.md](intent.md)，涉及语料时读 [knowledge-base.md](knowledge-base.md)。
4. 涉及语音或安全边界时读 [voice.md](voice.md) 与 [security.md](security.md)。
5. 修改完成后按 [development.md](development.md) 跑对应测试与门禁。

## 约定

- 文档使用 Markdown，图使用 Mermaid，不提交 HTML 渲染副本。
- 文档描述当前代码行为。历史方案与取舍过程保留在 Git 历史中。
- 配置项名称、类名、表名以代码为准，文档不重复列出全部配置。
