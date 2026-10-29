<script setup lang="ts">
/**
 * 「页面信息 → 站点信息」面板 —— 公开站上那几处加群信息。
 *
 * <h2>为什么它在页面信息，而不是 Bot 配置</h2>
 * 这几项描述的是**公开站长什么样**（群叫什么、群号多少、怎么引导加群），
 * 与机器人怎么回答没有任何关系。原先混在 Bot 配置里，改公开站文案得在两个页面之间来回跳。
 *
 * <p>设置项走现成的 {@code /admin/api/settings}（与白名单同一套），不另造接口 ——
 * 与「首页轮播」面板同一套做法。
 */
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { SettingItem, SettingsView } from '@shared/api/types'
import Panel from '@shared/ui/Panel.vue'
import Field from '@shared/ui/Field.vue'
import Input from '@shared/ui/Input.vue'
import Button from '@shared/ui/Button.vue'
import Notice from '@shared/ui/Notice.vue'

/**
 * 展示顺序：先群名、再群号，最后两段说明文字。
 * 后端只保证"有哪些项"，不保证顺序，所以这里定死。
 */
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
/** 改动缓冲：只有改过的项才会提交 */
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
      <!-- 三种状态要分得开：还在读 / 读失败（上面的 Notice）/ 读到了但一项都没有 -->
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
/* 四个短输入框不该铺满整页宽 —— 页面容器是宽版，这里自己收窄 */
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); max-width: 760px; }
.settings-bar { display: flex; align-items: center; gap: var(--sp-2); margin-top: var(--sp-3); }
</style>
