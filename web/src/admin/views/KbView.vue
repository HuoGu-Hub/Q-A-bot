<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminPage from '@shared/ui/AdminPage.vue'
import Tabs from '@shared/ui/Tabs.vue'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'
import TermPanel from './kb/TermPanel.vue'
import CategoryPanel from './kb/CategoryPanel.vue'
import DistillPanel from './kb/DistillPanel.vue'
import DocPanel from './kb/DocPanel.vue'
import WikiPanel from './kb/WikiPanel.vue'

const route = useRoute()
const router = useRouter()

const TABS = [
  { key: 'docs', label: '文档' },
  { key: 'terms', label: '词条' },
  { key: 'category', label: '分类' },
  { key: 'distill', label: '聊天蒸馏' },
  { key: 'wiki', label: '抓取' },
]

const valid = (k: unknown) => typeof k === 'string' && TABS.some(t => t.key === k)
const tab = ref(valid(route.query.tab) ? String(route.query.tab) : 'docs')

watch(() => route.query.tab, (v) => {
  if (valid(v) && v !== tab.value) tab.value = String(v)
})

function setTab(k: string) {
  tab.value = k
  router.replace({ query: { ...route.query, tab: k } })
}

const termRef = ref<InstanceType<typeof TermPanel> | null>(null)
const catRef = ref<InstanceType<typeof CategoryPanel> | null>(null)
const distillRef = ref<InstanceType<typeof DistillPanel> | null>(null)
const docRef = ref<InstanceType<typeof DocPanel> | null>(null)
const wikiRef = ref<InstanceType<typeof WikiPanel> | null>(null)

const tm = computed(() => (tab.value === 'terms' ? termRef.value : null))
const cat = computed(() => (tab.value === 'category' ? catRef.value : null))
const doc = computed(() => (tab.value === 'docs' ? docRef.value : null))
const wiki = computed(() => (tab.value === 'wiki' ? wikiRef.value : null))

const tmLoading = computed(() => tm.value?.isLoading() ?? false)
const catLoading = computed(() => cat.value?.isLoading() ?? false)
const distillLoading = computed(() => distillRef.value?.isLoading() ?? false)
const docLoading = computed(() => doc.value?.isLoading() ?? false)

const wikiRunning = computed(() => wiki.value?.isRunning() ?? false)
const wikiState = computed(() => wiki.value?.runState() ?? { state: 'idle', label: '空闲', tone: 'neutral' as const })
</script>

<template>
  <AdminPage width="wide">
    <template #tabs>
      <Tabs :tabs="TABS" :model-value="tab" @update:model-value="setTab" />
    </template>

    <template #actions>
      <template v-if="tab === 'terms'">
        <Button size="sm" variant="primary" :disabled="tmLoading" @click="tm?.openCreate()">新增词条</Button>
        <Button size="sm" :disabled="tmLoading" @click="tm?.openExport()">导出</Button>
        <Button size="sm" :disabled="tmLoading" @click="tm?.pickImport()">导入别名表</Button>
        <Button size="sm" variant="danger" :disabled="tmLoading" @click="tm?.clearAllAliases()">
          清空全部别名
        </Button>
        <Button size="sm" :disabled="tmLoading" @click="tm?.reload()">
          {{ tmLoading ? '读取中…' : '刷新' }}
        </Button>
      </template>

      <template v-else-if="tab === 'category'">
        <Button size="sm" :disabled="catLoading" @click="cat?.reload()">
          {{ catLoading ? '读取中…' : '刷新' }}
        </Button>
      </template>

      <template v-else-if="tab === 'wiki'">
        <Tag :tone="wikiState.tone">{{ wikiState.label }}</Tag>
        <Button size="sm" variant="primary" :disabled="wikiRunning" @click="wikiRef?.openRun()">
          {{ wikiRunning ? '抓取中…' : '开始抓取' }}
        </Button>
        <Button size="sm" @click="wikiRef?.reload()">刷新</Button>
      </template>

      <template v-else-if="tab === 'distill'">
        <Button size="sm" :disabled="distillLoading" @click="distillRef?.reload()">
          {{ distillLoading ? '读取中…' : '刷新' }}
        </Button>
      </template>

      <template v-else-if="tab === 'docs'">
        <Button size="sm" :disabled="docLoading" @click="docRef?.reload()">
          {{ docLoading ? '读取中…' : '刷新' }}
        </Button>
      </template>
    </template>

    <KeepAlive>
      <DocPanel v-if="tab === 'docs'" ref="docRef" />
      <TermPanel v-else-if="tab === 'terms'" ref="termRef" />
      <CategoryPanel v-else-if="tab === 'category'" ref="catRef" />
      <DistillPanel v-else-if="tab === 'distill'" ref="distillRef" />
      <WikiPanel v-else-if="tab === 'wiki'" ref="wikiRef" />
    </KeepAlive>
  </AdminPage>
</template>

<style scoped>
.stat b { font-family: var(--font-mono); font-variant-numeric: tabular-nums; font-weight: var(--fw-semi); }
</style>
