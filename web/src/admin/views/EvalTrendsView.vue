<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type {
  EvalBatch, EvalCompareResult, EvalDataset, EvalTrendPoint,
} from '@shared/api/types'
import AdminPage from '@shared/ui/AdminPage.vue'
import Tabs from '@shared/ui/Tabs.vue'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Button from '@shared/ui/Button.vue'
import Select from '@shared/ui/Select.vue'
import Notice from '@shared/ui/Notice.vue'
import Tag from '@shared/ui/Tag.vue'
import Empty from '@shared/ui/Empty.vue'

const tab = ref<'trends' | 'compare'>('trends')
const TABS = [
  { key: 'trends', label: '趋势' },
  { key: 'compare', label: '版本对比' },
]

const datasets = ref<EvalDataset[]>([])
const batches = ref<EvalBatch[]>([])
const datasetId = ref('')
const leftId = ref('')
const rightId = ref('')
const points = ref<EvalTrendPoint[]>([])
const result = ref<EvalCompareResult | null>(null)
const loading = ref(false)
const error = ref('')

const datasetOptions = computed(() =>
  datasets.value.map(d => ({ value: String(d.id), label: `${d.name} · ${d.caseCount} 条` })))
const batchOptions = computed(() =>
  batches.value.map(b => ({ value: String(b.id), label: `#${b.id} ${b.name}` })))

const barWidth = (rate: number) => `${Math.max(2, Math.round(rate * 100))}%`

