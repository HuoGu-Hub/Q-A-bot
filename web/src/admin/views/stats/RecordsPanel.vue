<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { RecordRow } from '@shared/api/types'
import { cosine as fmtCosine, ms, shortTime, VERDICT_LABEL, verdictTone } from '@shared/utils/format'
import { sourceHint, sourceLabel } from '@shared/utils/source'
import Button from '@shared/ui/Button.vue'
import Icon from '@shared/ui/Icon.vue'
import Notice from '@shared/ui/Notice.vue'
import Tag from '@shared/ui/Tag.vue'

const props = defineProps<{ days: number }>()

type Scope = 'replied' | 'dropped' | 'all'
type OutcomeKey = 'auto' | 'pass' | 'drop' | 'fixed'
type Tone = 'hit' | 'miss' | 'hold' | 'call'

const scope = ref<Scope>('replied')
const q = ref('')
const rows = ref<RecordRow[]>([])
const total = ref(0)

const PAGE = 20
const offset = ref(0)
const loading = ref(false)
const error = ref('')
const busyId = ref<number | null>(null)
const expanded = ref<number | null>(null)

const INTENT_LABEL: Record<string, string> = {
  CALL: '呼叫',
  KEYWORD: '关键词',
  QUESTION: '疑问',
  CHAT: '闲聊',
  NOISE: '噪音',
}

const scopeLabel = computed(() => {
  if (scope.value === 'dropped') return '未触发'
  if (scope.value === 'all') return '全部'
  return '已回复'
})

const pageLabel = computed(() => {
  if (!total.value) return '0 条'
  return (offset.value + 1) + '–' + Math.min(offset.value + PAGE, total.value) + ' / ' + total.value
})
const canPrev = computed(() => offset.value > 0)
const canNext = computed(() => offset.value + PAGE < total.value)

const days = computed(() => {
  const map = new Map<string, RecordRow[]>()
  for (const r of rows.value) {
    const day = (shortTime(r.ts) || '未知日期').slice(0, 5)
    const list = map.get(day)
    if (list) list.push(r)
    else map.set(day, [r])
  }
  return Array.from(map, ([day, list]) => ({ day, rows: list }))
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const params = new URLSearchParams({
      days: String(props.days),
      limit: String(PAGE),
      offset: String(offset.value),
      scope: scope.value,
    })
    const term = q.value.trim()
    if (term) params.set('q', term)
    const r = await adminApi.get<{ rows: RecordRow[]; total: number; scope: string }>(
      '/records?' + params.toString())
    rows.value = r.rows
    total.value = r.total
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

let searchTimer: number | undefined
function onSearch() {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    offset.value = 0
    expanded.value = null
    if (active) load()
    else dirty = true
  }, 320)
}

function clearSearch() {
  if (!q.value) return
  q.value = ''
  window.clearTimeout(searchTimer)
  offset.value = 0
  expanded.value = null
  if (active) load()
  else dirty = true
}

function goPage(delta: number) {
  if (loading.value) return
  const next = Math.max(0, offset.value + delta * PAGE)
  if (next === offset.value) return
  offset.value = next
  expanded.value = null
  load()
}

async function annotate(id: number, verdict: string) {
  busyId.value = id
  try {
    await adminApi.post('/annotate', { id, verdict })
    const row = rows.value.find(r => r.id === id)
    if (row) row.verdict = verdict
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busyId.value = null
  }
}

function toggle(id: number) {
  expanded.value = expanded.value === id ? null : id
}

let active = true
let dirty = false

function setScope(v: string) {
  const next: Scope = v === 'all' || v === 'dropped' ? v : 'replied'
  if (next === scope.value) return
  scope.value = next
  offset.value = 0
  expanded.value = null
  if (active) load()
  else dirty = true
}

watch(() => props.days, () => {
  offset.value = 0
  expanded.value = null
  if (active) load()
  else dirty = true
})

onActivated(() => {
  active = true
  if (dirty) {
    dirty = false
    load()
  }
})

onDeactivated(() => {
  active = false
})

onBeforeUnmount(() => window.clearTimeout(searchTimer))

function timeOf(ts: string): string {
  const t = shortTime(ts)
  return t.length > 6 ? t.slice(6) : t
}

function outcome(r: RecordRow): { key: OutcomeKey; label: string } {
  if (r.guardAction === 'auto') return { key: 'auto', label: '主动' }
  if (r.guardAction === 'drop') return { key: 'drop', label: '未触发' }
  if (r.guardAction === 'fixed_reply') return { key: 'fixed', label: '固定话术' }
  return { key: 'pass', label: '已答' }
}

