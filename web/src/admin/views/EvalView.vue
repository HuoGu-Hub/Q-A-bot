<script setup lang="ts">
/**
 * 评测 —— 批次与用例集。
 *
 * <p>一屏只回答两个问题：跑过哪些批次、它们的结论是什么；用例集有多少条、冻到第几版。
 * 单条用例的过程不在这里展开，点进去看 Trace —— 那是另一个问题，
 * 混在同一屏会让"这一批整体怎么样"永远被单条明细挤下去。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi, describeError } from '@shared/api/client'
import type { EvalBatch, EvalDataset, EvalImportResult } from '@shared/api/types'
import AdminPage from '@shared/ui/AdminPage.vue'
import Tabs from '@shared/ui/Tabs.vue'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Button from '@shared/ui/Button.vue'
import Select from '@shared/ui/Select.vue'
import Notice from '@shared/ui/Notice.vue'
import Tag from '@shared/ui/Tag.vue'
import Empty from '@shared/ui/Empty.vue'

const router = useRouter()

type Tab = 'batches' | 'datasets'
const tab = ref<Tab>('batches')
const TABS = [
  { key: 'batches', label: '批次' },
  { key: 'datasets', label: '用例集' },
]

const loading = ref(false)
const error = ref('')
const notice = ref('')
const batches = ref<EvalBatch[]>([])
const datasets = ref<EvalDataset[]>([])

const form = ref({ datasetId: '', name: '', repeatTimes: '1', sampleRate: '1' })
const creating = ref(false)
const importTarget = ref('')
const tsv = ref('')
const importing = ref(false)
const importResult = ref<EvalImportResult | null>(null)

const datasetOptions = computed(() =>
  datasets.value.map(d => ({ value: String(d.id), label: `${d.name} · ${d.caseCount} 条 · ${d.versionCount} 版` })))

const canCreate = computed(() => !!form.value.datasetId && datasets.value
  .some(d => String(d.id) === form.value.datasetId && d.versionCount > 0))

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const [b, d] = await Promise.all([
      adminApi.get<EvalBatch[]>('/eval/batches?limit=50'),
      adminApi.get<EvalDataset[]>('/eval/datasets'),
    ])
    batches.value = b
    datasets.value = d
    if (!form.value.datasetId && d.length > 0) form.value.datasetId = String(d[0].id)
    if (!importTarget.value && d.length > 0) importTarget.value = String(d[0].id)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(refresh)

async function createBatch() {
  if (!canCreate.value) return
  creating.value = true
  error.value = ''
  try {
    const res = await adminApi.post<{ id: number }>('/eval/batches', {
      name: form.value.name || undefined,
      datasetId: Number(form.value.datasetId),
      repeatTimes: Number(form.value.repeatTimes),
      concurrency: 2,
      sampleRate: Number(form.value.sampleRate),
    })
    router.push(`/eval/batches/${res.id}`)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    creating.value = false
  }
}

async function doImport(dryRun: boolean) {
  if (!importTarget.value || !tsv.value.trim()) return
  importing.value = true
  error.value = ''
  importResult.value = null
  try {
    importResult.value = await adminApi.post<EvalImportResult>(
      `/eval/datasets/${importTarget.value}/actions/import`, { content: tsv.value, dryRun })
    if (!dryRun) {
      notice.value = `已导入 ${importResult.value.accepted} 条，拒绝 ${importResult.value.rejected} 行`
      await refresh()
    }
  } catch (e) {
    error.value = describeError(e)
  } finally {
    importing.value = false
  }
}

async function freeze(datasetId: number) {
  error.value = ''
  try {
    const v = await adminApi.post<{ version: number }>(`/eval/datasets/${datasetId}/actions/freeze`)
    notice.value = `已冻结版本 v${v.version}`
    await refresh()
  } catch (e) {
    error.value = describeError(e)
  }
}

function openBatch(id: number) {
  router.push(`/eval/batches/${id}`)
}

function batchTone(status: string): 'good' | 'bad' | 'warn' | 'neutral' {
  if (status === 'completed') return 'good'
  if (status === 'failed') return 'bad'
  if (status === 'running' || status === 'queued') return 'warn'
  return 'neutral'
}

function gateTone(gate: string): 'good' | 'bad' | 'warn' | 'neutral' {
  if (gate === 'passed') return 'good'
  if (gate === 'blocked') return 'bad'
  if (gate === 'warned') return 'warn'
  return 'neutral'
}

function gateLabel(gate: string): string {
  return ({ passed: '通过', warned: '警告', blocked: '阻断', pending: '待判定' } as Record<string, string>)[gate] ?? gate
}

function when(value: string | null): string {
  return value ? value.slice(0, 19).replace('T', ' ') : '—'
}
</script>

<template>
  <AdminPage width="wide">
    <template #tabs>
      <Tabs :tabs="TABS" :model-value="tab" @update:model-value="(v: string) => (tab = v as Tab)" />
    </template>
    <template #actions>
      <Button size="sm" :disabled="loading" @click="refresh">刷新</Button>
    </template>

    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-if="notice" tone="ok">{{ notice }}</Notice>

    <template v-if="tab === 'batches'">
      <Panel title="批次" :count="batches.length" hint="按创建时间倒序">
        <DataTable :rows="batches.length" label="评测批次"
                   empty="还没有批次" empty-hint="选一个用例集，建第一批">
          <thead>
            <tr>
              <th>批次</th>
              <th>状态</th>
              <th>门禁</th>
              <th class="num">用例</th>
              <th class="num">重复</th>
              <th>开始</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in batches" :key="b.id">
              <td>{{ b.name }}</td>
              <td><Tag :tone="batchTone(b.status)">{{ b.status }}</Tag></td>
              <td><Tag :tone="gateTone(b.gateStatus)">{{ gateLabel(b.gateStatus) }}</Tag></td>
              <td class="num">{{ b.itemTotal }}</td>
              <td class="num">{{ b.repeatTimes }}</td>
              <td>{{ when(b.startedAt) }}</td>
              <td><Button size="sm" @click="openBatch(b.id)">打开</Button></td>
            </tr>
          </tbody>
        </DataTable>
      </Panel>

      <Panel title="新建批次" hint="目标快照在建批时固定，执行期不随线上配置漂移">
        <div class="form">
          <label class="field">
            <span>用例集</span>
            <Select v-model="form.datasetId" :options="datasetOptions" aria-label="用例集" />
          </label>
          <label class="field">
            <span>名称</span>
            <input v-model="form.name" class="inp" placeholder="留空自动生成" />
          </label>
          <label class="field narrow">
            <span>重复次数</span>
            <input v-model="form.repeatTimes" class="inp num" type="number" min="1" max="5" />
          </label>
          <label class="field narrow">
            <span>采样率</span>
            <input v-model="form.sampleRate" class="inp num" type="number" min="0.05" max="1" step="0.05" />
          </label>
          <Button variant="primary" :disabled="!canCreate || creating" @click="createBatch">
            {{ creating ? '创建中…' : '建批并执行' }}
          </Button>
        </div>
        <p v-if="!canCreate" class="hint">选中的用例集还没有冻结版本，先在下面冻结一次。</p>
      </Panel>
    </template>

    <template v-else>
      <Panel title="用例集" :count="datasets.length">
        <DataTable :rows="datasets.length" label="评测用例集"
                   empty="还没有用例集" empty-hint="导入 TSV 或等待首次启动自动导入">
          <thead>
            <tr>
              <th>名称</th>
              <th>标识</th>
              <th>状态</th>
              <th class="num">用例</th>
              <th class="num">版本</th>
              <th>创建</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in datasets" :key="d.id">
              <td>{{ d.name }}</td>
              <td><code>{{ d.slug }}</code></td>
              <td><Tag :tone="d.status === 'frozen' ? 'good' : 'neutral'">{{ d.status }}</Tag></td>
              <td class="num">{{ d.caseCount }}</td>
              <td class="num">{{ d.versionCount }}</td>
              <td>{{ when(d.createdAt) }}</td>
              <td><Button size="sm" :disabled="d.caseCount === 0" @click="freeze(d.id)">冻结</Button></td>
            </tr>
          </tbody>
        </DataTable>
      </Panel>

      <Panel title="导入 TSV" hint="类别、问题、期望块、备注，与 tests/golden/retrieval-golden.tsv 同格式">
        <div class="form">
          <label class="field">
            <span>目标用例集</span>
            <Select v-model="importTarget" :options="datasetOptions" aria-label="目标用例集" />
          </label>
        </div>
        <textarea v-model="tsv" class="tsv" rows="6"
                  placeholder="entity&#9;废料杯在哪&#9;block-1;block-2&#9;俗称" />
        <div class="form">
          <Button :disabled="importing || !tsv.trim()" @click="doImport(true)">预检</Button>
          <Button variant="primary" :disabled="importing || !tsv.trim()" @click="doImport(false)">导入</Button>
        </div>
        <div v-if="importResult" class="result">
          <p>
            共 {{ importResult.total }} 行，可接受 {{ importResult.accepted }}，
            拒绝 {{ importResult.rejected }}{{ importResult.dryRun ? '（预检，未写库）' : '' }}
          </p>
          <ul v-if="importResult.errors.length">
            <li v-for="e in importResult.errors" :key="e">{{ e }}</li>
          </ul>
        </div>
      </Panel>
    </template>

    <Empty v-if="!loading && tab === 'batches' && batches.length === 0 && datasets.length === 0"
           title="评测还没有数据" hint="首次启动会尝试导入检索黄金集；也可以在上面手动导入" />
  </AdminPage>
</template>

<style scoped>
.form { display: flex; flex-wrap: wrap; align-items: flex-end; gap: var(--sp-3); }
.field { display: flex; flex-direction: column; gap: 4px; min-width: 220px; }
.field.narrow { min-width: 110px; }
.field span { font-size: var(--fs-xs); color: var(--ink-3); }
.inp {
  padding: 6px 9px;
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  background: var(--stone-400);
  color: var(--ink);
  font: inherit;
  font-size: var(--fs-sm);
}
.tsv {
  width: 100%;
  margin: var(--sp-3) 0;
  padding: var(--sp-3);
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  background: var(--stone-400);
  color: var(--ink);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  resize: vertical;
}
.hint { margin: var(--sp-2) 0 0; font-size: var(--fs-xs); color: var(--ink-3); }
.result { margin-top: var(--sp-3); font-size: var(--fs-sm); }
.result ul { margin: 4px 0 0; padding-left: 18px; color: var(--ink-2); }
</style>
