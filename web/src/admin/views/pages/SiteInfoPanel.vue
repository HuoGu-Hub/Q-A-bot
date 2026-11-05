<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { SettingItem, SettingsView } from '@shared/api/types'
import Panel from '@shared/ui/Panel.vue'
import Field from '@shared/ui/Field.vue'
import Input from '@shared/ui/Input.vue'
import Button from '@shared/ui/Button.vue'
import Notice from '@shared/ui/Notice.vue'

const ORDER = [
  'app.site.group-name',
  'app.site.group-number',
  'app.site.group-desc',
  'app.site.join-hint',
]

const loading = ref(false)
const error = ref('')
const ok = ref('')
const items = ref<SettingItem[]>([])
const changed = ref<Record<string, unknown>>({})
const saving = ref(false)

const dirty = computed(() => Object.keys(changed.value).length > 0)
const isChanged = (key: string) => key in changed.value
const val = (it: SettingItem) => String(changed.value[it.key] ?? it.value ?? '')

async function load() {
  loading.value = true
  error.value = ''
  ok.value = ''
  try {
    const r = await adminApi.get<SettingsView>('/settings')
    const byKey = new Map(Object.values(r.groups).flat().map(i => [i.key, i]))
    items.value = ORDER.map(k => byKey.get(k)).filter((i): i is SettingItem => !!i)
    changed.value = {}
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

function onEdit(item: SettingItem, v: unknown) {
  changed.value = { ...changed.value, [item.key]: v }
  ok.value = ''
}

async function save() {
  if (!dirty.value) { ok.value = '没有改动'; return }
  saving.value = true
  error.value = ''
  try {
    const r = await adminApi.post<{ ok: boolean; applied: string[]; rejected: string[]; errors: Record<string, string> }>(
      '/settings', { changes: changed.value })
    if (r.rejected?.length) { error.value = '这些配置项不允许修改：' + r.rejected.join(', '); return }
    if (r.errors && Object.keys(r.errors).length) { error.value = Object.values(r.errors).join('；'); return }
    ok.value = '已保存并立即生效（' + r.applied.length + ' 项）'
    await load()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="panel-wrap">
    <Panel title="站点信息" :count="items.length ? items.length + ' 项' : ''">
      <Notice v-if="error" tone="error">{{ error }}</Notice>
      <Notice v-if="ok" tone="ok">{{ ok }}</Notice>

      <div v-if="loading" class="muted">读取中…</div>
      <div v-else-if="!items.length && !error" class="muted">
        后端白名单里没有 app.site.group-* 这几项
      </div>

      <template v-else>
        <Field
          v-for="it in items"
          :key="it.key"
          :label="it.label"
          :hint="it.hint"
          :changed="isChanged(it.key)"
          control-width="320px"
        >
          <Input
            :model-value="val(it)"
            :aria-label="it.label"
            @update:model-value="(v: string) => onEdit(it, v)"
          />
        </Field>

        <div class="settings-bar">
          <Button variant="primary" :disabled="saving || !dirty" @click="save">
            {{ saving ? '保存中…' : '保存设置' }}
          </Button>
          <Button :disabled="saving || !dirty" @click="load">放弃改动</Button>
        </div>
      </template>
    </Panel>
  </div>
</template>

<style scoped>
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); max-width: 760px; }
.settings-bar { display: flex; align-items: center; gap: var(--sp-2); margin-top: var(--sp-3); }
</style>
