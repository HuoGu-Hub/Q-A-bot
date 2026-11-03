# 评测中台

## 1. 定位

评测中台把黄金评测集变成可重复执行的批次：建批 → 执行 → 留痕 → 判定 → 聚合，
每一步的产物都落在 `eval_*` 表里，页面在管理后台「评测」。

它不复制检索与作答的实现：执行器只装配参数、开 span，检索、注入、工具循环与降级
全部调用生产代码。结论因此能外推到线上。

## 2. 代码位置

| 位置 | 内容 |
|---|---|
| `server/src/main/java/com/example/qqbot/eval` | 用例集、执行器、判定、聚合、接口 |
| `server/src/main/java/com/example/qqbot/persistence/EvalRepository.java` | 十一张表的 SQL |
| `web/src/admin/views/EvalView.vue` | 批次与用例集 |
| `web/src/admin/views/EvalBatchView.vue` | 单批次结果 |
| `web/src/admin/views/EvalRunView.vue` | 单条用例时间轴 |

```
eval
├─ EvalSpan / TraceBuilder      过程 span
├─ CaseSpec / Observation       执行期快照与判定输入
├─ RuleJudge                    规则判定，不调模型
├─ RetrievalSnapshot            检索快照与空结果原因
├─ EvalDatasetService           TSV 导入、用例维护、冻结版本
├─ EvalCaseExecutor             跑一条用例并落 span 树
├─ EvalRunner                   独立低优先级线程池，默认 2 并发
├─ EvalAggregator               通过率、维度、延迟分位、失败标签
├─ EvalService                  建批、拆项、查询
├─ EvalSeeder                   首次启动导入检索黄金集
└─ EvalController               /admin/api/eval
```

## 3. 数据表

| 表 | 用途 |
|---|---|
| `eval_dataset` | 用例集 |
| `eval_dataset_version` | 冻结版本与 checksum |
| `eval_case` | 用例，按 `case_key` 幂等 |
| `eval_batch` | 批次与目标快照 |
| `eval_item` | 批次内的用例项与快照 |
| `eval_run` | 一次执行 |
| `eval_span` | 过程节点 |
| `eval_retrieval` | 检索快照 |
| `eval_judge` | 判定结论，`judge_key` 唯一 |
| `eval_failure` | 失败标签 |

建表在 `EvalRepository.initSchema`，幂等；启动时由 `EvalSeeder` 调用一次。

## 4. 接口

前缀 `/admin/api/eval`，鉴权与角色范围沿用后台：新增区域 `eval`，
默认对 VIEWER、EDITOR、ADMIN 开放；写操作仍由角色级别决定。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/datasets` | 用例集列表 |
| POST | `/datasets` | 新建用例集 |
| GET | `/datasets/{id}` | 详情、版本、用例 |
| POST | `/datasets/{id}/cases` | 新增或覆盖单条用例 |
| POST | `/datasets/{id}/actions/import` | TSV 预检或导入 |
| POST | `/datasets/{id}/actions/freeze` | 冻结版本 |
| GET | `/batches` | 批次列表 |
| POST | `/batches` | 建批并异步入队 |
| GET | `/batches/{id}` | 批次、指标、用例列表 |
| POST | `/batches/{id}/actions/clone` | 用同一用例集再跑一批 |
| GET | `/runs/{id}` | 单条执行：span、检索快照、判定 |

## 5. 判定口径

| 结论 | 含义 |
|---|---|
| PASS | 该维度按现有事实判为通过 |
| FAIL | 判为失败，并给出失败标签 |
| SKIP | 该维度还没有判定来源，或期望值未标注 |

- 通过率 = PASS / (PASS + FAIL)，`SKIP` 不进分母。
- 规则判定覆盖：空返回、兜底话术、协议标记泄漏、库外问题该拒未拒、
  期望块是否命中、期望空结果却注入噪声、单例超时。
- 事实正确性、引用支持、有害内容仍待 rubric，结论为 SKIP 并进人工。
- 门禁三态：默认阈值 80%，达到为 `passed`，低于为 `warned`，无判定为 `pending`。
  基线机制上线后改成与激活基线比较。

## 6. 运行

启动服务后打开 `/admin/eval`。首次启动时若一个用例集都没有，会从
`tests/golden/retrieval-golden.tsv` 导入并冻结 v1；找不到文件就从空开始，
可以在页面上粘贴 TSV 导入。

建批后执行是异步的：批次页每 3 秒轮询，跑完自动停。

## 7. 已知缺口

| 缺口 | 影响 | 下一步 |
|---|---|---|
| 逐次 token 未落库 | 成本维度只能出 SKIP | `LlmRouter` 把 usage 写进 span |
| 工具调用参数与返回值未落库 | Agent 维度只有一个总 span | `KbToolAssistant` 授权钩子旁写 `eval_tool_call` |
| 逐句引用未采集 | 证据链维度为空 | 后置对齐原型 |
| 重跑失败项、暂停、取消 | 只能克隆整批 | 执行器支持中断与断点 |
| 人工复核、基线、告警 | 页面未提供入口 | P1 |
| 评测写入共用主库 | 大批次会与问答抢写 | 批次量上来后切库副本 |

评测执行会往 `qa_turn` 写一条 `群 0 / 用户 0` 的记忆，用于意图判定的最近轮次；
它按 3 条截断、7 天清理，不会进 `qa_stat`。

## 8. 测试

| 测试 | 覆盖 |
|---|---|
| `RuleJudgeTest` | 判定口径：空返回、噪声、名次分、未标注、超时 |
| `EvalRepositoryTest` | 建表幂等、一次执行整体落库、判定幂等、批次读取 |
| `EvalFlowTest` | 建批到出结论的整条链路，模型用桩替换 |
