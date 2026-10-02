<script setup lang="ts">
/**
 * 系统 —— 「模型」与「日志」合并后的页面。
 *
 * 合并的是同一件事的两端：模型管"用哪个模型答"，日志管"答的过程里发生了什么"。
 * 模型 ID 一改，日志里立刻能看到新的调用；排查时也是两头对着看。
 *
 * tab 状态同步到 URL 查询串（`?tab=logs`），这样旧的 /models、/logs 跳过来
 * 能直接落到对应 tab，链接也可分享、刷新后不跳回默认。
 *
 * 页面栏（吸顶）左边是二级目录、右边是当前 tab 的动作：刷新、级别过滤、搜索、
 * 暂停/继续、清空，以及缓冲读数。按钮与筛选控件调的都是面板内部状态，所以面板
 * 用 defineExpose 抛动作，这里按当前 tab 取用。
 *
 * ⚠️ **两个面板各用一个 ref，不共用一个**。项目里踩过这个坑：Vue 3.5 把模板 ref
 * 的写入推迟到 post-render 队列，共用 ref 时「切 tab 的那一次渲染」里还留着上一个
 * 面板的实例（类型不对），页面栏调它的动作会直接抛错。分开之后每个 ref 只会是
 * 「自己那个组件」或 null，可选链兜住 null 即可。
 */
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

/** 两个面板各一个 ref，按当前 tab 取用（原因见文件头注释） */
const modelsRef = ref<InstanceType<typeof ModelsPanel> | null>(null)
const logsRef = ref<InstanceType<typeof LogsPanel> | null>(null)

const models = computed(() => (tab.value === 'models' ? modelsRef.value : null))
const logs = computed(() => (tab.value === 'logs' ? logsRef.value : null))

/** 读数在这里取好再给模板用，模板里就不用连着一串可选链 */
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
        <!-- 计数一直在跳，等宽 + tabular-nums 才不会左右抖 -->
        <span v-if="logStatus" class="meta faint num">
          缓冲 {{ num(logStatus.bufferSize) }}/{{ num(logStatus.capacity) }}
          <template v-if="logStatus.dropped > 0"> · 已丢 {{ num(logStatus.dropped) }}</template>
          <template v-if="logStatus.maskSensitive"> · 已脱敏</template>
        </span>
        <Select
          class="w-level"
          :model-value="logLevel"
          :options="logLevelOptions"
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

    <!--
      KeepAlive 是必须的：日志面板有 SSE 连接与暂停/过滤状态，模型面板有
      未保存的编辑缓冲。切个 tab 就断流、丢改动是不可接受的。
    -->
    <KeepAlive>
      <ModelsPanel v-if="tab === 'models'" ref="modelsRef" />
      <LogsPanel v-else ref="logsRef" />
    </KeepAlive>
  </AdminPage>
</template>

<style scoped>
/* 页面栏里的控件。共享 Input / Select 自带 width:100%，
   所以定宽用 flex-basis（不去和 width 打平手，也不看两份样式谁先注入）。 */
.w-level { flex: 0 0 118px; }
.w-search { flex: 0 0 200px; }

.meta { font-size: var(--fs-meta); color: var(--ink-dim); }

@media (max-width: 900px) {
  .w-level, .w-search { flex: 1 1 100%; }
}
</style>
