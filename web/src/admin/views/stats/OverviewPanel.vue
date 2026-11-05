<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type {
  Overview, KeywordStat, Bucket, VisitSummary,
  TrafficResponse, TrafficPoint, LlmResponse, LlmPoint,
} from '@shared/api/types'
import { ms, num, pct } from '@shared/utils/format'
import Panel from '@shared/ui/Panel.vue'
import Notice from '@shared/ui/Notice.vue'
import Stat from '@shared/ui/Stat.vue'
import Bar from '@shared/ui/Bar.vue'
import Empty from '@shared/ui/Empty.vue'

const REFRESH_MS = 60_000

const MIN_SCORE = 0.45

const props = defineProps<{ days: number }>()
const loading = ref(false)
const error = ref('')
const auto = ref(false)
const lastAt = ref<Date | null>(null)

const ov = ref<Overview | null>(null)
const keywords = ref<KeywordStat[]>([])
const cosineDist = ref<Bucket[]>([])
const visits = ref<VisitSummary | null>(null)

const traffic = ref<TrafficResponse | null>(null)

const llm = ref<LlmResponse | null>(null)

const updatedAt = computed(() => {
  const d = lastAt.value
  if (!d) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
})

const maxKw = computed(() => Math.max(1, ...keywords.value.map(k => k.count)))

const trafficPeak = computed(() => Math.max(1, ...(traffic.value?.points ?? []).map(p => p.requests)))

const curQps = computed(() => {
  const pts = traffic.value?.points ?? []
  const last = pts[pts.length - 1]
  return last ? (last.requests / 60).toFixed(2) : '0.00'
})

const peakQps = computed(() => ((traffic.value?.summary.peakPerMinute ?? 0) / 60).toFixed(2))

const apiShare = computed(() => {
  const s = traffic.value?.summary
  if (!s || s.requests === 0) return '—'
  return pct(s.api / s.requests)
})

const barHeight = (requests: number) => (requests === 0 ? '0' : Math.max(4, (requests / trafficPeak.value) * 100) + '%')

const llmPeak = computed(() => Math.max(1, ...(llm.value?.points ?? []).map(p => p.calls)))

const llmTokens = computed(() => {
  const s = llm.value?.summary
  return s ? s.tokensIn + s.tokensOut : 0
})

const lastLlm = computed<LlmPoint | null>(() => {
  const pts = llm.value?.points ?? []
  return pts.length ? pts[pts.length - 1] : null
})

const llmBarHeight = (calls: number) => (calls === 0 ? '0' : Math.max(4, (calls / llmPeak.value) * 100) + '%')

const llmTip = (p: LlmPoint) => {
  const d = new Date(p.minute * 60_000)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${hh}:${mm} · 调用 ${p.calls}（失败 ${p.failures}）· 入 ${p.tokensIn} / 出 ${p.tokensOut} tokens · P95 ${p.p95}ms`
}

const barTip = (p: TrafficPoint) => {
  const d = new Date(p.minute * 60_000)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${hh}:${mm} · 请求 ${p.requests}（API ${p.api}）· 错误 ${p.errors} · P95 ${p.p95}ms`
}

const cosine = computed(() => {
  const buckets = cosineDist.value.map((b) => {
    const [lo, hi] = b.range.split('~').map(Number)
    const side: 'blocked' | 'kept' | 'edge' =
      !Number.isFinite(lo) || !Number.isFinite(hi) ? 'edge'
        : hi <= MIN_SCORE ? 'blocked'
          : lo >= MIN_SCORE ? 'kept'
            : 'edge'
    return { ...b, lo, hi, side }
  })
  const sum = (f: (b: typeof buckets[number]) => boolean) =>
    buckets.filter(f).reduce((s, b) => s + b.count, 0)
  const peak = buckets.length ? buckets.reduce((a, b) => (b.count > a.count ? b : a)) : null
  return {
    buckets,
    peak,
    max: Math.max(1, ...buckets.map(b => b.count)),
    total: buckets.reduce((s, b) => s + b.count, 0),
    kept: sum(b => b.side === 'kept'),
    edge: sum(b => b.side === 'edge'),
    blocked: sum(b => b.side === 'blocked'),
  }
})