function intentLabel(r: RecordRow): string {
  return INTENT_LABEL[r.triggerIntent] ?? r.triggerIntent
}

function chancePct(r: RecordRow): string {
  const v = Math.max(0, Math.min(1, r.triggerChance || 0))
  return Math.round(v * 100) + '%'
}

function rollPct(r: RecordRow): string {
  const v = Math.max(0, Math.min(1, r.triggerRoll || 0))
  return (v * 100).toFixed(1) + '%'
}

function showRoll(r: RecordRow): boolean {
  return r.triggerChance > 0 && r.triggerChance < 1 && r.triggerRoll > 0
}

function isMiss(r: RecordRow): boolean {
  if (r.guardAction !== 'drop') return false
  const reason = r.triggerReason || ''
  return reason.includes('未命中') || reason.includes('概率为 0') ||
    reason.includes('低概率') || reason.includes('该档') ||
    reason.includes('未解禁') || reason.includes('噪音') || reason.includes('群未启用')
}

function toneOf(r: RecordRow): Tone {
  if (r.guardAction === 'auto') return 'hit'
  if (isMiss(r)) return 'miss'
  if (r.guardAction === 'drop') return 'hold'
  if (r.triggerIntent === 'CALL' || r.triggerIntent === 'KEYWORD') return 'call'
  return 'hit'
}

function isChatDecision(r: RecordRow): boolean {
  return r.triggerIntent === 'CHAT' &&
    (r.triggerEvidence === '低频率闲聊' || r.triggerEvidence === '高频率闲聊')
}

function ruleText(r: RecordRow): string {
  if (!r.triggerIntent) return '没有触发记录'
  if (r.triggerIntent === 'CALL') {
    const e = r.triggerEvidence || r.triggerReason
    if (e === '被 @' || e === '呼叫') return '被 @ 叫到'
    if (e === '正文 @') return '正文 @ 叫到'
    if (e === '正文昵称') return '正文昵称叫到'
    if (e === '引用机器人') return '引用机器人叫到'
    return e
  }
  if (r.triggerEvidence) return r.triggerEvidence
  if (r.triggerIntent === 'KEYWORD') return '关键词命中'
  if (r.triggerIntent === 'QUESTION') return r.triggerReason || '疑问'
  return r.triggerReason || '—'
}

function decisionLabel(r: RecordRow): string {
  if (!r.triggerIntent) return '没有触发记录'
  if (r.guardAction === 'drop') {
    if (r.triggerReason === '命中') return '触发通过，后续拦截'
    if (r.triggerReason?.startsWith('影子模式')) return '影子模式'
    if (r.triggerReason === '未命中') return '掷点未命中'
    if (r.triggerReason === '低概率只回强游戏相关疑问') return '强游戏档未放行'
    if (r.triggerReason === '低概率只回游戏相关严格疑问') return '游戏严格档未放行'
    if (r.triggerReason === '低概率只回关键词或严格疑问') return '低档未放行'
    if (r.triggerReason === '游戏相关度不足') return '游戏相关不足'
    if (r.triggerReason === '该档只回游戏相关疑问') return '游戏档未放行'
    if (r.triggerReason === '该档只回严格疑问') return '只接严格疑问'
    if (r.triggerReason === '严格度不足') return '严格度不足'
    if (r.triggerReason === '该档只回疑问') return '未进入疑问档'
    if (r.triggerReason === '疑问强度不足') return '疑问不明确'
    if (r.triggerReason === '闲聊未解禁') return '闲聊未解禁'
    if (r.triggerReason === '概率过低') return '概率过低'
    if (r.triggerReason === '概率为 0') return '概率为 0'
    if (r.triggerReason === '噪音') return '噪音'
    if (r.triggerReason === '群未启用') return '群未启用'
    return r.triggerReason || '未触发'
  }
  if (r.triggerIntent === 'CALL') return '呼叫直通'
  if (r.triggerIntent === 'KEYWORD') return '关键词命中'
  return '命中'
}

function gaugeTitle(r: RecordRow): string {
  if (!r.triggerIntent) return '没有触发记录'
  const parts = ['闲聊', '接话概率 ' + chancePct(r)]
  if (showRoll(r)) parts.push('掷点 ' + (r.triggerRoll || 0).toFixed(2))
  parts.push(decisionLabel(r))
  return parts.join(' · ')
}

