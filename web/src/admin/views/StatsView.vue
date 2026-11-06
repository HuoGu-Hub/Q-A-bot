<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminPage from '@shared/ui/AdminPage.vue'
import Tabs from '@shared/ui/Tabs.vue'
import Select from '@shared/ui/Select.vue'
import Button from '@shared/ui/Button.vue'
import Switch from '@shared/ui/Switch.vue'
import OverviewPanel from './stats/OverviewPanel.vue'
import RecordsPanel from './stats/RecordsPanel.vue'
import RetrievalPanel from './stats/RetrievalPanel.vue'

const route = useRoute()
const router = useRouter()

const TABS = [
  { key: 'overview', label: '总览' },
  { key: 'records', label: '记录' },
  { key: 'retrieval', label: '检索' },
]

const valid = (k: unknown) => typeof k === 'string' && TABS.some((t) => t.key === k)
const tab = ref(valid(route.query.tab) ? String(route.query.tab) : 'overview')

watch(() => route.query.tab, (v) => {
  if (valid(v) && v !== tab.value) tab.value = String(v)
})

function setTab(k: string) {
  tab.value = k
  router.replace({ query: { ...route.query, tab: k } })
}

const days = ref(0)
const DAY_OPTIONS: Array<{ value: string; label: string }> = [
  { value: '7', label: '最近 7 天' },
  { value: '30', label: '最近 30 天' },
  { value: '90', label: '最近 90 天' },
  { value: '0', label: '全部' },
]

function onDays(v: string) {
  days.value = Number(v)
}

const SCOPE_OPTIONS = [
  { value: 'replied', label: '已回复' },
  { value: 'dropped', label: '未触发' },
  { value: 'all', label: '全部消息' },
]

const overviewRef = ref<InstanceType<typeof OverviewPanel> | null>(null)
const recordsRef = ref<InstanceType<typeof RecordsPanel> | null>(null)
const retrievalRef = ref<InstanceType<typeof RetrievalPanel> | null>(null)

const overview = computed(() => (tab.value === 'overview' ? overviewRef.value : null))
const records = computed(() => (tab.value === 'records' ? recordsRef.value : null))
const retrieval = computed(() => (tab.value === 'retrieval' ? retrievalRef.value : null))

const overviewLoading = computed(() => overview.value?.isLoading() ?? false)
const recordsLoading = computed(() => records.value?.isLoading() ?? false)
const retrievalLoading = computed(() => retrieval.value?.isLoading() ?? false)
const autoOn = computed(() => overview.value?.autoValue() ?? false)
const updatedAt = computed(() => overview.value?.updatedAt() ?? '')
const scope = computed(() => records.value?.scopeValue() ?? 'replied')
</script>

<template>
  <AdminPage width="wide">
    <template #tabs>
      <Tabs :tabs="TABS" :model-value="tab" @update:model-value="setTab" />
    </template>

    <template #actions>
      <Select
        class="w-days"
        :model-value="String(days)"
        :options="DAY_OPTIONS"
        aria-label="统计范围"
        @update:model-value="onDays"
      />

      <template v-if="tab === 'overview'">
        <label class="auto">
          <Switch
            :model-value="autoOn"
            aria-label="自动刷新"
            @update:model-value="(v: boolean) => overview?.toggleAuto(v)"
          />
          <span>自动刷新 60s</span>
        </label>
        <span v-if="updatedAt" class="stamp faint num">更新于 {{ updatedAt }}</span>
        <Button size="sm" :disabled="overviewLoading" @click="overview?.reload()">
          {{ overviewLoading ? '读取中…' : '刷新' }}
        </Button>
      </template>

      <template v-else-if="tab === 'records'">
        <Select
          class="w-scope"
          :model-value="scope"
          :options="SCOPE_OPTIONS"
          aria-label="记录范围"
          @update:model-value="(v: string) => records?.setScope(v)"
        />
        <Button size="sm" :disabled="recordsLoading" @click="records?.reload()">
          {{ recordsLoading ? '读取中…' : '刷新' }}
        </Button>
      </template>

      <template v-else>
        <Button size="sm" :disabled="retrievalLoading" @click="retrieval?.reload()">
          {{ retrievalLoading ? '读取中…' : '刷新' }}
        </Button>
      </template>
    </template>

    <KeepAlive>
      <OverviewPanel v-if="tab === 'overview'" ref="overviewRef" :days="days" />
      <RecordsPanel v-else-if="tab === 'records'" ref="recordsRef" :days="days" />
      <RetrievalPanel v-else ref="retrievalRef" :days="days" />
    </KeepAlive>
  </AdminPage>
</template>

<style scoped>
.w-days,
.w-scope { flex: 0 0 128px; }

.auto {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-xs);
  color: var(--ink-dim);
  white-space: nowrap;
}
.stamp { font-size: var(--fs-xs); white-space: nowrap; }

@media (max-width: 760px) {
  .auto span { display: none; }
  .stamp { display: none; }
  .w-days, .w-scope { flex: 0 0 112px; }
}
</style>
