<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type {
  Overview, KeywordStat, MissResponse, Bucket, SourcesResponse, VisitSummary,
} from '@shared/api/types'
import { cosine as fmtCosine, ms, num, pct, shortTime } from '@shared/utils/format'
import { sourceLabel } from '@shared/utils/source'
import AdminPage from '@shared/ui/AdminPage.vue'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Notice from '@shared/ui/Notice.vue'
import Select from '@shared/ui/Select.vue'
import Stat from '@shared/ui/Stat.vue'
import Bar from '@shared/ui/Bar.vue'
import Tag from '@shared/ui/Tag.vue'
import Empty from '@shared/ui/Empty.vue'
import Button from '@shared/ui/Button.vue'
import Switch from '@shared/ui/Switch.vue'

/**
 * 数据看板 —— 「大屏」和「看板」两张页面合并后的唯一入口。
 *
 * <p>合并的理由：两张页面 8 个指标里有 6 个一样，每日提问量 / 关键词排行 /
 * 检索来源 / 最高余弦分布 四块图完全重复，差别只是大屏固定 7 天、看板能选范围。
 * 现在一张页面看全，大屏那两处**有能力差异**的东西保留下来：
 *   · 自动刷新（60 秒）—— 挂在墙上当时钟看时不用手动点
 *   · 「更新于 HH:MM:SS」—— 判断屏幕上的数据是不是陈的
 * 旧地址 /screen 走重定向（见 router.ts），书签不会失效。
 */
const REFRESH_MS = 60_000

/**
 * min-score 阈值（app.kb.min-score 的默认值）。
 * 余弦分布图上那根虚线画在它上面 —— 阈值是这张图唯一的「可操作」信息，
 * 不画出来的话它只是一张好看但没用的柱状图。
 */
const MIN_SCORE = 0.45

/**
 * 统计窗口。默认 **0 = 全部**（不按时间过滤）——
 * 默认要看的是"到现在为止一共多少"，而不是"最近 30 天"。
 * 需要看近期趋势时再从右上角切 7/30/90 天。
 */
const days = ref(0)
const loading = ref(false)
const error = ref('')
const auto = ref(false)
const lastAt = ref<Date | null>(null)

const ov = ref<Overview | null>(null)
const keywords = ref<KeywordStat[]>([])
const misses = ref<MissResponse>({ withKeyword: [], unmatched: [] })
const cosineDist = ref<Bucket[]>([])
const src = ref<SourcesResponse>({ sources: [], verdicts: [] })
const visits = ref<VisitSummary | null>(null)

/**
 * 日期范围选项。Select 组件的 v-model 是 string（原生 select 的值本来也是字符串），
 * 而接口要的是数字，所以这里存字符串、出口处转 number，转换点只有一个。
 */
const DAY_OPTIONS: Array<{ value: string; label: string }> = [
  { value: '7', label: '最近 7 天' },
  { value: '30', label: '最近 30 天' },
  { value: '90', label: '最近 90 天' },
  { value: '0', label: '全部' },
]

/** 「更新于」：只保留时分秒 —— 判断数据新不新用不到日期 */
const updatedAt = computed(() => {
  const d = lastAt.value
  if (!d) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
})

const maxKw = computed(() => Math.max(1, ...keywords.value.map(k => k.count)))

/**
 * 最高余弦分布 —— 不是「画 10 根柱子」就完了：这张图的用途是**调阈值**，
 * 所以把每一档按「落在阈值哪一侧」分类，颜色只表达这件事（形状交给柱高）：
 *   blocked（整档都在阈值左边）→ 这一档的检索必然被挡，记 0
 *   kept   （整档都在阈值右边）→ 这一档是真正拿到资料的
 *   edge   （阈值正好落在档内）→ 最该盯的一档：往左一点就被挡
 * 顺带把命中/被挡的量数出来写进图注 —— 光看柱高，读者得自己加总。
 */
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

/**
 * 「每日提问量」折线图的几何（最近 21 天）。
 *
 * 坐标是 0–100 的百分比，SVG 靠 preserveAspectRatio="none" 拉满面板宽度 ——
 * 但非等比缩放会把 <circle> 压成椭圆，所以**圆点不用 SVG 画**，
 * 改成 HTML 绝对定位叠在图上（见模板 .pt）；线用 vector-effect="non-scaling-stroke"
 * 保住 2px 描边。纵轴压到 8%–92%，顶点不会贴边被裁。
 */