function canAnnotate(r: RecordRow): boolean {
  return r.guardAction !== 'drop' && r.guardAction !== 'fixed_reply'
}

defineExpose({
  reload: load,
  isLoading: () => loading.value,
  scopeValue: () => scope.value,
  setScope,
})

onMounted(load)
</script>

<template>
  <div class="records-page">
    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <section class="ledger texture-noise" aria-label="回复记录">
      <header class="ledger-head">
        <div class="ledger-stat">
          <b class="num">{{ total }}</b>
          <span>{{ scopeLabel }}</span>
        </div>

        <div class="legend" aria-hidden="true">
          <span><i class="dot dot--auto" />主动</span>
          <span><i class="dot dot--pass" />已答</span>
          <span><i class="dot dot--drop" />未触发</span>
        </div>

        <label class="search">
          <Icon name="search" :size="15" />
          <input
            v-model="q"
            type="search"
            class="search-input"
            placeholder="搜索消息内容"
            aria-label="搜索消息内容"
            @input="onSearch"
          >
          <button
            v-if="q"
            type="button"
            class="search-clear"
            aria-label="清除搜索"
            @click="clearSearch"
          >
            <Icon name="close" :size="12" />
          </button>
        </label>

        <div class="pager">
          <span class="faint num">{{ pageLabel }}</span>
          <Button size="sm" :disabled="!canPrev || loading" @click="goPage(-1)">上一页</Button>
          <Button size="sm" :disabled="!canNext || loading" @click="goPage(1)">下一页</Button>
        </div>
      </header>

      <div class="ledger-note">
        <span><b>直达</b> 呼叫、关键词、疑问不掷概率</span>
        <span class="note-sep">/</span>
        <span><b>闲聊</b> 接话概率与掷点，掷点小于概率才回复</span>
      </div>

      <div v-if="loading" class="ledger-empty">
        <span class="empty-line" />
        <p>读取中…</p>
      </div>

      <div v-else-if="!rows.length" class="ledger-empty">
        <Icon name="scroll" :size="28" />
        <p>{{ q ? '没有匹配的消息' : '还没有' + scopeLabel + '记录' }}</p>
        <p class="faint">{{ q ? '换一个关键词试试' : '新的消息会出现在这里' }}</p>
      </div>

      <div v-else class="ledger-body">
        <section v-for="day in days" :key="day.day" class="day">
          <header class="day-head">
            <span class="day-label num">{{ day.day }}</span>
            <span class="day-count num">{{ day.rows.length }}</span>
            <span class="day-rule" />
          </header>

          <article
            v-for="r in day.rows"
            :key="r.id"
            class="rec"
            :class="'rec--' + outcome(r).key"
          >
            <time class="rec-time num">{{ timeOf(r.ts) }}</time>
            <div class="rec-rail"><span class="rec-dot" /></div>

            <div class="rec-main">
              <div class="rec-top">
                <span class="badge" :class="'badge--' + outcome(r).key">{{ outcome(r).label }}</span>
                <button
                  type="button"
                  class="rec-q"
                  :aria-expanded="expanded === r.id"
                  :title="expanded === r.id ? '收起回答' : '展开回答'"
                  @click="toggle(r.id)"
                >
                  <span>{{ r.question || '(原文已清理)' }}</span>
                  <Icon :name="expanded === r.id ? 'chevron-down' : 'chevron-right'" :size="14" />
                </button>
              </div>

              <div class="rec-where">
                <span>群 {{ r.groupId }}</span>
                <span class="sep">·</span>
                <span>用户 {{ r.userId }}</span>
                <template v-if="r.guardAction === 'fixed_reply'">
                  <span class="sep">·</span>
                  <span>固定话术</span>
                </template>
              </div>

              <div class="decision" :class="'decision--' + toneOf(r)">
                <template v-if="isChatDecision(r)">
                  <span class="intent">闲聊</span>
                  <span v-if="r.triggerEvidence" class="tier">{{ r.triggerEvidence }}</span>
                  <span class="gauge" :title="gaugeTitle(r)">
                    <span class="gauge-fill" :style="{ width: chancePct(r) }" />
                    <span v-if="showRoll(r)" class="gauge-tick" :style="{ left: rollPct(r) }" />
                  </span>
                  <span class="chance num">接话 {{ chancePct(r) }}</span>
                  <span v-if="showRoll(r)" class="roll num">掷点 {{ (r.triggerRoll || 0).toFixed(2) }}</span>
                  <span class="decision-label">{{ decisionLabel(r) }}</span>
                </template>
                <template v-else-if="r.triggerIntent">
                  <span class="evidence">{{ ruleText(r) }}</span>
                  <span v-if="r.guardAction === 'drop'" class="decision-label">{{ decisionLabel(r) }}</span>
                </template>
                <span v-else class="evidence">没有触发记录</span>
              </div>

              <div class="rec-foot">
                <div class="rec-metrics">
                  <span>资料 <b class="num">{{ r.hitCount }}</b></span>
                  <span>余弦 <b class="num">{{ fmtCosine(r.bestCosine) }}</b></span>
                  <span :title="sourceHint(r.sources)">{{ sourceLabel(r.sources) }}</span>
                  <span>{{ ms(r.totalMs) }}</span>
                </div>

                <div v-if="canAnnotate(r)" class="rec-tools">
                  <Tag :tone="verdictTone(r.verdict)">
                    {{ VERDICT_LABEL[r.verdict ?? 'unknown'] ?? r.verdict }}
                  </Tag>
                  <button
                    type="button"
                    class="mark"
                    :class="{ 'mark--good': r.verdict === 'good' }"
                    :disabled="busyId === r.id"
                    aria-label="标记为答对"
                    title="标记为答对"
                    @click="annotate(r.id, 'good')"
                  >
                    <Icon name="thumbs-up" :size="14" />
                  </button>
                  <button
                    type="button"
                    class="mark"
                    :class="{ 'mark--bad': r.verdict === 'bad' }"
                    :disabled="busyId === r.id"
                    aria-label="标记为答错"
                    title="标记为答错"
                    @click="annotate(r.id, 'bad')"
                  >
                    <Icon name="thumbs-down" :size="14" />
                  </button>
                </div>
              </div>

              <div v-if="expanded === r.id" class="rec-detail">
                <div class="detail-block">
                  <span class="detail-label">回答</span>
                  <p class="answer">{{ r.answer || '(原文已清理)' }}</p>
                </div>
                <div class="detail-meta">
                  <span>检索 {{ ms(r.retrieveMs) }}</span>
                  <span>总耗时 {{ ms(r.totalMs) }}</span>
                  <span v-if="r.triggerIntent">触发 {{ intentLabel(r) }}</span>
                </div>
              </div>
            </div>
          </article>
        </section>
      </div>
    </section>
  </div>
