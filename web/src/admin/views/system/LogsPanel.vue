<script setup lang="ts">
/**
 * 「系统 → 日志」面板。
 *
 * 只服务业务层日志：内存缓冲取历史 + SSE 实时推送。
 *
 * 级别过滤、搜索、暂停/继续、清空原来在面板自己的过滤行里，现在都收到
 * SystemView 的吸顶页面栏上；本面板通过 defineExpose 把状态与动作抛出去。
 */
import { computed, nextTick, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { LogEntry, LogHistory, LogStatus } from '@shared/api/types'
import { num, shortTime } from '@shared/utils/format'
import Panel from '@shared/ui/Panel.vue'
import Notice from '@shared/ui/Notice.vue'
import Button from '@shared/ui/Button.vue'
import Empty from '@shared/ui/Empty.vue'

const error = ref('')

const entries = ref<LogEntry[]>([])
const status = ref<LogStatus | null>(null)
const level = ref('INFO')
const keyword = ref('')
const following = ref(true)
const paused = ref(false)
const box = ref<HTMLElement | null>(null)

let source: EventSource | null = null
const LEVELS = ['DEBUG', 'INFO', 'WARN', 'ERROR']
const LEVEL_OPTIONS = LEVELS.map(l => ({ value: l, label: `≥ ${l}` }))

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return entries.value
  return entries.value.filter(e =>
    e.message.toLowerCase().includes(kw) ||
    (e.throwable ?? '').toLowerCase().includes(kw))
})

const errorCount = computed(() => entries.value.filter(e => e.level === 'ERROR').length)
const warnCount = computed(() => entries.value.filter(e => e.level === 'WARN').length)

async function loadStatus() {
  try {
    status.value = await adminApi.get<LogStatus>('/logs/status')
  } catch (e) { error.value = describeError(e) }
}

async function loadHistory() {
  try {
    const h = await adminApi.get<LogHistory>(
      `/logs/history?limit=${status.value?.capacity ?? 2000}&level=${level.value}`)
    entries.value = h.entries
    await scrollToBottom()
  } catch (e) { error.value = describeError(e) }
}

/** 页面栏「刷新」：状态与历史一起重取 */
async function reload() {
  error.value = ''
  await loadStatus()
  await loadHistory()
}

function connect() {
  closeStream()
  source = new EventSource(`/admin/api/logs/stream?level=${level.value}`)
  source.addEventListener('log', (ev) => {
    if (paused.value) return
    try {
      const e = JSON.parse((ev as MessageEvent).data) as LogEntry
      entries.value.push(e)
      const cap = (status.value?.capacity ?? 2000) + 500
      if (entries.value.length > cap) {
        entries.value.splice(0, entries.value.length - cap)
      }
      if (following.value) scrollToBottom()
    } catch { /* 忽略坏消息 */ }
  })
  // SSE 断线由浏览器自动重连
  source.onerror = () => { /* noop */ }
}

function closeStream() {
  if (source) { source.close(); source = null }
}

async function scrollToBottom() {
  await nextTick()
  if (box.value) box.value.scrollTop = box.value.scrollHeight
}

function onScroll() {
  if (!box.value) return
  const { scrollTop, scrollHeight, clientHeight } = box.value
  following.value = scrollHeight - scrollTop - clientHeight < 40
}

function jumpToLatest() {
  following.value = true
  scrollToBottom()
}

function setLevel(v: string) {
  level.value = v
}

function setKeyword(v: string) {
  keyword.value = v
}

function togglePause() {
  paused.value = !paused.value
  if (!paused.value && following.value) scrollToBottom()
}

async function clearBuffer() {
  if (!window.confirm('清空内存缓冲？文件里的日志不受影响。')) return
  try {
    await adminApi.post('/logs/clear')
    entries.value = []
    await loadStatus()
  } catch (e) { error.value = describeError(e) }
}

watch(level, async () => {
  await loadHistory()
  connect()
})

/**
 * 父页面（SystemView）页面栏上的筛选控件与按钮要读写这些状态。
 * 暴露的是函数：视图渲染时调用它们，读到的依赖计入视图的渲染副作用。
 */
defineExpose({
  reload,
  levelValue: () => level.value,
  levelOptions: () => LEVEL_OPTIONS,
  setLevel,
  keywordValue: () => keyword.value,
  setKeyword,
  pausedValue: () => paused.value,
  togglePause,
  clearBuffer,
  statusValue: () => status.value,
  errorCount: () => errorCount.value,
  warnCount: () => warnCount.value,
})

onMounted(async () => {
  await loadStatus()
  await loadHistory()
  connect()
})

// 被 KeepAlive 换回来时，若本来就在跟随最新，就重新贴到底部
onActivated(() => { if (following.value) scrollToBottom() })

onBeforeUnmount(closeStream)
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <Panel>
      <div ref="box" class="logbox" @scroll="onScroll">
        <Empty v-if="!filtered.length" text="还没有日志" hint="等一会儿，或降低级别筛选" />
        <div
          v-for="e in filtered"
          :key="e.seq"
          class="line"
          :class="'lv-' + e.level.toLowerCase()"
        >
          <span class="ts">{{ shortTime(e.ts) }}</span>
          <span class="lv">{{ e.level }}</span>
          <span class="msg">{{ e.message }}</span>
          <pre v-if="e.throwable" class="tb">{{ e.throwable }}</pre>
        </div>
      </div>

      <div class="foot">
        <span v-if="following" class="meta faint">正在跟随最新</span>
        <Button v-else size="sm" @click="jumpToLatest">跳到最新</Button>
      </div>
    </Panel>
  </div>
</template>

<style scoped>
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }
.meta { font-size: var(--fs-meta); }

/* 日志是等宽密集文本，共享表格/面板都不适用。
   容器用下沉表面表达"这是一块滚动内容"，边界只给一道柔和边缘，不描重线。 */
.logbox {
  height: 58vh; overflow-y: auto;
  background: var(--surface-inset);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-sm);
  padding: var(--sp-2);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  line-height: 1.65;
  box-shadow: var(--shadow-sunken);
}

.line { display: grid; grid-template-columns: 62px 46px 1fr; gap: 8px; padding: 1px 4px; border-radius: var(--r-xs); }
.line:hover { background: var(--surface-hover); }
.ts { color: var(--ink-faint); }
.lv { font-weight: 600; }
.msg { color: var(--ink); white-space: pre-wrap; word-break: break-word; }

/* 异常块是日志独有的信息形态：左边一道深锈线标出"这里出错了"，
   它不是容器的边框，所以保留。 */
.tb {
  grid-column: 3; margin: 2px 0 4px; padding: 6px 8px;
  background: var(--surface-inset); border-left: 2px solid var(--rust-deep);
  border-radius: var(--r-xs); color: var(--ink-dim); font-size: var(--fs-meta);
  white-space: pre-wrap; overflow-x: auto;
}

/* 级别配色：整行淡淡的底 + 级别字变色，扫一眼就能定位 ERROR/WARN */
.lv-error { background: var(--rust-veil); }
.lv-error .lv { color: var(--rust); }
.lv-warn { background: var(--amber-veil); }
.lv-warn .lv { color: var(--amber); }
.lv-info .lv { color: var(--moss); }
.lv-debug .lv { color: var(--ink-faint); }

.foot { margin-top: var(--sp-3); text-align: center; }
</style>