const daily = computed(() => {
  const raw = (ov.value?.daily ?? []).slice(-21)
  const n = raw.length
  const max = Math.max(1, ...raw.map(d => d.count))
  // 日期标签最多约 8 个：等距取样，末尾那天必留；离末尾太近的刻度丢掉，免得两个日期叠在一起
  const step = Math.max(1, Math.ceil(n / 8))
  const labelAt = new Set<number>()
  for (let i = 0; i < n; i += step) labelAt.add(i)
  if (n) {
    labelAt.add(n - 1)
    // 末尾留出整整一个 step 的空档：窄屏（390px）下 10% 的间距只有约 31px，
    // 比「09-16」本身还窄 —— 留半个 step 会导致最后两个日期叠在一起。
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

/** 原来挂在 select 的 @change 上：改范围即重新拉数 */
function onDays(v: string) {
  days.value = Number(v)
  load()
}

let timer: number | undefined

/**
 * 自动刷新开关。打开时每 60 秒重取一次（合并大屏后保留的能力）；
 * 关掉时必须 clearInterval —— 否则开关关了，后台还在打接口。
 */
function toggleAuto(v: boolean) {
  auto.value = v
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
  if (v) timer = window.setInterval(load, REFRESH_MS)
}

onBeforeUnmount(() => {
  if (timer !== undefined) window.clearInterval(timer)
})

async function load() {
  loading.value = true
  error.value = ''
  const q = `?days=${days.value}`
  try {
    const [o, k, m, c, s, v] = await Promise.all([
      adminApi.get<Overview>(`/overview${q}`),
      // 只取前 8 —— 后端按次数倒序，多了没人看，还会把面板拉得很长
      adminApi.get<KeywordStat[]>(`/keywords${q}&limit=8`),
      adminApi.get<MissResponse>(`/misses${q}&limit=20`),
      adminApi.get<Bucket[]>(`/cosine${q}`),
      adminApi.get<SourcesResponse>(`/sources${q}`),
      adminApi.get<VisitSummary>(`/visits${q}`),
    ])
    ov.value = o
    keywords.value = k
    misses.value = m
    cosineDist.value = c
    src.value = s
    visits.value = v
    lastAt.value = new Date()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <AdminPage width="wide">
    <template #actions>
      <div class="days">
        <Select :model-value="String(days)" :options="DAY_OPTIONS" @update:model-value="onDays" />
      </div>
      <label class="auto">
        <Switch :model-value="auto" aria-label="自动刷新" @update:model-value="toggleAuto" />
        <span>自动刷新 60s</span>
      </label>
      <span v-if="updatedAt" class="stamp faint num">更新于 {{ updatedAt }}</span>
      <Button size="sm" :disabled="loading" @click="load">{{ loading ? '读取中…' : '刷新' }}</Button>
    </template>

    <template #notice>
      <Notice v-if="error" tone="error">{{ error }}</Notice>
    </template>

    <template v-if="ov">
      <div class="kpis">
        <!-- ⚠️ 口径：提问 = @ 了机器人 + 有效回应且未被拦截（后端 guard_action='pass'）。
             原来这里叫「实际作答」，而 total 叫「消息总数」放在旁边，容易被读成
             "1,163 条消息里有 72 条是问答" —— 其实 1,163 里绝大多数是群里没 @ 的闲聊。 -->
        <Stat label="提问量" :value="num(ov.questions)" hint="@ 了机器人且被有效回应" tone="flame" />
        <Stat label="群消息总数" :value="num(ov.total)" hint="含未 @ 与被拦" tone="mist" />
        <Stat label="提问命中率" :value="pct(ov.hitRate)" hint="提问中检索到资料的比例" tone="moss" />
        <Stat label="提问用户 / 群" :value="`${ov.users} / ${ov.groups}`" tone="mist" />
        <Stat label="被拦 / 指令" :value="`${num(ov.dropped + ov.fixedReplies)} / ${num(ov.commands)}`" hint="被拦不含指令" tone="mist" />
        <!-- 两个数字 = 中位数 / 最慢的 5%（也就是 P50 / P95），hint 就是这两个数的图例。
             ⚠️ 第二个数**不是**「最长耗时」：实测全部数据里最长的一次是 63.9 s（一个离群点），
             写成「最长」就会常年挂着那个 64 s —— 刺眼，而且对"平时多久"没有任何参考价值。
             P95 回答的是「差的时候大概多差」，这才是能拿来做判断的数。
             真要显示真正的最长，得让后端多回一个 MAX 字段（现在只算了 P50/P95，见 QaAnalytics）。
             两个数字在这个卡片宽度下会折成两行（斜杠留在第一行末尾）—— 这是接受的代价：
             只放一个数就看不出"差的时候多差"，那正是这张卡要回答的问题。 -->
        <Stat label="资料搜索耗时" :value="`${ms(ov.p50RetrieveMs)} / ${ms(ov.p95RetrieveMs)}`"
              hint="中位数 / 最慢的 5%" tone="mist" />
        <Stat label="回答耗时" :value="`${ms(ov.p50TotalMs)} / ${ms(ov.p95TotalMs)}`"
              hint="中位数 / 最慢的 5%" tone="mist" />
        <Stat label="后台访问" :value="num(visits?.total ?? 0)" tone="mist" />
      </div>

      <Panel title="未命中 · 该补什么" corners>
        <p class="note faint">
          「A 路余弦」= 向量检索在应用 min-score 阈值<b>之前</b>的最高相似度（当前阈值 0.45）。
          标红表示 ≥0.35，即"库里其实有、差一点被挡" → 该考虑调低阈值；数值很低说明库里确实没这份资料 →
          该补资料，调阈值没用。0 表示这次没走向量路（未启用 / 老记录没有这一列）。
        </p>
        <div class="miss">
          <div>
            <div class="sub faint">认出了词，但知识库没有资料</div>
            <DataTable :rows="misses.withKeyword.length" empty="很好，没有这类问题">
              <thead>
                <tr><th>关键词</th><th>英文名</th><th class="num">次数</th><th class="num">A 路余弦</th></tr>
              </thead>
              <tbody>
                <tr v-for="m in misses.withKeyword" :key="m.zh">
                  <td>{{ m.zh }}</td><td class="muted">{{ m.en }}</td><td class="num">{{ m.count }}</td>
                  <td class="num" :class="{ near: m.bestCosineRaw >= 0.35 }">{{ fmtCosine(m.bestCosineRaw) }}</td>
                </tr>
              </tbody>
            </DataTable>
          </div>
          <div>
            <div class="sub faint">连词都没认出来（术语表里没有）</div>
            <DataTable :rows="misses.unmatched.length" empty="很好，没有这类问题">
              <thead><tr><th>时间</th><th>问题</th><th class="num">A 路余弦</th></tr></thead>
              <tbody>
                <tr v-for="(m, i) in misses.unmatched" :key="i">
                  <td class="muted nowrap">{{ shortTime(m.ts) }}</td>
                  <td class="q">{{ m.question }}</td>
                  <td class="num" :class="{ near: m.bestCosineRaw >= 0.35 }">{{ fmtCosine(m.bestCosineRaw) }}</td>
                </tr>
              </tbody>
            </DataTable>
          </div>
        </div>
      </Panel>

      <div class="two">
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

        <Panel title="检索来源 / 标注">
          <div class="sub faint">来源</div>
          <DataTable :rows="src.sources.length" empty="这段时间还没有检索记录">
            <thead><tr><th>来源</th><th class="num">次数</th><th class="num">命中率</th></tr></thead>
            <tbody>
              <tr v-for="s in src.sources" :key="s.source">
                <td>{{ sourceLabel(s.source) }}</td><td class="num">{{ s.count }}</td><td class="num">{{ pct(s.hitRate) }}</td>
              </tr>
            </tbody>
          </DataTable>
          <div class="sub sub--gap faint">标注结论</div>
          <div class="verdicts">
            <Tag v-for="v in src.verdicts" :key="v.verdict"
                 :tone="v.verdict === 'good' ? 'good' : v.verdict === 'bad' ? 'bad' : v.verdict === 'unknown' ? 'neutral' : 'warn'">
              {{ v.verdict }} · {{ v.count }}
            </Tag>
          </div>
        </Panel>
      </div>

      <Panel title="最高余弦分布" :hint="`阈值参考 · ${cosineHint}`">
        <div class="cosine">
          <figure class="hist-fig">
            <!-- 计数单独占一行：不再让数字骑在各自柱头上 —— 那样顶边参差，最矮的几根根本读不出来 -->
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
              <!-- 阈值线：这张图存在的意义。0.45 落在横轴 45% 处 -->
              <span class="hist-thr" :style="{ left: MIN_SCORE * 100 + '%' }">
                <i class="thr-tag">阈值 {{ MIN_SCORE.toFixed(2) }}</i>
              </span>
            </div>
            <!-- 刻度只标「档的下界」：10 个「0.0~0.1」在窄屏挤不下，
                 而直方图的惯例本来就是刻度落在档的起点上。全称走 title。 -->
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
            <p class="note faint">
              纵轴是<b>提问条数</b>，横轴是<b>过阈值之后</b>的最高余弦：被挡下的一律记 0，
              所以最左那一根就是「压根没检索到」的量。0.1~0.4 空着，说明阈值正卡在缺口上。
              哪天有量落到虚线<b>左边那一档</b>，就是"库里其实有、只差一点被挡" —— 那时再动
              <span class="key">app.kb.min-score</span>。
            </p>
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
  </AdminPage>
</template>

<style scoped>
/* 这里只剩「本页特有」的东西：网格布局、图表、少量排版修饰。
   .page / .toolbar / .sel / .err / .hint / .tbl / .num 已由共享组件层接管，
   页面不再各写一份 —— 否则又是 12 份互相漂移的副本。 */

/* Select 默认 100% 宽，放进 AdminPage 的 actions 槽会被拉满整行 */
.days { width: 132px; flex: none; }
.auto {
  display: inline-flex; align-items: center; gap: var(--sp-2);
  font-size: var(--fs-xs); color: var(--ink-dim); white-space: nowrap;
}
.stamp { font-size: var(--fs-xs); white-space: nowrap; }

/* 8 个指标：150px 下限刚好让宽屏一行铺满 8 张。
   原先 160px → 实际只排得下 7 张，第 8 张「后台访问」孤零零换行、左边空一大块。
   不写死列数：窄屏自然退成 4 / 2 列，不需要另写断点。 */
.kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: var(--sp-3); }
/* 主次：提问量是这一屏最该先看到的数字，给它更大的字号；其余指标同尺寸退后 */
.kpis > :first-child :deep(.v) { font-size: var(--fs-3xl); }

.two { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-4); }
.miss { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-5); }

/* 面板内的小节标签：不是标题（区块标题归 Panel），所以压到元信息字号，
   并靠字距区分层级，而不是靠加粗 */
.sub { font-size: var(--fs-meta); letter-spacing: var(--tracking-label); margin-bottom: var(--sp-2); }
.sub--gap { margin-top: var(--sp-4); }

/* 说明文字：元信息字号 + 放宽行距，长句才读得下去 */
.note { font-size: var(--fs-meta); line-height: 1.8; margin: 0 0 var(--sp-4); }
.note:last-child { margin-bottom: 0; }
.key { font-family: var(--font-mono); color: var(--ink-dim); }

/* 「差一点被 min-score 挡住」—— 明显到值得你动手调阈值的那种 */
.near { color: var(--rust); }
.nowrap { white-space: nowrap; }
.q { max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.bars { display: flex; flex-direction: column; gap: var(--sp-2); }
.verdicts { display: flex; flex-wrap: wrap; gap: var(--sp-2); }

/* ==================== 最高余弦分布 ====================
   不再摊满整屏：条形限宽在 620px 以内，右侧那列放「怎么读」——
   既压住了「几根柱子拉一屏」的观感，也不至于在右边空出一大块。 */
.cosine {
  display: grid;
  grid-template-columns: minmax(0, 620px) minmax(220px, 1fr);
  gap: var(--sp-6);
  align-items: start;
}
.cosine .note { margin: 0; }

.hist-fig { margin: 0; }
/* 计数 / 柱 / 刻度三行用同一套「等宽列」网格 —— 列宽一致，三者天然对齐；
   grid-auto-flow 让它不依赖「挡数写死是 10」。
   ⚠️ 列宽必须写 minmax(0, 1fr)：光写 1fr 等于 minmax(auto, 1fr)，
   轨道的下限是内容的 min-content —— 10 个「0.0~0.1」撑到 470px，
   窄屏（390px）直接顶破整页，出现横向滚动条。 */
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
  /* 基线用 strong：--line 太沉，柱子落不住，整张图像是「飘着」的 */
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
/* 颜色只表达「这一档在阈值哪一侧」，不做装饰 —— 沿用全站语义色：
   moss = 拿到资料，rust = 被挡，flame = 需要你判断的那一档 */
.side-kept { background: linear-gradient(180deg, var(--moss), var(--moss-deep)); }
.side-edge {
  background: linear-gradient(180deg, var(--flame-bright), var(--flame-deep));
  box-shadow: 0 0 10px var(--flame-glow);
}
.side-blocked { background: linear-gradient(180deg, var(--rust), var(--rust-deep)); }

/* 阈值线：中性色。它是一条「参考刻度」，不是状态，别用强调色抢柱子的戏 */
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

.cosine-side { display: flex; flex-direction: column; gap: var(--sp-3); }
.legend { display: flex; flex-wrap: wrap; gap: var(--sp-2) var(--sp-4); font-size: var(--fs-meta); color: var(--ink-dim); }
.lg { display: inline-flex; align-items: center; gap: 6px; }
.sw { width: 9px; height: 9px; border-radius: 2px; flex: none; }

/* ==================== 每日提问量折线 ====================
   线交给 SVG（preserveAspectRatio=none 拉满宽度，描边靠 non-scaling-stroke
   保持 2px），圆点用 HTML 绝对定位叠上去 —— 这样圆不会被非等比缩放压成椭圆。 */
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
  .two, .miss, .cosine { grid-template-columns: 1fr; }
}
/* 窄屏：10 个「0.0~0.1」挤不下，字号再收一档 */
@media (max-width: 560px) {
  .hist-val, .h-lbl { font-size: 10px; }
}
</style>
