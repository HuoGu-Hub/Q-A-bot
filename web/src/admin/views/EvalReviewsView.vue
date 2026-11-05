<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi, describeError } from '@shared/api/client'
import type { EvalReview } from '@shared/api/types'
import AdminPage from '@shared/ui/AdminPage.vue'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Button from '@shared/ui/Button.vue'
import Notice from '@shared/ui/Notice.vue'
import Tag from '@shared/ui/Tag.vue'
import Select from '@shared/ui/Select.vue'
import Empty from '@shared/ui/Empty.vue'

const router = useRouter()
const rows = ref<EvalReview[]>([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const active = ref<number | null>(null)
const verdict = ref('PASS')
const note = ref('')

const VERDICTS = [
  { value: 'PASS', label: '通过' },
  { value: 'FAIL', label: '失败' },
]

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.get<EvalReview[]>('/eval/reviews?status=pending&limit=100')
    error.value = ''
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function claim(r: EvalReview) {
  try {
    await adminApi.post(`/eval/reviews/${r.id}/actions/claim`)
    active.value = r.id
    notice.value = ''
  } catch (e) {
    error.value = describeError(e)
  }
}

async function submit(r: EvalReview) {
  try {
    await adminApi.post(`/eval/reviews/${r.id}/actions/submit`, {
      verdict: verdict.value,
      note: note.value,
    })
    notice.value = `已提交 ${r.caseKey} 的复核结论`
    active.value = null
    note.value = ''
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}

function tone(priority: string): 'bad' | 'warn' | 'neutral' {
  if (priority === 'urgent') return 'bad'
  if (priority === 'high') return 'warn'
  return 'neutral'
}

function when(value: string | null): string {
  return value ? value.slice(0, 16).replace('T', ' ') : '—'
}
</script>

<template>
  <AdminPage width="wide" title="复核队列" :count="rows.length">
    <template #actions>
      <Button size="sm" :disabled="loading" @click="load">刷新</Button>
      <Button size="sm" to="/eval">返回评测</Button>
    </template>

    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-if="notice" tone="ok">{{ notice }}</Notice>

    <Panel title="待处理" :count="rows.length" hint="紧急项当日处理，其余 72 小时内">
      <DataTable :rows="rows.length" label="复核任务"
                 empty="没有待复核的任务" empty-hint="自动判定失败时会自动入队">
        <thead>
          <tr>
            <th>用例</th>
            <th>问题</th>
            <th>原因</th>
            <th>优先级</th>
            <th>状态</th>
            <th>截止</th>
            <th />
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.id">
            <td><code>{{ r.caseKey }}</code></td>
            <td class="q">{{ r.question }}</td>
            <td class="q">{{ r.reason }}</td>
            <td><Tag :tone="tone(r.priority)">{{ r.priority }}</Tag></td>
            <td>{{ r.status }}</td>
            <td>{{ when(r.dueAt) }}</td>
            <td class="ops">
              <Button size="sm" @click="router.push(`/eval/runs/${r.runId}`)">过程</Button>
              <Button v-if="r.status === 'pending'" size="sm" @click="claim(r)">认领</Button>
            </td>
          </tr>
          <tr v-if="active !== null" class="editor">
            <td colspan="7">
              <div class="form">
                <Select v-model="verdict" :options="VERDICTS" aria-label="复核结论" />
                <input v-model="note" class="inp" placeholder="理由，必填一句" />
                <Button variant="primary" :disabled="!note.trim()"
                        @click="submit(rows.find(x => x.id === active)!)">提交结论</Button>
                <Button @click="active = null">取消</Button>
              </div>
            </td>
          </tr>
        </tbody>
      </DataTable>
    </Panel>

    <Empty v-if="!loading && rows.length === 0" title="队列是空的"
           hint="质量类自动判定失败或重复运行结论不一致时会入队" />
  </AdminPage>
</template>

<style scoped>
.q { max-width: 300px; }
.ops { display: flex; gap: var(--sp-2); }
.form { display: flex; flex-wrap: wrap; gap: var(--sp-2); align-items: center; }
.inp {
  flex: 1;
  min-width: 220px;
  padding: 6px 9px;
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  background: var(--stone-400);
  color: var(--ink);
  font: inherit;
  font-size: var(--fs-sm);
}
.editor td { background: var(--stone-400); }
</style>
