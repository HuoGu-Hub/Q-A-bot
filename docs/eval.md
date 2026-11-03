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
| `eval_tool_call` | 工具调用，只有调用与参数，返回未采集 |
| `eval_review_task` | 复核队列，同一 run 只留一个未关闭任务 |
| `eval_baseline` | 基线，同一版本只允许一条 active |

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
| GET | `/runs/{id}` | 单条执行：span、检索快照、判定、工具调用 |
| GET | `/batches/{id}/failures` | 失败归因下钻，可按标签过滤 |
| POST | `/runs/{id}/actions/review` | 人工转复核 |
| GET | `/reviews` | 复核队列 |
| POST | `/reviews/{id}/actions/claim` | 认领 |
| POST | `/reviews/{id}/actions/submit` | 提交人工结论，覆盖同 run 的结果质量判定 |
| GET | `/baselines` | 基线列表 |
| POST | `/baselines` | 用某批次生成候选基线 |
| POST | `/baselines/{id}/actions/promote` | 晋升为活动基线 |

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
- 失败归因：失败标签落在 `eval_failure`，批次页按标签点开可看到受影响的用例与原因。
- 重复运行：每条用例跑 N 次，N 大于 1 时稳定性由跑完之后的一致性判定给出，
  一致性比较的是质量与检索两维的结论，不看分数。
- 门禁三态：同一版本存在活动基线时与基线比较，回退超过 3 个百分点判 `blocked`；
  没有基线时按默认阈值 80%，达到 `passed`，低于 `warned`，无判定 `pending`。
- 目标快照：建批时记录检索阈值、provider、语料版本；跑完比对一次，
  不一致标 `warned` 并把差异写进批次的原因。

## 6. 运行

启动服务后打开 `/admin/eval`。首次启动时若一个用例集都没有，会从
`tests/golden/retrieval-golden.tsv` 导入并冻结 v1；找不到文件就从空开始，
可以在页面上粘贴 TSV 导入。

建批后执行是异步的：批次页每 3 秒轮询，跑完自动停。

## 7. 已知缺口

| 缺口 | 影响 | 下一步 |
|---|---|---|
| token 已按 run 落库，但成本价目未接入 | 成本维度仍出 SKIP，只有 tokens 可看 | 加 `eval_price` 并算 `cost_micro` |
| 工具调用只有名称与参数，返回值与耗时未采集 | Agent 维度能判"选对没有"，判不了"返回对不对" | 包装 `ToolExecutor` 记录执行结果 |
| 逐句引用未采集 | 证据链维度为空 | 后置对齐原型 |
| 重跑失败项、暂停、取消 | 只能克隆整批 | 执行器支持中断与断点 |
| 告警、报告、趋势、版本对比 | 页面未提供入口 | P1 |
| 评测写入共用主库 | 大批次会与问答抢写 | 批次量上来后切库副本 |
| 失败归因没有独立页 | 只能在批次页看标签计数 | 加按标签筛选与逐条下钻 |

评测执行会往 `qa_turn` 写一条 `群 0 / 用户 0` 的记忆，用于意图判定的最近轮次；
它按 3 条截断、7 天清理，不会进 `qa_stat`。

## 8. 测试

| 测试 | 覆盖 |
|---|---|
| `RuleJudgeTest` | 判定口径：空返回、噪声、名次分、未标注、超时 |
| `EvalRepositoryTest` | 建表幂等、一次执行整体落库、判定幂等、批次读取 |
| `EvalFlowTest` | 建批到出结论的整条链路，模型用桩替换 |
