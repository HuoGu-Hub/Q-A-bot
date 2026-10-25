# 检索黄金集

回答质量的回归基线。数据在 `retrieval-golden.tsv`，跑手是 `RetrievalGoldenTest`。

## 运行

```bash
# 完整门禁，重排开启
./scripts/golden-gate.sh

# 对比用，关闭重排
./scripts/golden-gate.sh --rerank off
```

跑手的行为：

1. 复制一份 `server/data/qa/qqbot.sqlite` 到临时目录，不直接读生产库。
2. 使用生产参数 `top-k=5`、`min-score=0.45`、`title-gate=0.70`。
3. 调用真实检索器 `KbRetriever`，不重新实现算法。
4. 按类别统计 R@1、R@5、MRR、干净率，并输出逐条明细。

门禁模式由 `-Dqqbot.golden.gate=true` 打开。缺少语料、缺少 API Key 或向量路不通都算失败，不会跳过。逐条结果写入 `.toolchain/verify/golden-detail.tsv`。

## 类别

| 类别 | 含义 | 指标 |
|---|---|---|
| `entity` | 按名字提问，语料中有对应词条 | R@1、R@5、MRR |
| `colloquial` | 使用俗称，语料中是官方译名 | R@1、R@5、MRR |
| `gap` | 真实问题，语料中没有答案 | 干净率 |
| `chitchat` | 与知识库无关 | 干净率 |
| `descriptive` | 描述需求式提问 | 打印 Top-5，人工判定 |

`gap` 与 `chitchat` 的正确行为是一个块都不注入。注入无关资料比返回空更糟，模型会被带偏。类别必须分开统计，混合平均会掩盖结构性缺陷。

## 数据格式

制表符分隔，`#` 开头为注释：

```text
category<TAB>question<TAB>expected_block_ids<TAB>note
```

| 字段 | 说明 |
|---|---|
| `expected` | 多个可接受答案用 `;` 分隔 |
| `-` | 期望不检索到任何块 |
| `?` | 尚未人工标注，只打印不打分 |

## 添加条目

1. 先确认语料中对应块的 id。
2. 按用户真实说法写问题，不把官方术语改写成书面语。
3. 同一实体可以有多条问法，覆盖俗称、简称与错别字。
4. 添加后运行门禁，确认新样本的排名符合预期。
5. 标注无法确定时填 `?`，不要猜。

标注错误会表现为系统错误。问题指向的实体、类别与期望 id 必须逐字核对。

## 调参约束

向量余弦的分布在「该答」与「无资料」两类之间重叠，调高 `min-score` 会挡掉口语提问。可区分性来自交叉编码器重排。调整任何检索参数后都必须重跑本数据集，并在提交说明中写明变化。
