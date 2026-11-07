<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { LlmUsageDay, LlmUsageReport } from '@shared/api/types'
import { num, pct } from '@shared/utils/format'
import Panel from '@shared/ui/Panel.vue'
import Notice from '@shared/ui/Notice.vue'
import Stat from '@shared/ui/Stat.vue'
import Empty from '@shared/ui/Empty.vue'
import DataTable from '@shared/ui/DataTable.vue'

const props = defineProps<{ days: number }>()

const loading = ref(false)
const error = ref('')
const data = ref<LlmUsageReport | null>(null)

let active = true
let dirty = false
let started = false

const total = computed(() => {
  const t = data.value?.totals
  return t ? t.tokensIn + t.tokensOut : 0
})

const estimated = computed(() => {
  const t = data.value?.totals
  return t ? t.estimatedIn + t.estimatedOut : 0
})

const estimatedShare = computed(() => (total.value === 0 ? 0 : estimated.value / total.value))

const avgPerDay = computed(() => {
  const t = data.value?.totals
  return t && t.days > 0 ? total.value / t.days : 0
})

const maxDay = computed(() => Math.max(1, ...(data.value?.daily ?? []).map((d) => d.tokensIn + d.tokensOut)))

const peakDay = computed<LlmUsageDay | null>(() => {
  const days = data.value?.daily ?? []
  return days.reduce<LlmUsageDay | null>((best, d) => {
    const n = d.tokensIn + d.tokensOut
    if (n === 0) return best
    const bn = best ? best.tokensIn + best.tokensOut : -1
    return n > bn ? d : best
  }, null)
})

const rangeLabel = computed(() => {
  const days = data.value?.daily ?? []
  if (!days.length) return ''
  return `${days[0].day} ~ ${days[days.length - 1].day}`
})

const dense = computed(() => (data.value?.daily.length ?? 0) > 90)

const hasData = computed(() => (data.value?.totals.calls ?? 0) > 0)

const todayUsage = computed(() => {
  const days = data.value?.daily ?? []
  const today = days[days.length - 1]
  return today ? today.tokensIn + today.tokensOut : 0
})

const dayTotal = (d: LlmUsageDay) => d.tokensIn + d.tokensOut

const dayEstimated = (d: LlmUsageDay) => d.estimatedIn + d.estimatedOut

const barHeight = (d: LlmUsageDay) => {
  const n = dayTotal(d)
  return n === 0 ? '0' : Math.max(3, (n / maxDay.value) * 100) + '%'
}

const inShare = (d: LlmUsageDay) => {
  const n = dayTotal(d)
  return n === 0 ? 0 : (d.tokensIn / n) * 100
}

const dayTip = (d: LlmUsageDay) => {
  const est = dayEstimated(d)
  return `${d.day} · 合计 ${num(dayTotal(d))}（入 ${num(d.tokensIn)} / 出 ${num(d.tokensOut)}）`
    + ` · 调用 ${d.calls}`
    + (est > 0 ? ` · 其中估算 ${num(est)}` : '')
}

const modelTotal = (m: { tokensIn: number; tokensOut: number }) => m.tokensIn + m.tokensOut

const modelEstimated = (m: { estimatedIn: number; estimatedOut: number }) =>
  m.estimatedIn + m.estimatedOut

const modelShare = (m: { tokensIn: number; tokensOut: number }) =>
  total.value === 0 ? 0 : (modelTotal(m) / total.value) * 100