</template>

<style scoped>
.records-page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}

.ledger {
  background: var(--stone-300);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-md);
  overflow: hidden;
  box-shadow: var(--bevel-raised);
}

.ledger-head {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  padding: 14px 18px;
  border-bottom: 1px solid var(--hairline);
  background: var(--stone-400);
}

.ledger-stat {
  display: flex;
  align-items: baseline;
  gap: 8px;
  white-space: nowrap;
}
.ledger-stat b {
  font-size: 24px;
  line-height: 1;
  font-weight: var(--fw-semi);
  color: var(--ink);
}
.ledger-stat span {
  font-size: var(--fs-meta);
  letter-spacing: var(--tracking-label);
  color: var(--ink-3);
}

.legend {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: var(--fs-xs);
  color: var(--ink-3);
  white-space: nowrap;
}
.legend span { display: inline-flex; align-items: center; gap: 5px; }
.dot { width: 6px; height: 6px; border-radius: 50%; }
.dot--auto { background: var(--ember); }
.dot--pass { background: var(--vital); }
.dot--drop { background: var(--mist); }

.search {
  display: flex;
  align-items: center;
  gap: 7px;
  width: min(260px, 28vw);
  margin-left: auto;
  padding: 7px 9px;
  background: var(--stone-void);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-sm);
  color: var(--ink-3);
  transition: border-color var(--dur-fast) var(--ease);
}
.search:focus-within { border-color: var(--ember); }
.search-input {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: 0;
  background: none;
  color: var(--ink);
  font: inherit;
  font-size: var(--fs-xs);
}
.search-input::placeholder { color: var(--ink-4); }
.search-input::-webkit-search-cancel-button { display: none; }
.search-clear {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: var(--surface-hover);
  color: var(--ink-2);
  cursor: pointer;
}
.search-clear:hover { color: var(--ink); }

.pager {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}
.pager .faint { font-size: var(--fs-xs); }

