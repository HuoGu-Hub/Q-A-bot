<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { EvalAlertEvent, EvalAlertRule } from '@shared/api/types'
import AdminPage from '@shared/ui/AdminPage.vue'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Button from '@shared/ui/Button.vue'
import Notice from '@shared/ui/Notice.vue'
import Tag from '@shared/ui/Tag.vue'
import Select from '@shared/ui/Select.vue'

const rules = ref<EvalAlertRule[]>([])
const events = ref<EvalAlertEvent[]>([])
const error = ref('')
const notice = ref('')
const loading = ref(false)
const form = ref({ name: '', metric: 'passRate', comparator: 'lt', threshold: '0.8', severity: 'warn' })

const METRICS = [
  { value: 'passRate', label: '通过率 0~1' },
  { value: 'costPerRun', label: '单条成本 微元' },
  { value: 'p95Ms', label: 'P95 延迟 毫秒' },
]
const COMPARATORS = [
  { value: 'lt', label: '低于' },
  { value: 'lte', label: '不高于' },
  { value: 'gt', label: '高于' },
  { value: 'gte', label: '不低于' },
]
const SEVERITIES = [
  { value: 'warn', label: '警告' },
  { value: 'block', label: '阻断' },
]

async function load() {
  loading.value = true
  try {
    const [r, e] = await Promise.all([
      adminApi.get<EvalAlertRule[]>('/eval/alerts/rules'),
      adminApi.get<EvalAlertEvent[]>('/eval/alerts/events?limit=50'),
    ])
    rules.value = r
    events.value = e
    error.value = ''
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function create() {
  try {
    await adminApi.post('/eval/alerts/rules', {
      name: form.value.name,
      metric: form.value.metric,
      comparator: form.value.comparator,
      threshold: Number(form.value.threshold),
      severity: form.value.severity,
    })
    notice.value = '规则已保存，下一批跑完开始生效'
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}

async function toggle(rule: EvalAlertRule) {
  try {
    await adminApi.post(`/eval/alerts/rules/${rule.id}/actions/${rule.enabled ? 'disable' : 'enable'}`)
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}

async function ack(event: EvalAlertEvent) {
  try {
    await adminApi.post(`/eval/alerts/events/${event.id}/actions/ack`, { note: '已在页面处置' })
    notice.value = '事件已处置'
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}

function when(value: string | null): string {
  return value ? value.slice(0, 16).replace('T', ' ') : '—'
}

function metricLabel(metric: string): string {
  return METRICS.find(m => m.value === metric)?.label ?? metric
}

function severityTone(s: string): 'bad' | 'warn' {
  return s === 'block' ? 'bad' : 'warn'
}
</script>

<template>
  <AdminPage width="wide" title="告警" :count="events.filter(e => e.status === 'open').length">
    <template #actions>
      <Button size="sm" :disabled="loading" @click="load">刷新</Button>
      <Button size="sm" to="/eval">返回评测</Button>
    </template>

    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-if="notice" tone="ok">{{ notice }}</Notice>

    <Panel title="规则" :count="rules.length" hint="关闭的规则不再触发，历史事件保留">
      <DataTable :rows="rules.length" label="告警规则" empty="还没有规则">
        <thead>
          <tr><th>名称</th><th>指标</th><th>条件</th><th>级别</th><th>状态</th><th /></tr>
        </thead>
        <tbody>
          <tr v-for="r in rules" :key="r.id">
            <td>{{ r.name }}</td>
            <td>{{ metricLabel(r.metric) }}</td>
            <td>{{ r.comparator }} {{ r.threshold }}</td>
            <td><Tag :tone="severityTone(r.severity)">{{ r.severity }}</Tag></td>
            <td>{{ r.enabled ? '启用' : '停用' }}</td>
            <td><Button size="sm" @click="toggle(r)">{{ r.enabled ? '停用' : '启用' }}</Button></td>
          </tr>
        </tbody>
      </DataTable>
    </Panel>

    <Panel title="新建规则">
      <div class="form">
        <label class="field"><span>名称</span><input v-model="form.name" class="inp" /></label>
        <label class="field"><span>指标</span>
          <Select v-model="form.metric" :options="METRICS" aria-label="指标" />
        </label>
        <label class="field narrow"><span>条件</span>
          <Select v-model="form.comparator" :options="COMPARATORS" aria-label="条件" />
        </label>
        <label class="field narrow"><span>阈值</span><input v-model="form.threshold" class="inp num" type="number" /></label>
        <label class="field narrow"><span>级别</span>
          <Select v-model="form.severity" :options="SEVERITIES" aria-label="级别" />
        </label>
        <Button variant="primary" :disabled="!form.name.trim()" @click="create">保存</Button>
      </div>
    </Panel>

    <Panel title="事件" :count="events.length">
      <DataTable :rows="events.length" label="告警事件"
                 empty="没有告警" empty-hint="规则命中时自动生成">
        <thead>
          <tr><th>规则</th><th>批次</th><th class="num">实际值</th><th class="num">阈值</th><th>状态</th><th>时间</th><th /></tr>
        </thead>
        <tbody>
          <tr v-for="e in events" :key="e.id">
            <td>{{ e.ruleName }}</td>
            <td>#{{ e.batchId }}</td>
            <td class="num">{{ e.metricValue.toFixed(3) }}</td>
            <td class="num">{{ e.threshold }}</td>
            <td><Tag :tone="e.status === 'open' ? 'bad' : 'neutral'">{{ e.status }}</Tag></td>
            <td>{{ when(e.createdAt) }}</td>
            <td><Button size="sm" :disabled="e.status !== 'open'" @click="ack(e)">处置</Button></td>
          </tr>
        </tbody>
      </DataTable>
    </Panel>
  </AdminPage>
</template>

<style scoped>
.form { display: flex; flex-wrap: wrap; gap: var(--sp-3); align-items: flex-end; }
.field { display: flex; flex-direction: column; gap: 4px; min-width: 180px; }
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
</style>
