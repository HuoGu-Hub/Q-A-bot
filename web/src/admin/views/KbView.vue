<script setup lang="ts">
/**
 * 知识库 —— 「文档」「词条」「分类」「聊天蒸馏」「抓取」五个二级目录合并后的页面。
 *
 *  文档   往知识库**导入文档**（按块 id 操作，导入即自动切割成一个个词条）
 *  词条   **一个词条 = 一个分块**：列表看标题 / 别名 / 分类，点「编辑」展开改别名与正文
 *  分类   人工维护的平铺分类清单：新增 / 改名 / 删除；标签认不出就是「未分类」
 *  聊天蒸馏 自动触发设置 / 蒸馏模型 / 笔记整理与发布
 *  抓取   从 wiki 抓文章：选范围 → 开始抓取（空跑 / 增量 / 强制重洗）→ 看进度与结果
 *
 * 页面栏（吸顶）只剩「刷新」这类整页动作 —— 筛选、导出、导入都在各自面板里，
 * 免得一条吸顶栏塞十几个控件。tab 状态同步到 URL 查询串。
 *
 * ⚠️ **每个面板各用一个 ref，不能共用一个**。Vue 3.5 把模板 ref 的写入推迟到
 * post-render 队列，切 tab 的那一次渲染里 ref 还留着上一个面板的实例 ——
 * 共用一个 ref 时会调到类型不对的实例。分开之后每个 ref 只会是自己那个组件或 null。
 */
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
      <!--
        词条：动作全在顶栏（筛选、翻页在面板里）。
        导出收成一个入口，点了先选 xlsx / tsv —— 原来两个并排按钮只差一个后缀。
      -->
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
        <span class="stat faint">分类只读分块符号里的标签</span>
        <Button size="sm" :disabled="catLoading" @click="cat?.reload()">
          {{ catLoading ? '读取中…' : '刷新' }}
        </Button>
      </template>

      <!--
        抓取：主操作常驻顶栏。这个页面以前最大的问题就是"找不到从哪开始" ——
        现在无论滚到分类菜单的哪一行，「开始抓取」都在同一处。
      -->
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

    <!--
      KeepAlive：词条面板里可能有正在编辑但没保存的别名。切个 tab 就丢掉这些是不可接受的。
    -->
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
.stat { font-size: var(--fs-meta); color: var(--ink-dim); }
.stat b { font-family: var(--font-mono); font-variant-numeric: tabular-nums; font-weight: var(--fw-semi); }
</style>
