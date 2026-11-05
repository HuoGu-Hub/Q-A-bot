<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { adminApi, describeError } from '@shared/api/client'
import type { EvalCase, EvalDatasetDetail } from '@shared/api/types'
import AdminPage from '@shared/ui/AdminPage.vue'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Button from '@shared/ui/Button.vue'
import Notice from '@shared/ui/Notice.vue'
import Tag from '@shared/ui/Tag.vue'
import Select from '@shared/ui/Select.vue'

const route = useRoute()
const datasetId = Number(route.params.id)
const detail = ref<EvalDatasetDetail | null>(null)
const error = ref('')
const notice = ref('')
const loading = ref(false)
const editing = ref<number | null>(null)
const draft = ref({ question: '', expected: '', note: '', enabled: true })
const adding = ref(false)
const form = ref({ category: 'entity', question: '', expected: '', note: '' })

const CATEGORIES = [
  { value: 'entity', label: '实体' },
  { value: 'colloquial', label: '口语' },
  { value: 'gap', label: '库外' },
  { value: 'chitchat', label: '闲聊' },
  { value: 'descriptive', label: '描述' },
  { value: 'inject', label: '注入' },
  { value: 'leak', label: '泄漏' },
  { value: 'harm', label: '有害' },
  { value: 'privacy', label: '隐私' },
]

const cases = computed(() => detail.value?.cases ?? [])

async function load() {
  loading.value = true
  try {
    detail.value = await adminApi.get<EvalDatasetDetail>(`/eval/datasets/${datasetId}`)
    error.value = ''
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function startEdit(c: EvalCase) {
  editing.value = c.id
  draft.value = { question: c.question, expected: c.expected, note: c.note, enabled: c.enabled }
}

async function save(c: EvalCase) {
  try {
    await adminApi.patch(`/eval/datasets/${datasetId}/cases/${c.id}`, draft.value)
    notice.value = `已更新 ${c.caseKey}`
    editing.value = null
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}

async function remove(c: EvalCase) {
  if (!window.confirm(`删除用例 ${c.caseKey}？历史批次不受影响。`)) return
  try {
    await adminApi.del(`/eval/datasets/${datasetId}/cases/${c.id}`)
    notice.value = `已删除 ${c.caseKey}`
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}

async function add() {
  try {
    await adminApi.post(`/eval/datasets/${datasetId}/cases`, {
      category: form.value.category,
      question: form.value.question,
      expected: form.value.expected,
      note: form.value.note,
    })
    notice.value = '用例已加入'
    form.value = { category: form.value.category, question: '', expected: '', note: '' }
    adding.value = false
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}
</script>

<template>
  <AdminPage width="wide" :title="detail?.dataset.name ?? '用例集'" :count="cases.length">
    <template #actions>
      <Button size="sm" :disabled="loading" @click="load">刷新</Button>
      <Button size="sm" to="/eval">返回评测</Button>
    </template>

    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-if="notice" tone="ok">{{ notice }}</Notice>

    <Panel title="用例" :count="detail?.totalCases ?? 0"
           hint="类别为注入、泄漏、有害、隐私的用例走安全维度，其余走检索">
      <div class="bar">
        <Button size="sm" variant="primary" @click="adding = !adding">新增用例</Button>
      </div>

      <div v-if="adding" class="form">
        <label class="field"><span>类别</span>
          <Select v-model="form.category" :options="CATEGORIES" aria-label="类别" />
        </label>
        <label class="field grow"><span>问题</span><input v-model="form.question" class="inp" /></label>
        <label class="field"><span>期望块</span><input v-model="form.expected" class="inp" placeholder="block-id 或 -" /></label>
        <label class="field"><span>备注</span><input v-model="form.note" class="inp" /></label>
        <Button variant="primary" :disabled="!form.question.trim()" @click="add">加入</Button>
      </div>

      <DataTable :rows="cases.length" label="用例列表" empty="还没有用例">
        <thead>
          <tr>
            <th>key</th><th>类别</th><th>维度</th><th>问题</th><th>期望</th><th>备注</th><th>状态</th><th />
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in cases" :key="c.id">
            <td><code>{{ c.caseKey }}</code></td>
            <td>{{ c.category }}</td>
            <td>{{ c.dimension === 'safety' ? '安全' : '检索' }}</td>
            <template v-if="editing === c.id">
              <td><input v-model="draft.question" class="inp" /></td>
              <td><input v-model="draft.expected" class="inp" /></td>
              <td><input v-model="draft.note" class="inp" /></td>
              <td><input v-model="draft.enabled" type="checkbox" /></td>
              <td class="ops">
                <Button size="sm" variant="primary" @click="save(c)">保存</Button>
                <Button size="sm" @click="editing = null">取消</Button>
              </td>
            </template>
            <template v-else>
              <td class="q">{{ c.question }}</td>
              <td><code>{{ c.expected || '—' }}</code></td>
              <td>{{ c.note || '—' }}</td>
              <td><Tag :tone="c.enabled ? 'good' : 'neutral'">{{ c.enabled ? '启用' : '停用' }}</Tag></td>
              <td class="ops">
                <Button size="sm" @click="startEdit(c)">编辑</Button>
                <Button size="sm" variant="danger" @click="remove(c)">删除</Button>
              </td>
            </template>
          </tr>
        </tbody>
      </DataTable>
    </Panel>

    <Panel v-if="detail" title="版本" :count="detail.versions.length">
      <DataTable :rows="detail.versions.length" label="版本" empty="还没有冻结版本">
        <thead><tr><th class="num">版本</th><th class="num">用例</th><th>校验</th><th>冻结时间</th></tr></thead>
        <tbody>
          <tr v-for="v in detail.versions" :key="v.id">
            <td class="num">v{{ v.version }}</td>
            <td class="num">{{ v.caseCount }}</td>
            <td><code>{{ v.checksum?.slice(0, 12) || '—' }}</code></td>
            <td>{{ v.frozenAt?.slice(0, 16).replace('T', ' ') || '—' }}</td>
          </tr>
        </tbody>
      </DataTable>
    </Panel>
  </AdminPage>
</template>

<style scoped>
.bar { display: flex; gap: var(--sp-2); margin-bottom: var(--sp-3); }
.form { display: flex; flex-wrap: wrap; gap: var(--sp-3); align-items: flex-end; margin-bottom: var(--sp-3); }
.field { display: flex; flex-direction: column; gap: 4px; min-width: 150px; }
.field.grow { flex: 1; min-width: 240px; }
.field span { font-size: var(--fs-xs); color: var(--ink-3); }
.inp {
  width: 100%;
  padding: 6px 9px;
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  background: var(--stone-400);
  color: var(--ink);
  font: inherit;
  font-size: var(--fs-sm);
}
.q { max-width: 320px; }
.ops { display: flex; gap: var(--sp-2); white-space: nowrap; }
</style>
