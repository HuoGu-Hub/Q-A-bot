<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminPage from '@shared/ui/AdminPage.vue'
import Tabs from '@shared/ui/Tabs.vue'
import Button from '@shared/ui/Button.vue'
import Input from '@shared/ui/Input.vue'
import Select from '@shared/ui/Select.vue'
import Tag from '@shared/ui/Tag.vue'
import { num } from '@shared/utils/format'
import ModelsPanel from './system/ModelsPanel.vue'
import LogsPanel from './system/LogsPanel.vue'

const route = useRoute()
const router = useRouter()

const TABS = [
  { key: 'models', label: '模型' },
  { key: 'logs', label: '日志' },
]

const valid = (k: unknown) => typeof k === 'string' && TABS.some(t => t.key === k)
const tab = ref(valid(route.query.tab) ? String(route.query.tab) : 'models')

watch(() => route.query.tab, (v) => {
  if (valid(v) && v !== tab.value) tab.value = String(v)
})

function setTab(k: string) {
  tab.value = k
  router.replace({ query: { ...route.query, tab: k } })
}

const modelsRef = ref<InstanceType<typeof ModelsPanel> | null>(null)
const logsRef = ref<InstanceType<typeof LogsPanel> | null>(null)

const models = computed(() => (tab.value === 'models' ? modelsRef.value : null))
const logs = computed(() => (tab.value === 'logs' ? logsRef.value : null))

const modelsLoading = computed(() => models.value?.isLoading() ?? false)

const logStatus = computed(() => logs.value?.statusValue() ?? null)
const logLevel = computed(() => logs.value?.levelValue() ?? 'INFO')
const logLevelOptions = computed(() => logs.value?.levelOptions())
const logKeyword = computed(() => logs.value?.keywordValue() ?? '')
const logPaused = computed(() => logs.value?.pausedValue() ?? false)
const logErrors = computed(() => logs.value?.errorCount() ?? 0)
const logWarns = computed(() => logs.value?.warnCount() ?? 0)
</script>

<template>
  <AdminPage width="wide">
    <template #tabs>
      <Tabs :tabs="TABS" :model-value="tab" @update:model-value="setTab" />
    </template>

    <template #actions>
      <template v-if="tab === 'models'">
        <Button size="sm" :disabled="modelsLoading" @click="models?.reload()">
          {{ modelsLoading ? '读取中…' : '刷新' }}
        </Button>
      </template>

      <template v-else>
        <span v-if="logStatus" class="meta faint num">
          缓冲 {{ num(logStatus.bufferSize) }}/{{ num(logStatus.capacity) }}
          <template v-if="logStatus.dropped > 0"> · 已丢 {{ num(logStatus.dropped) }}</template>
          <template v-if="logStatus.maskSensitive"> · 已脱敏</template>
        </span>
        <Select
          class="w-level"
          :model-value="logLevel"
          :options="logLevelOptions"
          aria-label="日志级别"
          @update:model-value="(v: string) => logs?.setLevel(v)"
        />
        <Input
          class="w-search"
          :model-value="logKeyword"
          placeholder="搜索消息 / 异常"
          @update:model-value="(v: string) => logs?.setKeyword(v)"
        />
        <Tag v-if="logErrors" tone="bad">ERROR {{ logErrors }}</Tag>
        <Tag v-if="logWarns" tone="warn">WARN {{ logWarns }}</Tag>
        <Button size="sm" @click="logs?.togglePause()">{{ logPaused ? '继续' : '暂停' }}</Button>
        <Button size="sm" @click="logs?.clearBuffer()">清空</Button>
        <Button size="sm" @click="logs?.reload()">刷新</Button>
      </template>
    </template>

    <KeepAlive>
      <ModelsPanel v-if="tab === 'models'" ref="modelsRef" />
      <LogsPanel v-else ref="logsRef" />
    </KeepAlive>
  </AdminPage>
</template>

<style scoped>
.w-level { flex: 0 0 118px; }
.w-search { flex: 0 0 200px; }

.meta { font-size: var(--fs-meta); color: var(--ink-dim); }

@media (max-width: 900px) {
  .w-level, .w-search { flex: 1 1 100%; }
}
</style>