const cosineHint = computed(() =>
  cosine.value.peak && cosine.value.total ? `主要落在 ${cosine.value.peak.range}` : '暂无数据')

const daily = computed(() => {
  const raw = (ov.value?.daily ?? []).slice(-21)
  const n = raw.length
  const max = Math.max(1, ...raw.map(d => d.count))
  const step = Math.max(1, Math.ceil(n / 8))
  const labelAt = new Set<number>()
  for (let i = 0; i < n; i += step) labelAt.add(i)
  if (n) {
    labelAt.add(n - 1)
    for (const i of [...labelAt]) if (i !== n - 1 && n - 1 - i < step) labelAt.delete(i)
  }
  const pts = raw.map((d, i) => ({
    ...d,
    x: n <= 1 ? 50 : (i / (n - 1)) * 100,
    y: 100 - (d.count / max) * 84 - 8,
    showLabel: labelAt.has(i),
  }))
  const line = pts.map(p => `${p.x.toFixed(2)},${p.y.toFixed(2)}`).join(' ')
  return {
    pts,
    peak: pts.length ? pts.reduce((x, y) => (y.count > x.count ? y : x)) : null,
    line,
    area: pts.length ? `0,100 ${line} 100,100` : '',
  }
})

let timer: number | undefined
let active = true
let dirty = false

function syncTimer() {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
  if (active && auto.value) timer = window.setInterval(load, REFRESH_MS)
}

function toggleAuto(v: boolean) {
  auto.value = v
  syncTimer()
}

watch(() => props.days, () => {
  if (active) load()
  else dirty = true
})

onDeactivated(() => {
  active = false
  syncTimer()
})

onActivated(() => {
  active = true
  if (dirty) {
    dirty = false
    load()
  }
  syncTimer()
})

onBeforeUnmount(() => {
  if (timer !== undefined) window.clearInterval(timer)
})