.ledger-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 220px;
  padding: 40px 20px;
  color: var(--ink-3);
  text-align: center;
}
.ledger-empty p { margin: 0; font-size: var(--fs-sm); color: var(--ink-2); }
.ledger-empty .faint { font-size: var(--fs-xs); color: var(--ink-4); }
.empty-line {
  width: 34px;
  height: 1px;
  background: var(--edge);
}

.ledger-note {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  padding: 8px 18px;
  border-bottom: 1px solid var(--hairline);
  font-size: 10px;
  color: var(--ink-4);
}
.ledger-note b { color: var(--ink-2); font-weight: var(--fw-medium); }
.note-sep { opacity: .4; }

.ledger-body { padding-bottom: 6px; }

.day { padding: 0 18px; }
.day-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 0 7px;
}
.day-label {
  font-size: var(--fs-xs);
  color: var(--ink-2);
  letter-spacing: .08em;
}
.day-count {
  font-size: 10px;
  color: var(--ink-4);
}
.day-rule {
  height: 1px;
  flex: 1;
  background: var(--hairline);
}

.rec {
  display: grid;
  grid-template-columns: 52px 16px minmax(0, 1fr);
  gap: 0 10px;
  padding: 11px 8px 12px 0;
  border-top: 1px solid var(--hairline);
  transition: background var(--dur-fast) var(--ease);
}
.rec:hover { background: var(--surface-hover); }
.rec:first-of-type { border-top: 0; }

.rec-time {
  padding-top: 2px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--ink-3);
  white-space: nowrap;
  text-align: right;
}

.rec-rail {
  position: relative;
  display: flex;
  justify-content: center;
}
.rec-rail::before {
  content: '';
  position: absolute;
  top: -12px;
  bottom: -13px;
  width: 1px;
  background: var(--hairline);
}
.rec:first-of-type .rec-rail::before { top: 7px; }
.rec:last-of-type .rec-rail::before { bottom: 50%; }

.rec-dot {
  position: relative;
  z-index: 1;
  width: 7px;
  height: 7px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--stone-600);
  box-shadow: 0 0 0 3px var(--stone-300);
}
.rec--auto .rec-dot { background: var(--ember); }
.rec--pass .rec-dot { background: var(--vital); }
.rec--drop .rec-dot { background: var(--mist); }
.rec--fixed .rec-dot { background: var(--warn); }

.rec-main { min-width: 0; }

.rec-top {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
}
.badge {
  flex: none;
  margin-top: 2px;
  padding: 1px 6px;
  border: 1px solid var(--edge);
  border-radius: var(--r-xs);
  font-size: 10px;
  line-height: 1.6;
  letter-spacing: .06em;
  color: var(--ink-2);
  white-space: nowrap;
}
.badge--auto { color: var(--ember); border-color: color-mix(in srgb, var(--ember) 38%, transparent); background: var(--ember-veil); }
.badge--pass { color: var(--vital); border-color: color-mix(in srgb, var(--vital) 38%, transparent); background: var(--vital-veil); }
.badge--drop { color: var(--mist); border-color: color-mix(in srgb, var(--mist) 35%, transparent); background: var(--mist-veil); }
.badge--fixed { color: var(--warn); border-color: color-mix(in srgb, var(--warn) 38%, transparent); background: var(--warn-veil); }

.rec-q {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
  flex: 1;
  padding: 0;
  border: 0;
  background: none;
  color: var(--ink);
  font: inherit;
  font-size: var(--fs-body);
  line-height: 1.55;
  text-align: left;
  cursor: pointer;
  border-radius: var(--r-xs);
}
.rec-q > span {
  min-width: 0;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.rec-q[aria-expanded="true"] > span { display: block; overflow: visible; }
.rec-q :deep(svg) {
  flex: none;
  margin-top: 4px;
  margin-left: auto;
  color: var(--ink-4);
}
.rec-q:hover { color: var(--ember-hot); }
.rec-q:focus-visible {
  outline: 1px solid var(--ember);
  outline-offset: 3px;
}

.rec-where {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 3px;
  font-size: 10px;
  color: var(--ink-4);
  font-variant-numeric: tabular-nums;
}
.rec-where .sep { opacity: .5; }

.decision {
  display: flex;
  align-items: center;
  gap: 8px;
  width: fit-content;
  max-width: 100%;
  margin-top: 8px;
  padding: 5px 9px;
  border: 1px solid var(--hairline);
  border-left: 2px solid var(--stone-600);
  border-radius: var(--r-xs);
  background: var(--stone-200);
  font-size: 11px;
  color: var(--ink-3);
}
.decision--hit { border-left-color: var(--vital); }
.decision--miss { border-left-color: var(--blight); }
.decision--hold { border-left-color: var(--warn); }
.decision--call { border-left-color: var(--ember); }

.intent {
  flex: none;
  color: var(--ink-2);
  letter-spacing: .04em;
}
.tier {
  flex: none;
  color: var(--ink-4);
  font-size: 10px;
}
.evidence {
  min-width: 0;
  color: var(--ink-2);
  line-height: 1.5;
}
.gauge {
  position: relative;
  flex: none;
  width: 126px;
  height: 4px;
  border-radius: var(--r-pill);
  background: var(--stone-600);
  overflow: visible;
}
.gauge-fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--ember);
  transition: width var(--dur-slow) var(--ease);
}
.decision--miss .gauge-fill { background: var(--blight); }
.gauge-tick {
  position: absolute;
  top: -4px;
  width: 2px;
  height: 12px;
  margin-left: -1px;
  border-radius: 1px;
  background: var(--ink);
  box-shadow: 0 0 0 1px var(--stone-200);
}
.decision--miss .gauge-tick { background: var(--blight-lift); }

