<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { EvalPrice } from '@shared/api/types'
import AdminPage from '@shared/ui/AdminPage.vue'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Button from '@shared/ui/Button.vue'
import Notice from '@shared/ui/Notice.vue'

const rows = ref<EvalPrice[]>([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const form = ref({ provider: '', model: '', input: '', output: '', currency: 'CNY' })

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.get<EvalPrice[]>('/eval/prices')
    error.value = ''
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function save() {
  try {
    await adminApi.put('/eval/prices', {
      provider: form.value.provider,
      model: form.value.model,
      inputPerMillion: Number(form.value.input),
      outputPerMillion: Number(form.value.output),
      currency: form.value.currency,
    })
    notice.value = `已保存 ${form.value.model} 的价目`
    await load()
  } catch (e) {
    error.value = describeError(e)
  }
}

function when(value: string): string {
  return value ? value.slice(0, 16).replace('T', ' ') : '—'
}
</script>

<template>
  <AdminPage width="wide" title="成本价目" :count="rows.length">
    <template #actions>
      <Button size="sm" :disabled="loading" @click="load">刷新</Button>
      <Button size="sm" to="/eval">返回评测</Button>
    </template>

    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-if="notice" tone="ok">{{ notice }}</Notice>

    <Panel title="当前价目" hint="单位：元 / 百万 token；未配置价目的模型成本记为未计价">
      <DataTable :rows="rows.length" label="模型价目" empty="还没有价目"
                 empty-hint="填一条，下一批执行就会按它算成本">
        <thead>
          <tr>
            <th>provider</th>
            <th>模型</th>
            <th class="num">输入 / 百万</th>
            <th class="num">输出 / 百万</th>
            <th>币种</th>
            <th>更新</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in rows" :key="p.id">
            <td>{{ p.provider || '—' }}</td>
            <td><code>{{ p.model }}</code></td>
            <td class="num">{{ p.inputPerMillion }}</td>
            <td class="num">{{ p.outputPerMillion }}</td>
            <td>{{ p.currency }}</td>
            <td>{{ when(p.updatedAt) }}</td>
          </tr>
        </tbody>
      </DataTable>
    </Panel>

    <Panel title="新增或覆盖">
      <div class="form">
        <label class="field"><span>provider</span><input v-model="form.provider" class="inp" /></label>
        <label class="field"><span>模型</span><input v-model="form.model" class="inp" /></label>
        <label class="field narrow"><span>输入单价</span><input v-model="form.input" class="inp num" type="number" step="0.01" /></label>
        <label class="field narrow"><span>输出单价</span><input v-model="form.output" class="inp num" type="number" step="0.01" /></label>
        <label class="field narrow"><span>币种</span><input v-model="form.currency" class="inp" /></label>
        <Button variant="primary" :disabled="!form.model.trim()" @click="save">保存</Button>
      </div>
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
