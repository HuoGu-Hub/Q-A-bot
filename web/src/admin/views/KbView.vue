<script setup lang="ts">
/**
 * 知识库 —— 「文档」「词条」「分类」「提案」「抓取」五个二级目录合并后的页面。
 *
 *  文档   往知识库**导入文档**（按块 id 操作，导入即自动切割成一个个词条）
 *  词条   **一个词条 = 一个分块**：id + 标题 + 别名 + 分类；别名在这里改
 *  分类   人工维护的平铺分类清单：新增 / 改名 / 删除；标签认不出就是「未分类」
 *  提案   被认可的回答攒够阈值后，由 agent 产出、等人确认的改进提案
 *  抓取   从 wiki 抓文章（来源名与页面分类会自动登记进分类清单）
 *
 * 页面栏（吸顶）只剩「刷新」这类整页动作 —— 筛选、导出、导入都在各自面板里，
 * 免得一条吸顶栏塞十几个控件。tab 状态同步到 URL 查询串。
 *
 * ⚠️ **四个面板各用一个 ref，不能共用一个**。Vue 3.5 把模板 ref 的写入推迟到
 * post-render 队列，切 tab 的那一次渲染里 ref 还留着上一个面板的实例 ——
 * 共用一个 ref 时会调到类型不对的实例。分开之后每个 ref 只会是自己那个组件或 null。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminPage from '@shared/ui/AdminPage.vue'
import Tabs from '@shared/ui/Tabs.vue'
import Button from '@shared/ui/Button.vue'
import TermPanel from './kb/TermPanel.vue'
import CategoryPanel from './kb/CategoryPanel.vue'
import ProposalPanel from './kb/ProposalPanel.vue'
import DocPanel from './kb/DocPanel.vue'
import WikiPanel from './kb/WikiPanel.vue'

const route = useRoute()
const router = useRouter()

const TABS = [
  { key: 'docs', label: '文档' },
  { key: 'terms', label: '词条' },
  { key: 'category', label: '分类' },
  { key: 'proposals', label: '提案' },
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
const propRef = ref<InstanceType<typeof ProposalPanel> | null>(null)
const docRef = ref<InstanceType<typeof DocPanel> | null>(null)

const tm = computed(() => (tab.value === 'terms' ? termRef.value : null))
const cat = computed(() => (tab.value === 'category' ? catRef.value : null))
const prop = computed(() => (tab.value === 'proposals' ? propRef.value : null))
const doc = computed(() => (tab.value === 'docs' ? docRef.value : null))

const tmLoading = computed(() => tm.value?.isLoading() ?? false)
const catLoading = computed(() => cat.value?.isLoading() ?? false)
const docLoading = computed(() => doc.value?.isLoading() ?? false)

const propProgress = computed(() => prop.value?.progress() ?? null)
const propAnalyzing = computed(() => prop.value?.analyzing() ?? false)
const propLoading = computed(() => prop.value?.isLoading() ?? false)
const propCanAnalyze = computed(() => prop.value?.canAnalyze() ?? false)
const propTitle = computed(() => prop.value?.analyzeTitle() ?? '')
</script>

<template>
  <AdminPage width="wide">
    <template #tabs>
      <Tabs :tabs="TABS" :model-value="tab" @update:model-value="setTab" />
    </template>

    <template #actions>
      <template v-if="tab === 'terms'">
        <span class="stat faint">一个词条 = 一个分块</span>
        <Button size="sm" :disabled="tmLoading" @click="tm?.reload()">
          {{ tmLoading ? '读取中…' : '刷新' }}
        </Button>
      </template>

      <template v-else-if="tab === 'category'">
        <span class="stat faint">分类只读分块符号里的标签</span>
        <Button size="sm" :disabled="catLoading" @click="cat?.reload()">
          {{ catLoading ? '读取中…' : '刷新' }}
        </Button>
      </template>

      <template v-else-if="tab === 'proposals'">
        <span v-if="propProgress" class="stat faint">
          <template v-if="!propProgress.available">提案库不可用</template>
          <template v-else>
            <b>{{ propProgress.pendingGood }}</b> / <b>{{ propProgress.threshold }}</b>
            条「有帮助」待分析
            <template v-if="propProgress.canAnalyze"> · <span class="reach">可以分析</span></template>
            <template v-else> · 还差 {{ propProgress.remaining }} 条</template>
          </template>
        </span>
        <Button size="sm" :disabled="propLoading" @click="prop?.reload()">刷新</Button>
        <Button
          size="sm"
          variant="primary"
          :disabled="propAnalyzing || propLoading || !propCanAnalyze"
          :title="propTitle"
          @click="prop?.analyze()"
        >
          {{ propAnalyzing ? '分析中…' : '让 agent 分析' }}
        </Button>
      </template>

      <template v-else-if="tab === 'docs'">
        <Button size="sm" :disabled="docLoading" @click="docRef?.reload()">
          {{ docLoading ? '读取中…' : '刷新' }}
        </Button>
      </template>
    </template>

    <!--
      KeepAlive：词条面板里可能有正在编辑但没保存的别名。切个 tab 就丢掉这些是不可接受的。
    -->
    <KeepAlive>
      <DocPanel v-if="tab === 'docs'" ref="docRef" />
      <TermPanel v-else-if="tab === 'terms'" ref="termRef" />
      <CategoryPanel v-else-if="tab === 'category'" ref="catRef" />
      <ProposalPanel v-else-if="tab === 'proposals'" ref="propRef" />
      <WikiPanel v-else-if="tab === 'wiki'" />
    </KeepAlive>
  </AdminPage>
</template>

<style scoped>
.stat { font-size: var(--fs-meta); color: var(--ink-dim); }
.stat b { font-family: var(--font-mono); font-variant-numeric: tabular-nums; font-weight: var(--fw-semi); }
.reach { color: var(--moss); }
</style>
