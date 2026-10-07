<script setup lang="ts">
/**
 * 「Bot 配置 → 设置」面板。
 *
 * 从原来的独立页面 SettingsView 拆出来：外壳（AdminPage）与二级目录都交给
 * 父页面 BotConfigView，这里只负责这一个 tab 的内容。
 *
 * 操作按钮由父页面渲染在吸顶的页面栏里（「保存并生效」等），本面板通过
 * defineExpose 把这些动作抛出去 —— 按钮要调的是面板内部状态，
 * 视图拿不到，所以必须这样开口子。
 */
import { onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
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

/** 改动缓冲：只有改过的项才会提交 */
const changed = ref<Record<string, unknown>>({})

/**
 * 折叠状态：默认【全部收起】。
 *
 * 原来 12 个分组 53 个控件全部展开，页面长到要靠滚动找东西。
 * Panel 的展开态是它自己的内部状态，所以「全部展开/收起」用换 key 重挂载；
 * 未保存的改动存在 `changed` 里，重挂载不会丢。
 */
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

/** 列表类配置用多行文本编辑（一行一个） */
function listText(item: SettingItem): string {
  const v = changed.value[item.key] ?? item.value
  return Array.isArray(v) ? v.join(String.fromCharCode(10)) : String(v ?? '')
}
function onListEdit(item: SettingItem, text: string) {
  onEdit(item, text.split(String.fromCharCode(10)).map(s => s.trim()).filter(Boolean))
}

async function save() {
  if (!Object.keys(changed.value).length) { ok.value = '没有改动'; return }
  try {
    const r = await adminApi.post<{ ok: boolean; applied: string[]; rejected: string[]; errors: Record<string, string> }>(
      '/settings', { changes: changed.value })
    if (r.rejected?.length) { error.value = '这些配置项不允许修改：' + r.rejected.join(', '); return }
    if (r.errors && Object.keys(r.errors).length) { error.value = Object.values(r.errors).join('；'); return }
    ok.value = '已保存并立即生效（' + r.applied.length + ' 项）'
    await load()
  } catch (e) { error.value = describeError(e) }
}

const changedCount = () => Object.keys(changed.value).length
const isChanged = (k: string) => k in changed.value
/** 该分组里改了几项 —— 收起状态下徽标仍可见，方便定位改动 */
const changedIn = (items: SettingItem[]) => items.filter(i => isChanged(i.key)).length

/**
 * 枚举选项表。
 * ⚠️ 兜底逻辑下沉在 Select 组件里：没有预置选项时展示当前值，不会写成空值。
 */
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

/**
 * 父页面（BotConfigView）页面栏上的按钮要调这些动作。
 *
 * 全部以**函数**形式暴露（而不是 ref / computed）：
 * 视图在渲染时调用它们，读到的响应式依赖会计入视图的渲染副作用，
 * 所以「已改 N 项」这类数字照样会随面板内部状态更新，类型也是干净的。
 */
defineExpose({
  /** 放弃改动（重新拉取） */
  reload: load,
  save,
  setAll,
  changedCount,
  /** 读数式暴露：视图在渲染时调用它，读到 loading 变化会重渲染，按钮禁用态才跟得上 */
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
        v-for="(items, group) in groups"
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
</style>