async function load() {
  loading.value = true
  try {
    const [d, b] = await Promise.all([
      adminApi.get<EvalDataset[]>('/eval/datasets'),
      adminApi.get<EvalBatch[]>('/eval/batches?limit=100'),
    ])
    datasets.value = d
    batches.value = b.filter(x => x.status === 'completed')
    if (!datasetId.value && d.length) datasetId.value = String(d[0].id)
    if (!leftId.value && batches.value.length) leftId.value = String(batches.value[0].id)
    if (!rightId.value && batches.value.length > 1) rightId.value = String(batches.value[1].id)
    await Promise.all([loadTrends(), loadCompare()])
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function loadTrends() {
  if (!datasetId.value) return
  points.value = await adminApi.get<EvalTrendPoint[]>(`/eval/trends?datasetId=${datasetId.value}&limit=30`)
}

async function loadCompare() {
  if (!leftId.value || !rightId.value) return
  result.value = await adminApi.get<EvalCompareResult>(
    `/eval/compare?left=${leftId.value}&right=${rightId.value}`)
}

onMounted(load)

function onDataset(v: string) {
  datasetId.value = v
  loadTrends().catch(e => (error.value = describeError(e)))
}

function onSide(which: 'left' | 'right', v: string) {
  if (which === 'left') leftId.value = v
  else rightId.value = v
  loadCompare().catch(e => (error.value = describeError(e)))
}

function when(value: string): string {
  return value ? value.slice(5, 16).replace('T', ' ') : '—'
}

function gateTone(gate: string): 'good' | 'warn' | 'bad' | 'neutral' {
  if (gate === 'passed') return 'good'
  if (gate === 'warned') return 'warn'
  if (gate === 'blocked') return 'bad'
  return 'neutral'
}
</script>

<template>
  <AdminPage width="wide" title="趋势与对比">
    <template #tabs>
      <Tabs :tabs="TABS" :model-value="tab" @update:model-value="(v: string) => (tab = v as 'trends' | 'compare')" />
    </template>
    <template #actions>
      <Button size="sm" :disabled="loading" @click="load">刷新</Button>
      <Button size="sm" to="/eval">返回评测</Button>
    </template>

    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <template v-if="tab === 'trends'">
      <Panel title="用例集趋势" hint="只统计已完成的批次">
        <div class="bar">
          <Select :model-value="datasetId" :options="datasetOptions" aria-label="用例集"
                  @update:model-value="onDataset" />
        </div>
        <DataTable :rows="points.length" label="批次趋势" empty="这个用例集还没有完成的批次">
          <thead>
            <tr>
              <th>批次</th>
              <th>时间</th>
              <th>通过率</th>
              <th class="num">判定</th>
              <th class="num">P95</th>
              <th>门禁</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in points" :key="p.batchId">
              <td>{{ p.name }}</td>
              <td>{{ when(p.createdAt) }}</td>
              <td class="rate">
                <span class="track"><span class="fill" :style="{ width: barWidth(p.passRate) }" /></span>
                <span class="num">{{ (p.passRate * 100).toFixed(1) }}%</span>
              </td>
              <td class="num">{{ p.judged }}</td>
              <td class="num">{{ p.p95Ms }} ms</td>
              <td><Tag :tone="gateTone(p.gateStatus)">{{ p.gateStatus }}</Tag></td>
            </tr>
          </tbody>
        </DataTable>
      </Panel>
    </template>

    <template v-else>
      <Panel title="选择两次运行" hint="同用例集、同冻结版本才有可比性">
        <div class="bar">
          <Select :model-value="leftId" :options="batchOptions" aria-label="基线批次"
                  @update:model-value="(v: string) => onSide('left', v)" />
          <span class="vs">对比</span>
          <Select :model-value="rightId" :options="batchOptions" aria-label="对比批次"
                  @update:model-value="(v: string) => onSide('right', v)" />
        </div>
      </Panel>

      <template v-if="result">
        <Panel title="结论">
          <div class="kpis">
            <div><span>左通过率</span><b class="num">{{ (result.left.passRate * 100).toFixed(1) }}%</b></div>
            <div><span>右通过率</span><b class="num">{{ (result.right.passRate * 100).toFixed(1) }}%</b></div>
            <div><span>变好</span><b class="num good">{{ result.improved }}</b></div>
            <div><span>变差</span><b class="num bad">{{ result.regressed }}</b></div>
            <div><span>一致</span><b class="num">{{ result.same }}</b></div>
          </div>
          <p v-if="!result.sameDataset" class="warn">两次不是同一个用例集，只能按 case_key 交集比。</p>
          <p v-else-if="!result.sameVersion" class="warn">两次不是同一个冻结版本，用例内容可能已经变过。</p>
        </Panel>

        <Panel title="发生变化的用例" :count="result.changes.length">
          <DataTable :rows="result.changes.length" label="差异用例"
                     empty="两次结论完全一致">
            <thead>
              <tr><th>用例</th><th>问题</th><th>左</th><th>右</th><th>标签</th></tr>
            </thead>
            <tbody>
              <tr v-for="c in result.changes" :key="c.caseKey">
                <td><code>{{ c.caseKey }}</code></td>
                <td class="q">{{ c.question }}</td>
                <td>{{ c.left }}</td>
                <td>{{ c.right }}</td>
                <td>{{ c.tag ?? '—' }}</td>
              </tr>
            </tbody>
          </DataTable>
        </Panel>
      </template>
      <Empty v-else title="还没有可对比的批次" hint="至少需要两个已完成的批次" />
    </template>
  </AdminPage>
</template>

<style scoped>
.bar { display: flex; flex-wrap: wrap; align-items: center; gap: var(--sp-3); margin-bottom: var(--sp-3); }
.vs { color: var(--ink-3); font-size: var(--fs-sm); }
.kpis { display: flex; flex-wrap: wrap; gap: var(--sp-3); }
.kpis div { border: 1px solid var(--edge); border-radius: var(--r-md); padding: 8px 14px; min-width: 110px; }
.kpis span { display: block; font-size: var(--fs-xs); color: var(--ink-3); }
.kpis b { font-size: 1.3em; }
.good { color: var(--vital); }
.bad { color: var(--blight); }
.warn { margin-top: var(--sp-3); color: var(--warn); font-size: var(--fs-sm); }
.rate { display: flex; align-items: center; gap: var(--sp-2); min-width: 180px; }
.track { flex: 1; height: 8px; background: var(--stone-400); border-radius: var(--r-pill); overflow: hidden; }
.fill { display: block; height: 100%; background: var(--ember); border-radius: var(--r-pill); }
.q { max-width: 320px; }
</style>
