<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, ref, watch } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { RecordRow } from '@shared/api/types'
import { cosine as fmtCosine, ms, shortTime, VERDICT_LABEL, verdictTone } from '@shared/utils/format'
import { guardLabel } from '@shared/utils/guard'
import { sourceHint, sourceLabel } from '@shared/utils/source'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Notice from '@shared/ui/Notice.vue'
import Tag from '@shared/ui/Tag.vue'
import Button from '@shared/ui/Button.vue'

const props = defineProps<{ days: number }>()
const scope = ref<'replied' | 'dropped' | 'all'>('replied')
const rows = ref<RecordRow[]>([])
const total = ref(0)

const PAGE = 20
const offset = ref(0)

const loading = ref(false)
const error = ref('')
const busyId = ref<number | null>(null)

const expanded = ref<number | null>(null)

const tableRows = computed(() =>
  !rows.value.length && loading.value ? undefined : rows.value.length
)

const pageLabel = computed(() => {
  if (!total.value) return '0 条'
  return (offset.value + 1) + '–' + Math.min(offset.value + PAGE, total.value) + ' / ' + total.value
})
const canPrev = computed(() => offset.value > 0)
const canNext = computed(() => offset.value + PAGE < total.value)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const r = await adminApi.get<{ rows: RecordRow[]; total: number; scope: string }>(
      `/records?days=${props.days}&limit=${PAGE}&offset=${offset.value}&scope=${scope.value}`)
    rows.value = r.rows
    total.value = r.total
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
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
  const next = v === 'all' || v === 'dropped' ? v : 'replied'
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

defineExpose({
  reload: load,
  isLoading: () => loading.value,
  scopeValue: () => scope.value,
  setScope,
})

const INTENT_LABEL: Record<string, string> = {
  CALL: '呼叫',
  KEYWORD: '关键词',
  QUESTION: '疑问',
  CHAT: '闲聊',
  NOISE: '噪音',
}

const scopeNote = computed(() => {
  if (scope.value === 'replied') return `共 ${total.value} 条已回复 · 主动接话也在里面`
  if (scope.value === 'dropped') return `共 ${total.value} 条未触发 · 看触发列的原因`
  return ''
})

function triggerSummary(r: RecordRow): string {
  if (!r.triggerIntent) return '—'
  if (r.triggerIntent === 'CALL') return '呼叫 · 必回'
  if (r.triggerIntent === 'KEYWORD') return '关键词 · 命中'
  const chance = Math.round((r.triggerChance || 0) * 100) + '%'
  const roll = r.triggerRoll > 0 ? ' · 掷 ' + r.triggerRoll.toFixed(2) : ''
  const label = INTENT_LABEL[r.triggerIntent] ?? r.triggerIntent
  return `${label} ${chance}${roll} · ${r.triggerReason || '—'}`
}

function triggerDetail(r: RecordRow): string {
  if (!r.triggerIntent) return '没有经过概率触发'
  const label = INTENT_LABEL[r.triggerIntent] ?? r.triggerIntent
  return `意图 ${label} · 概率 ${Math.round((r.triggerChance || 0) * 100)}% · ` +
    `掷点 ${(r.triggerRoll || 0).toFixed(2)} · 结果 ${r.triggerReason || '—'}`
}