async function load() {
  started = true
  loading.value = true
  error.value = ''
  try {
    data.value = await adminApi.get<LlmUsageReport>(`/llm/usage?days=${props.days}`)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

watch(() => props.days, () => {
  if (active) load()
  else dirty = true
})

onMounted(load)

onDeactivated(() => {
  active = false
})

onActivated(() => {
  active = true
  if (dirty || !started) {
    dirty = false
    load()
  }
})

onBeforeUnmount(() => {
  active = false
})

defineExpose({
  reload: load,
  isLoading: () => loading.value,
})
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <template v-if="data">
      <div class="kpis">
        <Stat label="token 总量" :value="num(total)" :hint="rangeLabel" tone="flame" />
        <Stat label="输入 / 输出" :value="`${num(data.totals.tokensIn)} / ${num(data.totals.tokensOut)}`" tone="mist" />
        <Stat label="调用次数" :value="num(data.totals.calls)" hint="一次回答可能含多轮工具调用" tone="mist" />
        <Stat label="今日 token" :value="num(todayUsage)" hint="含还没落库的这一分钟" tone="mist" />
        <Stat
          label="估算占比"
          :value="pct(estimatedShare)"
          :hint="`估算 ${num(estimated)} token`"
          :tone="estimated > 0 ? 'rust' : 'moss'"
        />
        <Stat label="日均 token" :value="num(Math.round(avgPerDay))" :hint="`窗口 ${data.totals.days} 天`" tone="mist" />
        <Stat label="单日峰值" :value="num(peakDay ? dayTotal(peakDay) : 0)" :hint="peakDay?.day ?? ''" tone="mist" />
      </div>

      <Panel title="每日 token" :hint="dense ? '每日一根，悬停看明细' : ''">
        <Empty v-if="!hasData" text="还没有用量数据" hint="调用过模型后，这里按天累积" />

        <template v-else>
          <div class="legend">
            <span class="lg"><i class="sw sw-in" />输入</span>
            <span class="lg"><i class="sw sw-out" />输出</span>
            <span v-if="estimated > 0" class="lg"><i class="sw sw-est" />含估算</span>
          </div>

          <div
            class="plot"
            :class="{ dense }"
            role="img"
            :aria-label="`每日 token 用量，峰值 ${peakDay ? dayTotal(peakDay) : 0}`"
          >
            <i
              v-for="d in data.daily"
              :key="d.day"
              class="bar"
              :class="{ 'bar--est': dayEstimated(d) > 0 }"
              :style="{ height: barHeight(d) }"
              :title="dayTip(d)"
            >
              <span class="seg seg-in" :style="{ height: inShare(d) + '%' }" />
              <span class="seg seg-out" :style="{ height: 100 - inShare(d) + '%' }" />
            </i>
          </div>
          <div class="axis">
            <span class="faint num">{{ data.daily[0]?.day }}</span>
            <span class="faint num">{{ data.daily[data.daily.length - 1]?.day }}</span>
          </div>
        </template>
      </Panel>

      <Panel title="按模型" :count="data.models.length">
        <Empty v-if="!data.models.length" text="还没有模型用量" />
        <DataTable v-else cards :rows="data.models.length" label="按模型的 token 用量">
          <thead>
            <tr>
              <th>模型</th>
              <th class="num">调用</th>
              <th class="num">输入</th>
              <th class="num">输出</th>
              <th class="num">合计</th>
              <th>占比</th>
              <th>来源</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in data.models" :key="m.provider + '/' + m.model">
              <td data-label="模型">{{ m.provider }} / {{ m.model }}</td>
              <td class="num" data-label="调用">{{ num(m.calls) }}</td>
              <td class="num" data-label="输入">{{ num(m.tokensIn) }}</td>
              <td class="num" data-label="输出">{{ num(m.tokensOut) }}</td>
              <td class="num" data-label="合计">{{ num(modelTotal(m)) }}</td>
              <td data-label="占比">
                <span class="share" :title="pct(modelShare(m) / 100)">
                  <i :style="{ width: Math.max(2, modelShare(m)) + '%' }" />
                </span>
              </td>
              <td data-label="来源">
                <span v-if="modelEstimated(m) > 0" class="tag-est">含估算</span>
                <span v-else class="tag-real">实测</span>
              </td>
            </tr>
          </tbody>
        </DataTable>
      </Panel>

      <p class="note">
        厂商在响应里回 usage 时按实测记账；只有没回时才按字符估算，并标成「估算」。
        OpenCode Go 的默认模型会回 usage，正常情况下这里的数字是实测值。
        估算规则：中文约 0.85 token / 字、英文约 4 字符 1 token，另加消息与工具定义的结构开销。
      </p>
    </template>

    <div v-else-if="!error && loading" class="muted">读取中…</div>
  </div>
</template>

<style scoped>
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

.kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: var(--sp-3); }

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2) var(--sp-4);
  margin-bottom: var(--sp-3);
  font-size: var(--fs-meta);
  color: var(--ink-dim);
}
.lg { display: inline-flex; align-items: center; gap: 6px; }
.sw { width: 9px; height: 9px; border-radius: 2px; flex: none; }
.sw-in { background: var(--moss); }
.sw-out { background: var(--flame); }
.sw-est { background: linear-gradient(135deg, var(--rust) 50%, transparent 50%); }

.plot {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: minmax(0, 1fr);
  gap: 2px;
  align-items: end;
  height: 160px;
  border-bottom: 1px solid var(--line-strong);
}
.plot.dense { gap: 0; }

.bar {
  display: flex;
  flex-direction: column-reverse;
  width: 100%;
  min-height: 0;
  border-radius: 2px 2px 0 0;
  overflow: hidden;
  transition: filter var(--dur-fast) var(--ease);
}
.bar:hover { filter: brightness(1.25); }
.bar--est { box-shadow: inset 0 0 0 1px var(--rust); }

.seg { display: block; width: 100%; }
.seg-in { background: linear-gradient(180deg, var(--moss), var(--moss-deep)); }
.seg-out { background: linear-gradient(180deg, var(--flame), var(--flame-deep)); }

.axis {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
  font-size: var(--fs-meta);
}

.share {
  display: block;
  width: 100%;
  max-width: 140px;
  height: 6px;
  border-radius: var(--r-pill);
  background: var(--stone-void);
  box-shadow: var(--bevel-inset);
  overflow: hidden;
}
.share i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--flame-deep), var(--flame));
}

.tag-est,
.tag-real {
  display: inline-block;
  padding: 0 7px;
  border-radius: var(--r-pill);
  font-size: var(--fs-micro);
  line-height: 1.7;
  white-space: nowrap;
}
.tag-est {
  color: var(--blight-lift);
  background: var(--blight-veil);
  box-shadow: inset 0 0 0 1px var(--blight-deep);
}
.tag-real {
  color: var(--ink-3);
  background: var(--surface-raised);
  box-shadow: inset 0 0 0 1px var(--edge-soft);
}

.note {
  margin: 0;
  font-size: var(--fs-meta);
  color: var(--ink-3);
  line-height: 1.7;
}

@media (max-width: 760px) {
  .plot { height: 120px; }
  .kpis { gap: var(--sp-2); }
}
</style>