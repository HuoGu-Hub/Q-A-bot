<script setup lang="ts">
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
    } catch {   }
  })
  source.onerror = () => {   }
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
    await adminApi.del('/logs')
    entries.value = []
    await loadStatus()
  } catch (e) { error.value = describeError(e) }
}

watch(level, async () => {
  await loadHistory()
  connect()
})

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

onActivated(() => { if (following.value) scrollToBottom() })

onBeforeUnmount(closeStream)
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <Panel>
      <div ref="box" class="logbox" @scroll="onScroll">
        <Empty v-if="!filtered.length" text="还没有日志" />
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

.tb {
  grid-column: 3; margin: 2px 0 4px; padding: 6px 8px;
  background: var(--surface-inset); border-left: 2px solid var(--rust-deep);
  border-radius: var(--r-xs); color: var(--ink-dim); font-size: var(--fs-meta);
  white-space: pre-wrap; overflow-x: auto;
}

.lv-error { background: var(--rust-veil); }
.lv-error .lv { color: var(--rust); }
.lv-warn { background: var(--amber-veil); }
.lv-warn .lv { color: var(--amber); }
.lv-info .lv { color: var(--moss); }
.lv-debug .lv { color: var(--ink-faint); }

.foot { margin-top: var(--sp-3); text-align: center; }
</style>
