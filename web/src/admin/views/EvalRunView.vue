<script setup lang="ts">
/**
 * 单条用例的过程 —— 从输入到判定的一条时间轴。
 *
 * <p>left 是阶段清单，right 是按总耗时归一化的条。条的长度就是耗时占比，
 * 这样"卡在检索还是卡在模型"不用读数字就能看出来。
 *
 * <p>条只对得起"有开始与耗时"的 span。工具调用的细粒度、token 都还没有结构化出口，
 * 所以这一屏**不画**它们，也不编一个数 —— 缺的东西写"未采集"。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminApi, describeError } from '@shared/api/client'
import type { EvalRunDetail, EvalSpan } from '@shared/api/types'
import AdminPage from '@shared/ui/AdminPage.vue'
import Panel from '@shared/ui/Panel.vue'
import Button from '@shared/ui/Button.vue'
import Notice from '@shared/ui/Notice.vue'
import Tag from '@shared/ui/Tag.vue'
import Stat from '@shared/ui/Stat.vue'
import Empty from '@shared/ui/Empty.vue'

const route = useRoute()
const router = useRouter()
const runId = Number(route.params.id)

const run = ref<EvalRunDetail | null>(null)
const loading = ref(false)
const error = ref('')

/** 时间轴的基准：根 span 的耗时。没有根时退回 run 自己的耗时 */
const totalMs = computed(() => {
  const root = run.value?.spans.find(s => s.parentSeq === 0)
  return Math.max(1, root?.durationMs ?? run.value?.latencyMs ?? 1)
})

const spans = computed(() => run.value?.spans ?? [])

const reviewMsg = ref('')

async function requestReview() {
  try {
    await adminApi.post(`/eval/runs/${runId}/actions/review`, { reason: '人工要求复核' })
    reviewMsg.value = '已转复核'
  } catch (e) {
    reviewMsg.value = describeError(e)
  }
}

