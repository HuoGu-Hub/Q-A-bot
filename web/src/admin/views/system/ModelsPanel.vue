<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { ModelList, ModelProvider, ProbeResult, AvailableModels } from '@shared/api/types'
import Panel from '@shared/ui/Panel.vue'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Notice from '@shared/ui/Notice.vue'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'

const loading = ref(false)
const error = ref('')
const ok = ref('')
const data = ref<ModelList | null>(null)

const edits = ref<Record<string, string>>({})
const probes = ref<Record<string, ProbeResult>>({})
const probing = ref<Record<string, boolean>>({})
const available = ref<Record<string, string[]>>({})
const loadingModels = ref<Record<string, boolean>>({})

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await adminApi.get<ModelList>('/models')
    edits.value = {}
    probes.value = {}
  } catch (e) { error.value = describeError(e) } finally {
    loading.value = false
  }
}

function current(m: ModelProvider): string {
  return edits.value[m.name] ?? m.modelName
}

function isChanged(m: ModelProvider): boolean {
  return m.name in edits.value && edits.value[m.name] !== m.modelName
}

function onEdit(m: ModelProvider, v: string) {
  edits.value = { ...edits.value, [m.name]: v }
  const rest = { ...probes.value }
  delete rest[m.name]
  probes.value = rest
  ok.value = ''
}

async function fetchModels(m: ModelProvider) {
  loadingModels.value = { ...loadingModels.value, [m.name]: true }
  try {
    const r = await adminApi.get<AvailableModels>('/models/' + encodeURIComponent(m.name) + '/available')
    if (r.ok) {
      available.value = { ...available.value, [m.name]: r.models }
    } else {
      error.value = '拉取模型列表失败：' + (r.error ?? '未知原因')
    }
  } catch (e) { error.value = describeError(e) } finally {
    loadingModels.value = { ...loadingModels.value, [m.name]: false }
  }
}

function modelOptions(m: ModelProvider) {
  const ids = available.value[m.name] ?? []
  const cur = current(m)
  return (cur && !ids.includes(cur) ? [cur, ...ids] : ids).map(id => ({ value: id, label: id }))
}

async function test(m: ModelProvider) {
  probing.value = { ...probing.value, [m.name]: true }
  ok.value = ''
  try {
    const r = await adminApi.post<ProbeResult>('/models/' + encodeURIComponent(m.name) + '/actions/test', {
      modelName: current(m),
    })
    probes.value = { ...probes.value, [m.name]: r }
  } catch (e) {
    probes.value = { ...probes.value, [m.name]: { ok: false, error: describeError(e) } }
  } finally {
    probing.value = { ...probing.value, [m.name]: false }
  }
}

async function save(m: ModelProvider) {
  ok.value = ''
  try {
    await adminApi.put('/models/' + encodeURIComponent(m.name), {
      modelName: current(m),
      verify: true,
    })
    ok.value = m.name + ' 的模型已切换为 ' + current(m) + '，立即生效'
    await load()
  } catch (e) { error.value = describeError(e) }
}

defineExpose({
  reload: load,
  isLoading: () => loading.value,
})

onMounted(load)
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-if="ok" tone="ok">{{ ok }}</Notice>

    <div v-if="loading && !data" class="muted">读取中…</div>

    <Panel v-if="data" title="当前调用顺序">
      <div class="chain">
        <template v-for="(name, i) in data.fallbackChain" :key="name">
          <span v-if="i > 0" class="arrow">→</span>
          <span class="node" :class="{ primary: i === 0 }">
            {{ name }}
            <span class="faint node-role"> {{ i === 0 ? '默认' : '降级 ' + i }}</span>
          </span>
        </template>
      </div>
    </Panel>

    <Panel v-for="m in data?.providers ?? []" :key="m.name" :title="m.name">
      <div class="head">
        <Tag :tone="m.ready ? 'good' : m.configured ? 'warn' : 'neutral'">
          {{ m.ready ? '已就绪' : m.configured ? '已配置但未加载' : '未配置' }}
        </Tag>
        <Tag v-if="m.name === data?.defaultProvider" tone="flame">默认</Tag>
      </div>

      <div class="edit-box">
        <div class="edit-label">
          模型 ID
        </div>
        <div class="edit-row">
          <div class="grow">
            <Input
              :model-value="current(m)"
              placeholder="如 deepseek-v4.1-flash"
              mono
              @update:model-value="onEdit(m, $event)"
            />
          </div>
          <Button size="sm" :disabled="loadingModels[m.name]" @click="fetchModels(m)">
            {{ loadingModels[m.name] ? '拉取中…' : '拉取列表' }}
          </Button>
        </div>

        <div v-if="(available[m.name] ?? []).length" class="pick-row">
          <Select
            :model-value="current(m)"
            :options="modelOptions(m)"
            :aria-label="m.name + ' 的模型'"
            @update:model-value="onEdit(m, $event)"
          />
        </div>

        <div class="edit-actions">
          <Button size="sm" :disabled="probing[m.name]" @click="test(m)">
            {{ probing[m.name] ? '测试中…' : '测试连通性' }}
          </Button>
          <Button size="sm" variant="primary" :disabled="!isChanged(m)" @click="save(m)">
            保存并生效
          </Button>
          <span v-if="isChanged(m)" class="faint note">已修改，未保存</span>
        </div>

        <div v-if="probes[m.name]" class="probe-wrap">
          <Notice :tone="probes[m.name].ok ? 'ok' : 'error'">
            <template v-if="probes[m.name].ok">
              连通 ✅ 耗时 {{ probes[m.name].elapsedMs }}ms
              <span class="probe-reply">模型回复：{{ probes[m.name].reply }}</span>
            </template>
            <template v-else>
              失败 ❌ {{ probes[m.name].error }}
              <span class="probe-reply faint">
                提示：模型 ID 必须是真实存在的 ID；推理模型若返回空内容，可能是 max-tokens 太小。
              </span>
            </template>
          </Notice>
        </div>
      </div>
    </Panel>
  </div>
</template>

<style scoped>
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }
.note { font-size: var(--fs-meta); line-height: 1.9; }
.note:last-child { margin-bottom: 0; }

.chain { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; margin-bottom: var(--sp-3); }
.node {
  background: var(--surface-inset);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-sm);
  padding: 5px 12px;
  font-family: var(--font-mono);
  font-size: var(--fs-meta);
}
.node.primary { border-color: var(--flame); color: var(--flame-bright); box-shadow: 0 0 0 3px var(--flame-glow); }
.node-role { margin-left: var(--sp-1); }
.arrow { color: var(--copper); }

.head { display: flex; gap: var(--sp-2); margin-bottom: var(--sp-4); align-items: center; }

.edit-box {
  padding: var(--sp-4);
  background: var(--surface-inset);
  border: 1px solid var(--flame-veil);
  border-radius: var(--r-md);
}
.edit-label { font-size: var(--fs-sm); color: var(--ink); margin-bottom: var(--sp-3); }
.edit-row { display: flex; gap: var(--sp-2); align-items: center; }
.grow { flex: 1; min-width: 0; }
.pick-row { margin-top: var(--sp-2); max-width: 360px; }
.edit-actions { display: flex; gap: var(--sp-2); align-items: center; margin-top: var(--sp-3); }

.probe-wrap { margin-top: var(--sp-3); }
.probe-reply { display: block; margin-top: 5px; color: var(--ink-dim); }
</style>
