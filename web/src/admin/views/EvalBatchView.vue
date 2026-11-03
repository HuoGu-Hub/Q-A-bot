<script setup lang="ts">
/**
 * 单批次结果 —— 这一屏回答"这批整体怎么样"。
 *
 * <p>执行中每 3 秒轮询一次；批次跑完自动停。停止轮询的条件写在状态里，
 * 不靠"用户会不会切走"——切走再回来是另一个页面实例，定时器跟随组件生命周期。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminApi, describeError } from '@shared/api/client'
import type { EvalBatchDetail, EvalItem } from '@shared/api/types'
import AdminPage from '@shared/ui/AdminPage.vue'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Button from '@shared/ui/Button.vue'
import Notice from '@shared/ui/Notice.vue'
import Tag from '@shared/ui/Tag.vue'
import Stat from '@shared/ui/Stat.vue'
import Empty from '@shared/ui/Empty.vue'

const route = useRoute()
const router = useRouter()
const batchId = Number(route.params.id)

const detail = ref<EvalBatchDetail | null>(null)
const loading = ref(false)
const error = ref('')
let timer: number | null = null

const running = computed(() => {
  const s = detail.value?.batch.status
  return s === 'queued' || s === 'running'
})

const gateTone = computed(() => {
  const g = detail.value?.batch.gateStatus
  if (g === 'passed') return 'good'
  if (g === 'warned') return 'warn'
  if (g === 'blocked') return 'bad'
  return 'neutral'
})
const gateLabel = computed(() => {
  const g = detail.value?.batch.gateStatus
  return ({ passed: '通过', warned: '警告', blocked: '阻断', pending: '待判定' } as Record<string, string>)[g ?? ''] ?? g ?? ''
})

const rateText = computed(() => {
  const s = detail.value?.summary
  return s ? `${(s.passRate * 100).toFixed(1)}%` : '—'
})

async function load() {
  loading.value = true
  try {
    detail.value = await adminApi.get<EvalBatchDetail>(`/eval/batches/${batchId}?itemLimit=500`)
    error.value = ''
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

function schedule() {
  if (timer !== null) window.clearTimeout(timer)
  if (!running.value) return
  timer = window.setTimeout(async () => {
    await load()
    schedule()
  }, 3000)
}

onMounted(async () => {
  await load()
  schedule()
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearTimeout(timer)
})

async function clone() {
  try {
    const res = await adminApi.post<{ id: number }>(`/eval/batches/${batchId}/actions/clone`)
    router.push(`/eval/batches/${res.id}`)
  } catch (e) {
    error.value = describeError(e)
  }
}

function openRun(item: EvalItem) {
  if (item.runId != null) router.push(`/eval/runs/${item.runId}`)
}

function verdictTone(verdict: string | null): 'good' | 'bad' | 'neutral' | 'warn' {
  if (verdict === 'PASS') return 'good'
  if (verdict === 'FAIL') return 'bad'
  if (verdict === 'SKIP') return 'warn'
  return 'neutral'
}

function when(value: string | null | undefined): string {
  return value ? value.slice(0, 19).replace('T', ' ') : '—'
}

const DIM_LABEL: Record<string, string> = {
  quality: '结果质量',
  rag: 'RAG 检索',
  stability: '稳定性',
  cost: '成本效率',
  safety: '安全限制',
}
</script>

<template>
  <AdminPage width="wide" :title="detail ? detail.batch.name : '批次'" :count="detail?.batch.itemTotal ?? ''">
    <template #actions>
      <Button size="sm" :disabled="loading" @click="load">刷新</Button>
      <Button size="sm" @click="clone">克隆批次</Button>
      <Button size="sm" to="/eval">返回列表</Button>
    </template>

    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Empty v-if="!detail && !loading" title="批次不存在" />

    <template v-if="detail">
      <div class="kpis">
        <Stat label="通过率" :value="rateText" tone="flame" hint="已判定用例中通过的比例，SKIP 不进分母" />
        <Stat label="已判定" :value="detail.summary.judged" tone="mist" />
        <Stat label="通过" :value="detail.summary.pass" tone="moss" />
        <Stat label="失败" :value="detail.summary.fail" tone="rust" />
        <Stat label="未判定" :value="detail.summary.skip" tone="mist" />
        <Stat label="P50" :value="`${detail.summary.p50Ms} ms`" tone="mist" />
        <Stat label="P95" :value="`${detail.summary.p95Ms} ms`" tone="mist" />
      </div>

      <Panel title="批次信息">
        <div class="meta">
          <span>状态 <Tag :tone="running ? 'warn' : detail.batch.status === 'completed' ? 'good' : 'neutral'">{{ detail.batch.status }}</Tag></span>
          <span>门禁 <Tag :tone="gateTone">{{ gateLabel }}</Tag></span>
          <span>用例 {{ detail.batch.itemTotal }}</span>
          <span>重复 {{ detail.batch.repeatTimes }}</span>
          <span>开始 {{ when(detail.batch.startedAt) }}</span>
          <span>结束 {{ when(detail.batch.finishedAt) }}</span>
        </div>
      </Panel>

      <Panel title="维度" hint="PASS 与 FAIL 才计入判定；SKIP 表示该维度还没有判定来源">
        <DataTable :rows="detail.summary.dimensions.length" label="维度结论">
          <thead>
            <tr><th>维度</th><th class="num">通过</th><th class="num">失败</th><th class="num">跳过</th></tr>
          </thead>
          <tbody>
            <tr v-for="d in detail.summary.dimensions" :key="d.dimension">
              <td>{{ DIM_LABEL[d.dimension] ?? d.dimension }}</td>
              <td class="num">{{ d.pass }}</td>
              <td class="num">{{ d.fail }}</td>
              <td class="num">{{ d.skip }}</td>
            </tr>
          </tbody>
        </DataTable>
      </Panel>

      <Panel v-if="detail.summary.tags.length" title="失败标签" hint="按出现次数倒序">
        <div class="tags">
          <Tag v-for="t in detail.summary.tags" :key="t.tag" tone="bad">{{ t.tag }} · {{ t.count }}</Tag>
        </div>
      </Panel>

      <Panel title="用例" :count="detail.items.length">
        <DataTable :rows="detail.items.length" label="批次用例"
                   empty="还没有执行结果" empty-hint="批次刚入队时这一屏是空的">
          <thead>
            <tr>
              <th>用例</th>
              <th>类别</th>
              <th>问题</th>
              <th>结论</th>
              <th>标签</th>
              <th class="num">耗时</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="i in detail.items" :key="i.id">
              <td><code>{{ i.caseKey }}</code></td>
              <td>{{ i.category }}</td>
              <td class="q">{{ i.question }}</td>
              <td><Tag :tone="verdictTone(i.verdict)">{{ i.verdict ?? i.status }}</Tag></td>
              <td>{{ i.tag ?? '—' }}</td>
              <td class="num">{{ i.latencyMs == null ? '—' : `${i.latencyMs} ms` }}</td>
              <td>
                <Button size="sm" :disabled="i.runId == null" @click="openRun(i)">过程</Button>
              </td>
            </tr>
          </tbody>
        </DataTable>
      </Panel>
    </template>
  </AdminPage>
</template>

<style scoped>
.kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(132px, 1fr));
  gap: var(--sp-3);
}
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-4);
  font-size: var(--fs-sm);
  color: var(--ink-2);
}
.meta span { display: inline-flex; align-items: center; gap: 6px; }
.tags { display: flex; flex-wrap: wrap; gap: var(--sp-2); }
.q { max-width: 360px; }
</style>