async function load() {
  loading.value = true
  try {
    run.value = await adminApi.get<EvalRunDetail>(`/eval/runs/${runId}`)
    error.value = ''
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function barStyle(s: EvalSpan) {
  const left = Math.min(100, (s.startOffsetMs / totalMs.value) * 100)
  const width = Math.max(1.2, Math.min(100 - left, (s.durationMs / totalMs.value) * 100))
  return { left: `${left}%`, width: `${width}%` }
}

const KIND_LABEL: Record<string, string> = {
  request: '请求',
  intent: '意图',
  retrieve: '检索',
  inject: '注入',
  llm: '模型',
  tool: '工具',
  judge: '判定',
  outbound: '出站',
  guard: '护栏',
}

const EMPTY_REASON: Record<string, string> = {
  disabled: '本轮未启用知识库',
  no_candidate: '候选为空，库里多半没有这份资料',
  below_threshold: '有候选但被阈值挡掉，先看 min-score 与重排',
}

const judgeVerdictTone = (v: string): 'good' | 'bad' | 'warn' => {
  if (v === 'PASS') return 'good'
  if (v === 'FAIL') return 'bad'
  return 'warn'
}

const DIM_LABEL: Record<string, string> = {
  quality: '结果质量',
  rag: 'RAG 检索',
  stability: '稳定性',
  cost: '成本',
  safety: '安全',
}

function when(value: string): string {
  return value ? value.slice(0, 19).replace('T', ' ') : '—'
}

function attrText(attrs: Record<string, unknown>): string {
  const entries = Object.entries(attrs)
  if (entries.length === 0) return '（无属性）'
  return entries.map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : String(v)}`).join('  ')
}
</script>

<template>
  <AdminPage width="wide" :title="run ? run.caseKey : '执行记录'" :count="run ? `${run.category} · 第 ${run.attempt} 次` : ''">
    <template #actions>
      <Button size="sm" :disabled="loading" @click="load">刷新</Button>
      <Button v-if="run" size="sm" :to="`/eval/batches/${run.batchId}`">回到批次</Button>
      <Button size="sm" @click="requestReview">转复核</Button>
      <Button size="sm" @click="router.back()">返回</Button>
    </template>

    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-if="reviewMsg" tone="ok">{{ reviewMsg }}</Notice>
    <Empty v-if="!run && !loading" title="执行记录不存在" />

    <template v-if="run">
      <Panel title="问题">
        <p class="question">{{ run.question }}</p>
        <div class="meta">
          <span>状态 <Tag :tone="run.status === 'succeeded' ? 'good' : 'bad'">{{ run.status }}</Tag></span>
          <span>命中 {{ run.hitCount }} 块</span>
          <span>知识库 {{ run.kbEnabled ? '开' : '关' }}</span>
          <span>{{ when(run.createdAt) }}</span>
        </div>
      </Panel>

      <div class="kpis">
        <Stat label="总耗时" :value="`${run.latencyMs} ms`" tone="flame" />
        <Stat label="命中块" :value="run.hitCount" tone="mist" />
        <Stat label="token 入" :value="run.tokensIn || '未计量'" tone="mist" />
        <Stat label="token 出" :value="run.tokensOut || '未计量'" tone="mist" />
        <Stat label="spans" :value="run.spans.length" tone="mist" />
      </div>

      <Panel title="时间轴" hint="条长 = 耗时占比，起点 = 相对请求开始">
        <div class="timeline">
          <div v-for="s in spans" :key="s.seq" class="row" :class="'k-' + s.kind">
            <div class="label">
              <span class="kind">{{ KIND_LABEL[s.kind] ?? s.kind }}</span>
              <span class="name">{{ s.name }}</span>
              <span class="num dur">{{ s.durationMs }} ms</span>
            </div>
            <div class="track">
              <span class="bar" :style="barStyle(s)" />
            </div>
            <details class="attrs">
              <summary>属性</summary>
              <code>{{ attrText(s.attrs) }}</code>
              <span v-if="s.errorCode" class="err">{{ s.errorCode }}</span>
            </details>
          </div>
        </div>
      </Panel>

      <Panel title="判定" :count="run.judges.length" hint="规则判定；事实与引用仍待 rubric 与人工">
        <table class="tbl">
          <thead>
            <tr><th>维度</th><th>结论</th><th class="num">分数</th><th>理由</th><th>标签</th></tr>
          </thead>
          <tbody>
            <tr v-for="j in run.judges" :key="j.dimension">
              <td>{{ DIM_LABEL[j.dimension] ?? j.dimension }}</td>
              <td><Tag :tone="judgeVerdictTone(j.verdict)">{{ j.verdict }}</Tag></td>
              <td class="num">{{ j.score.toFixed(2) }}</td>
              <td>{{ j.reason }}</td>
              <td>{{ j.tag ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
      </Panel>

      <Panel v-if="run.toolCalls.length" title="工具调用" :count="run.toolCalls.length"
             hint="只有执行前钩子：调用与参数可得，返回与耗时未知">
        <table class="tbl">
          <thead>
            <tr><th class="num">#</th><th>工具</th><th>参数</th><th>状态</th></tr>
          </thead>
          <tbody>
            <tr v-for="c in run.toolCalls" :key="c.callIndex">
              <td class="num">{{ c.callIndex }}</td>
              <td><code>{{ c.name }}</code></td>
              <td class="args">{{ c.args }}</td>
              <td>{{ c.status === 'unknown' ? '未知' : c.status }}</td>
            </tr>
          </tbody>
        </table>
      </Panel>

      <Panel v-if="run.retrieval" title="检索快照">
        <div class="meta">
          <span>向量候选 {{ run.retrieval.vectorCandidates }}</span>
          <span>关键词候选 {{ run.retrieval.keywordCandidates }}</span>
          <span>最高余弦 {{ run.retrieval.bestCosineRaw.toFixed(3) }}</span>
          <span>来源 {{ run.retrieval.sources }}</span>
          <span>耗时 {{ run.retrieval.latencyMs }} ms</span>
        </div>
        <p v-if="run.retrieval.emptyReason" class="empty-reason">
          {{ EMPTY_REASON[run.retrieval.emptyReason] ?? run.retrieval.emptyReason }}
        </p>
        <div v-if="run.retrieval.terms.length" class="terms">
          <Tag v-for="(t, i) in run.retrieval.terms" :key="i" tone="neutral">
            {{ t.zh }} → {{ t.en }}{{ t.inRetrieved ? ' ✓' : '' }}
          </Tag>
        </div>
        <table v-if="run.retrieval.hits.length" class="tbl">
          <thead>
            <tr><th class="num">#</th><th>块</th><th>标题</th><th class="num">分数</th><th>来源</th></tr>
          </thead>
          <tbody>
            <tr v-for="(h, i) in run.retrieval.hits" :key="i">
              <td class="num">{{ i + 1 }}</td>
              <td><code>{{ h.id }}</code></td>
              <td>{{ h.title }}</td>
              <td class="num">{{ Number(h.score).toFixed(3) }}</td>
              <td>{{ h.source }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="empty-reason">没有注入任何资料。</p>
      </Panel>

      <Panel title="答案">
        <pre class="answer">{{ run.answer || '（空）' }}</pre>
      </Panel>

      <Notice tone="info">
        token、工具调用参数与逐句引用还没有结构化出口，这一屏暂不展示；
        它们不会显示为 0，而是不出现在这里。
      </Notice>
    </template>
  </AdminPage>
</template>

<style scoped>
.question { margin: 0 0 var(--sp-2); font-size: var(--fs-lg); color: var(--ink); }
.meta { display: flex; flex-wrap: wrap; gap: var(--sp-4); font-size: var(--fs-sm); color: var(--ink-2); }
.meta span { display: inline-flex; align-items: center; gap: 6px; }
.kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(132px, 1fr)); gap: var(--sp-3); }

.timeline { display: flex; flex-direction: column; gap: var(--sp-2); }
.row {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: var(--sp-3);
  align-items: center;
}
.label { display: flex; align-items: baseline; gap: var(--sp-2); min-width: 0; }
.kind { font-size: var(--fs-xs); color: var(--ink-3); flex: none; width: 44px; }
.name { font-size: var(--fs-sm); color: var(--ink-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dur { margin-left: auto; font-size: var(--fs-xs); color: var(--ink-3); }
.track {
  position: relative;
  height: 10px;
  background: var(--stone-400);
  border-radius: var(--r-pill);
  overflow: hidden;
}
.bar {
  position: absolute;
  top: 0;
  bottom: 0;
  border-radius: var(--r-pill);
  background: var(--ember);
}
.k-retrieve .bar { background: color-mix(in srgb, var(--ember) 70%, var(--vital)); }
.k-llm .bar { background: var(--ember-hot); }
.k-judge .bar { background: var(--warn); }
.k-outbound .bar { background: var(--vital); }
.k-intent .bar { background: var(--ink-3); }
.attrs { grid-column: 1 / -1; font-size: var(--fs-xs); color: var(--ink-3); }
.attrs summary { cursor: pointer; }
.attrs code { display: block; margin-top: 4px; white-space: pre-wrap; word-break: break-word; }
.err { color: var(--blight); }

.tbl { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
.tbl th {
  text-align: left; font-weight: var(--fw-medium); font-size: var(--fs-xs); color: var(--ink-3);
  padding: var(--sp-2) var(--sp-3); border-bottom: 1px solid var(--edge); white-space: nowrap;
}
.tbl td { padding: var(--sp-2) var(--sp-3); border-bottom: 1px solid var(--hairline); vertical-align: top; }
.tbl :deep(.num), .tbl td.num, .tbl th.num { font-family: var(--font-mono); text-align: right; }

.empty-reason { margin: var(--sp-2) 0; font-size: var(--fs-sm); color: var(--ink-2); }
.terms { display: flex; flex-wrap: wrap; gap: var(--sp-2); margin: var(--sp-2) 0; }
.args { max-width: 360px; word-break: break-all; font-family: var(--font-mono); font-size: var(--fs-xs); }
.answer {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: var(--fs-sm);
  line-height: 1.7;
  color: var(--ink);
}
@media (max-width: 760px) {
  .row { grid-template-columns: 1fr; }
  .track { margin-top: 4px; }
}
</style>