onMounted(load)
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-else-if="scopeNote" tone="info">
      {{ scopeNote }} · 标记结果只用于统计
    </Notice>

    <Panel>
      <div class="pager">
        <span class="faint page">{{ pageLabel }}</span>
        <Button size="sm" :disabled="!canPrev || loading" @click="goPage(-1)">上一页</Button>
        <Button size="sm" :disabled="!canNext || loading" @click="goPage(1)">下一页</Button>
      </div>
      <DataTable
        class="records"
        cards
        :rows="tableRows"
        :empty="scope === 'replied' ? '还没有已回复记录' : scope === 'dropped' ? '还没有未触发记录' : '还没有任何消息记录'"
      >
        <thead>
          <tr>
            <th>时间</th><th>问题</th>
            <th title="意图 / 概率 / 掷点 / 结果">触发</th>
            <th class="num" title="这次回答用上了几条资料；0 = 知识库里没找到相关的内容">查到资料</th>
            <th class="num" title="问题和资料有多像（0~1，越高越像）。「-」表示这次没走向量检索">相关度</th>
            <th title="这次的知识是用哪种方式找到的">怎么找到的</th>
            <th title="人工给这条回答打的质量标签">质量评价</th>
            <th class="right" title="给这条回答打分，用来决定知识库该补什么">标记</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="r in rows" :key="r.id">
            <tr class="row">
              <td class="muted nowrap" data-label="时间">{{ shortTime(r.ts) }}</td>
              <td class="q" data-label="问题">
                <span class="q-wrap">
                  <Tag v-if="r.guardAction === 'auto'" tone="neutral">主动</Tag>
                  <Tag v-else-if="r.guardAction === 'drop'" tone="bad">未触发</Tag>
                  <button
                    type="button"
                    class="q-btn"
                    :aria-expanded="expanded === r.id"
                    :title="expanded === r.id ? '收起详情' : '展开详情'"
                    @click="toggle(r.id)"
                  >{{ r.question || '(原文已清理)' }}</button>
                </span>
              </td>
              <td class="trig" :title="triggerDetail(r)" data-label="触发">{{ triggerSummary(r) }}</td>
              <td class="num" :class="{ miss: r.hitCount === 0 }" data-label="查到资料">{{ r.hitCount }}</td>
              <td class="num" data-label="相关度">{{ fmtCosine(r.bestCosine) }}</td>
              <td class="muted" :title="sourceHint(r.sources)" data-label="怎么找到的">{{ sourceLabel(r.sources) }}</td>
              <td data-label="质量评价">
                <Tag :tone="verdictTone(r.verdict)">{{ VERDICT_LABEL[r.verdict ?? 'unknown'] ?? r.verdict }}</Tag>
              </td>
              <td class="right" data-label="标记" @click.stop>
                <Button size="sm" :disabled="busyId === r.id" @click="annotate(r.id, 'good')">对</Button>
                <Button size="sm" variant="danger" :disabled="busyId === r.id" @click="annotate(r.id, 'bad')">错</Button>
              </td>
            </tr>
            <tr v-if="expanded === r.id" class="detail-row">
              <td colspan="8">
                <div class="dpanel">
                  <div>
                    <div class="dlbl">问题</div>
                    <div class="dtext">{{ r.question || '(原文已清理)' }}</div>
                  </div>
                  <div>
                    <div class="dlbl">回答</div>
                    <div class="dtext">{{ r.answer || '(原文已清理)' }}</div>
                  </div>
                  <div>
                    <div class="dlbl">触发</div>
                    <div class="dtext">{{ triggerDetail(r) }}</div>
                  </div>
                  <div class="dmeta">
                    检索 {{ ms(r.retrieveMs) }} · 总耗时 {{ ms(r.totalMs) }} ·
                    状态 {{ guardLabel(r.guardAction) }} · 群 {{ r.groupId }} · 用户 {{ r.userId }}
                  </div>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </DataTable>
    </Panel>
  </div>
</template>

<style scoped>
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

.pager { display: flex; align-items: center; justify-content: flex-end; gap: var(--sp-2); margin-bottom: var(--sp-3); }
.pager .page { font-size: var(--fs-meta); }

.q-btn {
  display: block;
  width: 100%;
  background: none;
  border: 0;
  padding: 0;
  font: inherit;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
  border-radius: var(--r-xs);
}
.q-btn:hover { color: var(--ember-hot); }
.row:hover { background: var(--surface-hover); }

.q { max-width: 380px; }
.q-wrap { display: flex; align-items: center; gap: var(--sp-1); min-width: 0; }
.q-wrap .q-btn { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.trig {
  font-size: var(--fs-meta);
  color: var(--ink-dim);
  white-space: nowrap;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.nowrap { white-space: nowrap; }
.miss { color: var(--rust); }

.records :deep(th.right),
.records :deep(td.right) { text-align: right; white-space: nowrap; }
.records :deep(td.right .btn + .btn) { margin-left: var(--sp-1); }

.dpanel {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  background: var(--surface-inset);
  border-radius: var(--r-sm);
  box-shadow: var(--shadow-sunken);
  padding: var(--sp-3) var(--sp-4);
}
.dlbl {
  font-size: var(--fs-meta);
  letter-spacing: var(--tracking-label);
  color: var(--ink-faint);
  margin-bottom: var(--sp-1);
}
.dtext {
  font-size: var(--fs-body);
  line-height: 1.75;
  white-space: pre-wrap;
  max-height: 260px;
  overflow-y: auto;
}
.dmeta {
  font-size: var(--fs-meta);
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  color: var(--ink-faint);
}
</style>
