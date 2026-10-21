<script setup lang="ts">
/**
 * 统计 —— 「看板」与「记录」合并后的页面。
 *
 * <p>合并的理由：两页回答的是同一个问题 —— 现在用得怎么样、具体哪几条答得不对。
 * 看板给总量与分布，记录给单条明细，排查时本来就要来回对照。分开时看板页
 * 没有二级目录，动作按钮只能自己占一整行；并进来之后，页栏左边是 tab、
 * 右边是本 tab 的动作，和知识库 / 系统 / Bot 配置是同一套结构。
 *
 * 时间窗口**放在这一层**：两个 tab 看的是同一个窗口，切 tab 不该把范围重置，
 * 也不该出现"总览说最近 7 天、记录却是最近 30 天"这种对不上的读数。
 * 默认 0 = 全部：先回答"到现在一共多少"，要看趋势再切 7/30/90 天。
 *
 * tab 状态同步到 URL 查询串（`?tab=records`），旧地址 /dashboard、/records、
 * /screen 全部重定向到这里（见 router.ts），书签和肌肉记忆不会失效。
 *
 * ⚠️ **两个面板各用一个 ref，不能共用一个**。Vue 3.5 把模板 ref 的写入推迟到
 * post-render 队列，切 tab 的那一次渲染里 ref 还留着上一个面板的实例 ——
 * 共用一个 ref 时会调到类型不对的实例。分开之后每个 ref 只会是自己那个组件或 null。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminPage from '@shared/ui/AdminPage.vue'
import Tabs from '@shared/ui/Tabs.vue'
import Select from '@shared/ui/Select.vue'
import Button from '@shared/ui/Button.vue'
import Switch from '@shared/ui/Switch.vue'
import OverviewPanel from './stats/OverviewPanel.vue'
import RecordsPanel from './stats/RecordsPanel.vue'

const route = useRoute()
const router = useRouter()

const TABS = [
  { key: 'overview', label: '总览' },
  { key: 'records', label: '记录' },
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

/**
 * 统计窗口。Select 的 v-model 是 string（原生 select 的值本来就是字符串），
 * 接口要数字，所以在这里换算一次，两个面板拿到的都已经是 number。
 */
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

/** 记录范围只在记录 tab 出现；选项与 RecordsPanel 的 scope 取值一致 */
const SCOPE_OPTIONS = [
  { value: 'questions', label: '只看提问' },
  { value: 'all', label: '全部消息' },
]

const overviewRef = ref<InstanceType<typeof OverviewPanel> | null>(null)
const recordsRef = ref<InstanceType<typeof RecordsPanel> | null>(null)

const overview = computed(() => (tab.value === 'overview' ? overviewRef.value : null))
const records = computed(() => (tab.value === 'records' ? recordsRef.value : null))

/** 读数在这里取好再给模板用，模板里就不用连着一串可选链 */
const overviewLoading = computed(() => overview.value?.isLoading() ?? false)
const recordsLoading = computed(() => records.value?.isLoading() ?? false)
const autoOn = computed(() => overview.value?.autoValue() ?? false)
const updatedAt = computed(() => overview.value?.updatedAt() ?? '')
const scope = computed(() => records.value?.scopeValue() ?? 'questions')
</script>

<template>
  <AdminPage width="wide">
    <template #tabs>
      <Tabs :tabs="TABS" :model-value="tab" @update:model-value="setTab" />
    </template>

    <template #actions>
      <!-- 时间窗口两个 tab 共用，所以常驻；其余动作跟着 tab 走 -->
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

      <template v-else>
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
    </template>

    <!--
      KeepAlive：总览的自动刷新开关、记录里展开的详情与翻到的位置，
      切个 tab 就重置是不可接受的。面板内部会在不可见时暂停请求。
    -->
    <KeepAlive>
      <OverviewPanel v-if="tab === 'overview'" ref="overviewRef" :days="days" />
      <RecordsPanel v-else ref="recordsRef" :days="days" />
    </KeepAlive>
  </AdminPage>
</template>

<style scoped>
/* 页面栏里的控件。共享 Select 自带 width:100%，用 flex-basis 定宽才压得住，
   也不受两份单文件样式谁先注入的影响。 */
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

/* 手机上动作行只留「范围 + 刷新」：自动刷新的说明文字与更新时间属于桌面信息，
   挤在 390px 的一行里会把两个下拉挤出屏幕。 */
@media (max-width: 760px) {
  .auto span { display: none; }
  .stamp { display: none; }
  .w-days, .w-scope { flex: 0 0 112px; }
}
</style>