.chance {
  flex: none;
  width: auto;
  color: var(--ink-2);
  white-space: nowrap;
  text-align: right;
}
.roll {
  flex: none;
  color: var(--ink-4);
  font-size: 10px;
}
.decision-label {
  flex: none;
  margin-left: 4px;
  padding-left: 9px;
  border-left: 1px solid var(--hairline);
  color: var(--ink-2);
  white-space: nowrap;
}
.decision--hit .decision-label { color: var(--vital); }
.decision--miss .decision-label { color: var(--blight); }
.decision--hold .decision-label { color: var(--warn); }

.rec-foot {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-top: 9px;
  flex-wrap: wrap;
}
.rec-metrics {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 10px;
  color: var(--ink-4);
  font-variant-numeric: tabular-nums;
}
.rec-metrics b {
  color: var(--ink-2);
  font-weight: var(--fw-medium);
}
.rec-tools {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}
.mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-xs);
  background: transparent;
  color: var(--ink-3);
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}
.mark:hover:not(:disabled) { color: var(--ink); border-color: var(--edge); }
.mark--good {
  color: var(--vital);
  border-color: color-mix(in srgb, var(--vital) 45%, transparent);
  background: var(--vital-veil);
}
.mark--bad {
  color: var(--blight);
  border-color: color-mix(in srgb, var(--blight) 45%, transparent);
  background: var(--blight-veil);
}
.mark:disabled { opacity: .4; cursor: default; }

.rec-detail {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--hairline);
  animation: detail-in .16s var(--ease);
}
.detail-block { min-width: 0; }
.detail-label {
  display: block;
  margin-bottom: 4px;
  font-size: 10px;
  letter-spacing: var(--tracking-label);
  color: var(--ink-4);
}
.answer {
  margin: 0;
  max-height: 280px;
  overflow-y: auto;
  white-space: pre-wrap;
  font-size: var(--fs-sm);
  line-height: 1.72;
  color: var(--ink-2);
}
.detail-meta {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 8px;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--ink-4);
}

@keyframes detail-in {
  from { opacity: 0; transform: translateY(-3px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 1100px) {
  .ledger-head { flex-wrap: wrap; gap: 10px 14px; }
  .search { order: 4; width: 100%; margin-left: 0; }
  .pager { margin-left: auto; }
}

@media (max-width: 760px) {
  .ledger-head { padding: 12px 14px; }
  .ledger-note { padding: 8px 12px; }
  .legend { display: none; }
  .day { padding: 0 12px; }
  .rec { grid-template-columns: 44px 14px minmax(0, 1fr); gap: 0 6px; }
  .rec-q { font-size: var(--fs-sm); }
  .decision { flex-wrap: wrap; width: 100%; }
  .gauge { flex: 1; width: auto; min-width: 70px; }
  .decision-label { margin-left: auto; border-left: 0; padding-left: 0; }
  .rec-tools { width: 100%; margin-left: 0; justify-content: flex-end; }
}

@media (max-width: 520px) {
  .rec { grid-template-columns: 40px minmax(0, 1fr); }
  .rec-rail { display: none; }
  .rec-time { font-size: 10px; }
  .rec-where { flex-wrap: wrap; }
  .chance { width: 30px; }
  .roll { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  .rec-detail { animation: none; }
  .gauge-fill { transition: none; }
}
</style>
