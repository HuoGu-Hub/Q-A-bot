# token 用量统计

统计的是**每一次模型往返**的输入与输出 token。一次群聊回答可能包含多轮工具调用，
每一轮都是一次真实的计费请求，所以计数单位是往返，不是回答。

## 数据来源

优先级只有一条：**厂商回了 usage 就用真实值，没回才估算。**

OpenAI 协议里 `usage` 是可选字段。2026-11-08 实测默认 provider
`opencode-go / deepseek-v4.1-flash` 会返回完整用量，包括推理 token；
网关侧成本也在这个对象里，但 LangChain4j 不解析它，所以本页不显示金额：

~~~json
"usage": {
  "prompt_tokens": 35,
  "completion_tokens": 17,
  "total_tokens": 52,
  "completion_tokens_details": { "reasoning_tokens": 14 },
  "opencode": { "list_cost": 0.00001545, "allowance": 0.00000927 }
}
~~~

LangChain4j 的 `OpenAiChatModel` 会把这个字段解析成 `ChatResponse.tokenUsage()`，
`LlmRouter` 在唯一收口处 `accountUsage()` 分发给四个消费者：

| 消费者 | 用途 |
|---|---|
| `BudgetGuard` | 当日 token 额度熔断 |
| `LlmUsage` | 评测执行器收集单次用例的 token |
| `LlmMetrics` | 最近 60 分钟的实时曲线 |
| `LlmUsageLedger` | 按日 × provider × model 的历史账 |

## 缺 usage 时的估算

估算器是 `llm.TokenEstimator`，只处理"厂商没回"这一条兜底路径。

| 项 | 系数 |
|---|---|
| 中日韩文字 | 0.85 token / 字 |
| 其它字符 | 4 字符 / token |
| 一条消息结构开销 | 4 token |
| 一次请求固定开销 | 25 token |
| 一张图片 | 800 token |

工具定义按 `ToolSpecification.toJson()` 的字符量折算，跟着请求内容走。

系数来自同一组实测样本：

| 样本 | 实际输入 | 加权字符 |
|---|---|---|
| 三字短问 | 33 | 4.2 |
| 约 1.1KB 英文 | 277 | 275 |
| `system.md` + 短问 | 1982 | 2507 |

长文本斜率约 0.79 token / 加权字，短文本有 25~30 token 的固定开销。
取 0.85 与 25 是折中：长文本高估约一成，短文本误差更大但绝对量小。

**已知偏差**：推理模型的思考 token 不在返回正文里。厂商没回 usage 时，
输出侧看不到这部分，估算值会偏低。默认 provider 会回 usage，主线不受影响。

## 存储

表 `llm_usage_day`，主键是 日 × provider × model × 是否估算：

~~~sql
CREATE TABLE llm_usage_day (
  day        TEXT NOT NULL,
  provider   TEXT NOT NULL,
  model      TEXT NOT NULL,
  estimated  INTEGER NOT NULL,
  calls      INTEGER NOT NULL,
  tokens_in  INTEGER NOT NULL,
  tokens_out INTEGER NOT NULL,
  PRIMARY KEY (day, provider, model, estimated)
);
~~~

写入路径：`LlmUsageLedger` 先在内存里按桶累加，
`MetricsRollupJob` 每分钟 `drain()` 一次并 upsert。列表在回复路径上不碰 SQLite，
不会和知识库导入、聊天落库抢全局锁；代价是进程崩溃最多丢一分钟的账。

- 真实值与估算值分成两行，页面能说"其中多少是估的"，不会把估算冒充实测。
- 保留 180 天，由同一个每分钟任务每 60 分钟清理一次。
- 日界按 `AppTime.ZONE` 的东八区算，和看板、预算同一个口径。

## 接口与页面

`GET /admin/api/llm/usage?days=30`

| 参数 | 说明 |
|---|---|
| `days` | 窗口长度，默认 30；`0` 等于保留期 180 天；上限 180 |

响应包含 `totals`、按天补零的 `daily`、按用量倒序的 `models`。
`estimatedIn / estimatedOut` 是 `tokensIn / tokensOut` 的子集，不是额外一笔。

页面在「统计 → 用量」，复用统计页的 7 / 30 / 90 / 全部范围选择。
内容是三块：窗口 KPI、每日输入/输出堆叠柱、按模型的明细表。

## 边界

- **不统计成本**：价目表属于评测库，且 OpenCode 的 `list_cost` 被 LangChain4j 丢弃。
  需要钱的时候用评测页已配置的价目另算，不要在这里猜。
- **不统计连通性测试**：`LlmRouter.probe()` 是后台的手动测试，走临时客户端，不经过记账收口。
- **只统计成功往返**：失败、空回复不计 token；它们计入 `llm_minute` 的失败数。
- **不区分场景**：目前只按 provider / model 归集，不区分群聊、蒸馏、评测。
  需要时再加维度，先把"花在哪个模型上"回答清楚。