async function load() {
  loading.value = true
  error.value = ''
  const q = `?days=${props.days}`
  try {
    const [o, k, c, v, t, l] = await Promise.all([
      adminApi.get<Overview>(`/overview${q}`),
      adminApi.get<KeywordStat[]>(`/keywords${q}&limit=8`),
      adminApi.get<Bucket[]>(`/cosine${q}`),
      adminApi.get<VisitSummary>(`/visits${q}`),
      adminApi.get<TrafficResponse>('/traffic?minutes=60'),
      adminApi.get<LlmResponse>('/llm?minutes=60'),
    ])
    ov.value = o
    keywords.value = k
    cosineDist.value = c
    visits.value = v
    traffic.value = t
    llm.value = l
    lastAt.value = new Date()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

defineExpose({
  reload: load,
  isLoading: () => loading.value,
  autoValue: () => auto.value,
  toggleAuto,
  updatedAt: () => updatedAt.value,
})
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <template v-if="ov">
      <div class="kpis">
        <Stat label="提问量" :value="num(ov.questions)" hint="@ 了机器人且被有效回应" tone="flame" />
        <Stat label="群消息总数" :value="num(ov.total)" hint="含未 @ 与被拦" tone="mist" />
        <Stat label="提问命中率" :value="pct(ov.hitRate)" hint="提问中检索到资料的比例" tone="moss" />
        <Stat label="提问用户 / 群" :value="`${ov.users} / ${ov.groups}`" tone="mist" />
        <Stat label="被拦 / 指令" :value="`${num(ov.dropped + ov.fixedReplies)} / ${num(ov.commands)}`" hint="被拦不含指令" tone="mist" />
        <Stat label="资料搜索耗时 P50 / P95" :value="`${ms(ov.p50RetrieveMs)} / ${ms(ov.p95RetrieveMs)}`"
              hint="中位数 / 最慢的 5%" tone="mist" />
        <Stat label="回答耗时 P50 / P95" :value="`${ms(ov.p50TotalMs)} / ${ms(ov.p95TotalMs)}`"
              hint="中位数 / 最慢的 5%" tone="mist" />
        <Stat label="后台访问" :value="num(visits?.total ?? 0)" tone="mist" />
      </div>

      <Panel title="流量 · 最近 60 分钟">
        <template v-if="traffic">
          <Empty v-if="traffic.summary.requests === 0" text="还没有流量数据" />

          <template v-else>
            <div class="tf-kpis">
              <div class="tf-k">
                <span class="tf-l">当前 QPS</span>
                <b class="tf-v num">{{ curQps }}</b>
              </div>
              <div class="tf-k">
                <span class="tf-l">峰值 QPS</span>
                <b class="tf-v num">{{ peakQps }}</b>
              </div>
              <div class="tf-k">
                <span class="tf-l">P95（窗口最差）</span>
                <b class="tf-v num">{{ ms(traffic.summary.worstP95) }}</b>
              </div>
              <div class="tf-k">
                <span class="tf-l">错误率</span>
                <b class="tf-v num">{{ pct(traffic.summary.errorRate) }}</b>
              </div>
            </div>

            <div class="tf-plot" role="img" :aria-label="`最近 60 分钟每分钟请求数，峰值 ${traffic.summary.peakPerMinute} 次`">
              <i
                v-for="pt in traffic.points"
                :key="pt.minute"
                class="tf-bar"
                :class="{ 'tf-bar--err': pt.errors > 0 }"
                :style="{ height: barHeight(pt.requests) }"
                :title="barTip(pt)"
              />
            </div>
            <div class="tf-axis">
              <span class="faint num">60 分钟前</span>
              <span class="faint num">现在</span>
            </div>

            <p class="tf-note">
              窗口内共 <b class="num">{{ num(traffic.summary.requests) }}</b> 次请求，其中 API
              <b class="num">{{ num(traffic.summary.api) }}</b> 次（{{ apiShare }}）。
              延迟只统计 API 请求。
            </p>
          </template>
        </template>
        <div v-else class="muted">读取中…</div>
      </Panel>

      <Panel title="模型调用 · 最近 60 分钟">
        <template v-if="llm">
          <Empty v-if="llm.summary.calls === 0" text="这段时间没有调用模型" />

          <template v-else>
            <div class="tf-kpis">
              <div class="tf-k">
                <span class="tf-l">调用（窗口）</span>
                <b class="tf-v num">{{ num(llm.summary.calls) }}</b>
              </div>
              <div class="tf-k">
                <span class="tf-l">token（窗口）</span>
                <b class="tf-v num">{{ num(llmTokens) }}</b>
              </div>
              <div class="tf-k">
                <span class="tf-l">失败率</span>
                <b class="tf-v num">{{ pct(llm.summary.failRate) }}</b>
              </div>
              <div class="tf-k">
                <span class="tf-l">P95（窗口最差）</span>
                <b class="tf-v num">{{ ms(llm.summary.worstP95) }}</b>
              </div>
            </div>

            <div
              class="tf-plot"
              role="img"
              :aria-label="`最近 60 分钟每分钟模型调用次数，峰值 ${llm.summary.peakPerMinute} 次`"
            >
              <i
                v-for="pt in llm.points"
                :key="pt.minute"
                class="tf-bar"
                :class="{ 'tf-bar--err': pt.failures > 0 }"
                :style="{ height: llmBarHeight(pt.calls) }"
                :title="llmTip(pt)"
              />
            </div>
            <div class="tf-axis">
              <span class="faint num">60 分钟前</span>
              <span class="faint num">现在</span>
            </div>

            <p class="tf-note">
              输入 <b class="num">{{ num(llm.summary.tokensIn) }}</b> · 输出
              <b class="num">{{ num(llm.summary.tokensOut) }}</b> tokens。
              一次回答可能含多轮工具调用，每轮单独计数。<template v-if="lastLlm">最近一分钟
              <b class="num">{{ lastLlm.calls }}</b> 次。</template>
            </p>
          </template>
        </template>
        <div v-else class="muted">读取中…</div>
      </Panel>

      <Panel title="关键词排行">
        <div v-if="!keywords.length"><Empty text="暂无数据" /></div>
        <div v-else class="bars">
          <Bar
            v-for="k in keywords"
            :key="k.zh"
            :label="k.zh"
            :value="k.count"
            :max="maxKw"
            :tone="k.missCount > 0 ? 'rust' : 'flame'"
          />
        </div>
      </Panel>

      <Panel title="最高余弦分布" :hint="`阈值参考 · ${cosineHint}`">
        <div class="cosine">
          <figure class="hist-fig">
            <div class="hist-vals">
              <span v-for="b in cosine.buckets" :key="b.range" class="hist-val num" :class="{ zero: !b.count }">{{ b.count }}</span>
            </div>
            <div class="hist-plot">
              <div class="hist-bars">
                <span
                  v-for="b in cosine.buckets"
                  :key="b.range"
                  class="h-bar"
                  :class="`side-${b.side}`"
                  :style="{ height: Math.max(b.count ? 3 : 1, (b.count / cosine.max) * 82) + '%' }"
                  :title="`${b.range} · ${b.count} 条`"
                />
              </div>
              <span class="hist-thr" :style="{ left: MIN_SCORE * 100 + '%' }">
                <i class="thr-tag">阈值 {{ MIN_SCORE.toFixed(2) }}</i>
              </span>
            </div>
            <div class="hist-axis">
              <span
                v-for="b in cosine.buckets"
                :key="b.range"
                class="h-lbl faint num"
                :title="b.range"
              >{{ b.range.split('~')[0] }}</span>
            </div>
          </figure>

          <div class="cosine-side">
            <div class="legend">
              <span class="lg"><i class="sw side-kept" />达到阈值 · {{ cosine.kept }}</span>
              <span class="lg"><i class="sw side-edge" />阈值所在档 · {{ cosine.edge }}</span>
              <span class="lg"><i class="sw side-blocked" />被挡（记 0）· {{ cosine.blocked }}</span>
            </div>
          </div>
        </div>
      </Panel>

      <Panel title="每日提问量" :hint="`最近 ${daily.pts.length} 天`">
        <div v-if="!daily.pts.length"><Empty text="暂无数据" /></div>
        <div v-else class="line">
          <div class="line-canvas">
            <svg class="line-svg" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
              <defs>
                <linearGradient id="daily-area" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" class="stop-top" />
                  <stop offset="100%" class="stop-bottom" />
                </linearGradient>
              </defs>
              <polygon class="line-area" :points="daily.area" fill="url(#daily-area)" />
              <polyline class="line-path" :points="daily.line" vector-effect="non-scaling-stroke" />
            </svg>
            <span
              v-for="p in daily.pts"
              :key="p.day"
              class="pt"
              :class="{ 'pt--peak': daily.peak?.day === p.day }"
              :style="{ left: p.x + '%', top: p.y + '%' }"
              :title="`${p.day} · ${p.count} 次`"
            >
              <i class="pt-dot" />
              <b v-if="daily.peak?.day === p.day" class="pt-val num">{{ p.count }}</b>
            </span>
          </div>
          <div class="line-axis">
            <span
              v-for="p in daily.pts"
              v-show="p.showLabel"
              :key="p.day"
              class="axis-lbl faint num"
              :style="{ left: p.x + '%' }"
            >{{ p.day.slice(5) }}</span>
          </div>
        </div>
      </Panel>
    </template>

    <div v-else-if="!error && loading" class="muted">读取中…</div>
  </div>
</template>

<style scoped>
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

.kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: var(--sp-3); }
.bars { display: flex; flex-direction: column; gap: var(--sp-2); }
.cosine {
  display: grid;
  grid-template-columns: minmax(0, 620px) minmax(220px, 1fr);
  gap: var(--sp-6);
  align-items: start;
}

.hist-fig { margin: 0; }
.hist-vals,
.hist-bars,
.hist-axis {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: minmax(0, 1fr);
  gap: 4px;
}
.hist-val { text-align: center; font-size: var(--fs-meta); color: var(--ink-dim); }
.hist-val.zero { color: var(--ink-faint); opacity: .55; }

.hist-plot {
  position: relative;
  height: 140px;
  margin-top: var(--sp-2);
  border-bottom: 1px solid var(--line-strong);
}
.hist-bars { position: absolute; inset: 0; align-items: end; }
.h-bar {
  justify-self: center;
  width: 100%;
  max-width: 34px;
  border-radius: var(--r-xs) var(--r-xs) 0 0;
  background: var(--mist);
  transition: height var(--dur-slow) var(--ease);
}
.side-kept { background: linear-gradient(180deg, var(--moss), var(--moss-deep)); }
.side-edge {
  background: linear-gradient(180deg, var(--flame-bright), var(--flame-deep));
  box-shadow: 0 0 10px var(--flame-glow);
}
.side-blocked { background: linear-gradient(180deg, var(--rust), var(--rust-deep)); }

.hist-thr {
  position: absolute; top: 0; bottom: 0; width: 0;
  border-left: 1px dashed var(--ink-faint);
  pointer-events: none;
}
.thr-tag {
  position: absolute; top: 0; left: 5px;
  font-size: var(--fs-meta); font-style: normal; color: var(--ink-dim);
  background: var(--surface-panel); padding: 0 4px; white-space: nowrap;
}
.hist-axis { margin-top: 6px; }
.h-lbl { text-align: center; font-size: var(--fs-meta); }

.tf-kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--sp-3);
  margin-bottom: var(--sp-4);
}
.tf-k { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.tf-l { font-size: var(--fs-xs); color: var(--ink-3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tf-v { font-size: var(--fs-lg); line-height: 1.2; color: var(--ink); font-weight: var(--fw-medium); }

.tf-plot {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: minmax(0, 1fr);
  gap: 2px;
  align-items: end;
  height: 96px;
  border-bottom: 1px solid var(--line-strong);
}
.tf-bar {
  display: block;
  width: 100%;
  border-radius: 1px 1px 0 0;
  background: var(--mist);
}
.tf-bar--err { background: var(--rust); }

.tf-axis {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
  font-size: var(--fs-meta);
}
.tf-note {
  margin: var(--sp-3) 0 0;
  font-size: var(--fs-meta);
  color: var(--ink-3);
  line-height: 1.7;
}

.cosine-side { display: flex; flex-direction: column; gap: var(--sp-3); }
.legend { display: flex; flex-wrap: wrap; gap: var(--sp-2) var(--sp-4); font-size: var(--fs-meta); color: var(--ink-dim); }
.lg { display: inline-flex; align-items: center; gap: 6px; }
.sw { width: 9px; height: 9px; border-radius: 2px; flex: none; }

.line { display: flex; flex-direction: column; gap: var(--sp-1); }
.line-canvas { position: relative; height: 170px; margin: var(--sp-2) var(--sp-3) 0; }
.line-svg { display: block; width: 100%; height: 100%; overflow: visible; }
.line-area { stroke: none; }
.stop-top { stop-color: var(--flame); stop-opacity: .32; }
.stop-bottom { stop-color: var(--flame); stop-opacity: 0; }
.line-path {
  fill: none;
  stroke: var(--flame-bright);
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
  filter: drop-shadow(0 0 6px var(--flame-glow));
}
.pt { position: absolute; width: 0; height: 0; transform: translate(-50%, -50%); }
.pt-dot {
  position: absolute; left: -3px; top: -3px;
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--bg-abyss); border: 1px solid var(--flame);
  transition: transform var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.pt:hover .pt-dot { background: var(--flame-bright); transform: scale(1.5); }
.pt--peak .pt-dot {
  background: var(--flame-bright); border-color: var(--flame-bright);
  box-shadow: 0 0 8px var(--flame-glow);
}
.pt-val {
  position: absolute; left: 0; bottom: 9px; transform: translateX(-50%);
  font-size: var(--fs-meta); font-weight: var(--fw-medium);
  color: var(--flame-bright); white-space: nowrap;
}
.line-axis { position: relative; height: 15px; margin: 0 var(--sp-3); }
.axis-lbl {
  position: absolute; top: 0; transform: translateX(-50%);
  font-size: var(--fs-meta); white-space: nowrap;
}

@media (max-width: 900px) {
  .cosine { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 560px) {
  .hist-val, .h-lbl { font-size: 10px; }
}
</style>
