<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import { reloadBotName } from '@shared/identity/botName'
import type { SettingsView, SettingItem } from '@shared/api/types'
import Panel from '@shared/ui/Panel.vue'
import Field from '@shared/ui/Field.vue'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Switch from '@shared/ui/Switch.vue'
import Notice from '@shared/ui/Notice.vue'

const loading = ref(false)
const error = ref('')
const ok = ref('')
const groups = ref<Record<string, SettingsView['groups'][string]>>({})

const changed = ref<Record<string, unknown>>({})

const SITE_GROUPS = ['首页轮播', '站点信息']
const botGroups = computed(() => Object.fromEntries(
  Object.entries(groups.value).filter(([name]) => !SITE_GROUPS.includes(name))))

const expandAll = ref<boolean | null>(null)
const gen = ref(0)
function setAll(open: boolean) {
  expandAll.value = open
  gen.value++
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const r = await adminApi.get<SettingsView>('/settings')
    groups.value = r.groups
    changed.value = {}
  } catch (e) { error.value = describeError(e) } finally {
    loading.value = false
  }
}

function onEdit(item: SettingItem, value: unknown) {
  changed.value = { ...changed.value, [item.key]: value }
  ok.value = ''
}

function listText(item: SettingItem): string {
  const v = changed.value[item.key] ?? item.value
  return Array.isArray(v) ? v.join(String.fromCharCode(10)) : String(v ?? '')
}
function onListEdit(item: SettingItem, text: string) {
  onEdit(item, text.split(String.fromCharCode(10)).map(s => s.trim()).filter(Boolean))
}

async function save() {
  if (!Object.keys(changed.value).length) { ok.value = '没有改动'; return }
  const botNameChanged = 'app.bot.name' in changed.value
  try {
    const r = await adminApi.post<{ ok: boolean; applied: string[]; rejected: string[]; errors: Record<string, string> }>(
      '/settings', { changes: changed.value })
    if (r.rejected?.length) { error.value = '这些配置项不允许修改：' + r.rejected.join(', '); return }
    if (r.errors && Object.keys(r.errors).length) { error.value = Object.values(r.errors).join('；'); return }
    ok.value = '已保存并立即生效（' + r.applied.length + ' 项）'
    if (botNameChanged) await reloadBotName()
    await load()
  } catch (e) { error.value = describeError(e) }
}

const changedCount = () => Object.keys(changed.value).length
const isChanged = (k: string) => k in changed.value
const changedIn = (items: SettingItem[]) => items.filter(i => isChanged(i.key)).length

const ENUM_OPTIONS: Array<[string, Array<{ value: string; label: string }>]> = [
  ['private-chat-policy', [
    { value: 'off', label: 'off — 不回私聊' },
    { value: 'all', label: 'all — 都回' },
    { value: 'whitelist', label: 'whitelist — 只回白名单' },
  ]],
  ['on-limit', [
    { value: 'silent', label: 'silent — 不出声' },
    { value: 'notify-once', label: 'notify-once — 提示一次' },
  ]],
  ['reply-style.format', [
    { value: '', label: '（不约束）' },
    { value: 'list', label: 'list — 列表' },
    { value: 'prose', label: 'prose — 段落' },
  ]],
]
const enumOpts = (item: SettingItem) => ENUM_OPTIONS.find(([frag]) => item.key.includes(frag))?.[1]
const val = (item: SettingItem) => String(changed.value[item.key] ?? item.value ?? '')
const ratioValue = (item: SettingItem) => {
  const n = Number(changed.value[item.key] ?? item.value)
  return Number.isFinite(n) ? Math.min(1, Math.max(0, n)) : 0
}
function onRatio(item: SettingItem, e: Event) {
  onEdit(item, Number((e.target as HTMLInputElement).value))
}

defineExpose({
  reload: load,
  save,
  setAll,
  changedCount,
  isLoading: () => loading.value,
})

onMounted(load)
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-if="ok" tone="ok">{{ ok }}</Notice>

    <div v-if="loading" class="muted">读取中…</div>

    <template v-else>
      <Panel
        v-for="(items, group) in botGroups"
        :key="group + '-' + gen"
        :title="String(group)"
        :count="items.length + ' 项'"
        :hint="changedIn(items) ? '已改 ' + changedIn(items) + ' 项' : ''"
        collapsible
        :default-open="expandAll === true"
      >
        <Field
          v-for="item in items"
          :key="item.key"
          :label="item.label"
          :hint="item.hint"
          :changed="isChanged(item.key)"
          :control-width="item.type === 'bool' ? '80px' : '320px'"
        >
          <Switch
            v-if="item.type === 'bool'"
            :model-value="Boolean(changed[item.key] ?? item.value)"
            :aria-label="item.label"
            @update:model-value="onEdit(item, $event)"
          />
          <Input
            v-else-if="item.type === 'int' || (item.type === 'string' && typeof item.value === 'number')"
            :model-value="val(item)"
            mono
            @update:model-value="onEdit(item, $event)"
          />
          <Select
            v-else-if="item.type === 'enum'"
            :model-value="val(item)"
            :options="enumOpts(item)"
            :aria-label="item.label"
            @update:model-value="onEdit(item, $event)"
          />
          <Input
            v-else-if="item.type === 'list'"
            :model-value="listText(item)"
            multiline
            :rows="3"
            placeholder="一行一个"
            @update:model-value="onListEdit(item, $event)"
          />
          <Input
            v-else-if="item.type === 'text'"
            :model-value="val(item)"
            multiline
            :rows="2"
            @update:model-value="onEdit(item, $event)"
          />
          <Input v-else :model-value="val(item)" @update:model-value="onEdit(item, $event)" />
        </Field>
      </Panel>

    </template>
  </div>
</template>

<style scoped>
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }
.ratio { display: flex; align-items: center; gap: var(--sp-3); width: 100%; }
.ratio-range { flex: 1; accent-color: var(--flame); }
.ratio-val { width: 42px; text-align: right; color: var(--ink-3); font-variant-numeric: tabular-nums; }
</style>
