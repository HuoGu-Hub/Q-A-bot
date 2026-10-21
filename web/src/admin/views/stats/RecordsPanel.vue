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

/**
 * 「统计 → 记录」面板。
 *
 * <p>原「问答记录」页并入统计页后，时间窗口由外层统一持有；这里只保留
 * 记录自己的范围开关（只看提问 / 全部消息）。
 *
 * ⚠️ 这个页面原来列出的是【全部群消息】，其中 93% 是群里没 @ 机器人的闲聊，
 * 却顶着「去群里 @ 机器人问几个问题」的提示语 —— 自相矛盾。
 * 现在默认只看提问：@ 了机器人 + 机器人有效回应且未被拦截。
 * 想看全部消息（排查「为什么没回我」）可以切到 all。
 */
const props = defineProps<{ days: number }>()
const scope = ref<'questions' | 'all'>('questions')
const rows = ref<RecordRow[]>([])
const total = ref(0)

/** 标记「有帮助」的累计进度 —— 打标记是为了喂给「知识库 → 提案」的 agent 分析 */
const prop = ref<{ threshold: number; pendingGood: number; canAnalyze: boolean } | null>(null)
const loading = ref(false)
const error = ref('')
const busyId = ref<number | null>(null)

/** 展开的详情行 —— 列表太窄，需要看全问题和答案 */
const expanded = ref<number | null>(null)

/**
 * 首次加载还没回来时只画表头，不显示空态：
 * 否则每次进页面都会先闪一句「还没有记录」，像是真的没数据。
 */
const tableRows = computed(() =>
  !rows.value.length && loading.value ? undefined : rows.value.length
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    // 进度失败不该影响主列表
    adminApi.get<{ threshold: number; pendingGood: number; canAnalyze: boolean }>(
      '/kb/proposals/stats').then(r => { prop.value = r }).catch(() => {})
    const r = await adminApi.get<{ rows: RecordRow[]; total: number; scope: string }>(
      `/records?days=${props.days}&limit=100&scope=${scope.value}`)
    rows.value = r.rows
    total.value = r.total
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
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

/**
 * 记录没有自动刷新：切走之后不轮询；范围变更发生在不可见期间时，
 * 先记一笔，切回来再补拉，避免两个 tab 的后台请求叠在一起。
 */
let active = true
let dirty = false

function setScope(v: string) {
  const next = v === 'all' ? 'all' : 'questions'
  if (next === scope.value) return
  scope.value = next
  if (active) load()
  else dirty = true
}

watch(() => props.days, () => {
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

/**
 * 页面栏（StatsView）要用的读数与动作。
 * 以函数形式暴露，视图渲染时调用即可把面板内部状态计入渲染依赖。
 */
defineExpose({
  reload: load,
  isLoading: () => loading.value,
  scopeValue: () => scope.value,
  setScope,
})

onMounted(load)
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>
    <Notice v-else-if="scope === 'questions'" tone="info">
      共 {{ total }} 条提问<template v-if="prop"> · 标记「有帮助」的回答累计
      <strong class="num">{{ prop.pendingGood }}</strong>/<span class="num">{{ prop.threshold }}</span> 条后，
      可在「知识库 → 提案」让 agent 分析并给出改进建议</template>
    </Notice>

    <Panel>
      <DataTable
        class="records"
        cards
        :rows="tableRows"
        :empty="scope === 'questions' ? '还没有提问记录' : '还没有任何消息记录'"
        empty-hint="去群里 @ 机器人问几个问题（@ 了并且被有效回应才算提问）"
      >
        <thead>
          <!--
            列名用普通话，并给每个属性加悬浮解释 —— 这一页原先写的是
            「命中 / 余弦 / 标注 / 打标」这种行话，看不懂的人只能来问。
          -->
          <tr>
            <th>时间</th><th>问题</th>
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
                <!--
                  ⚠️ 原来展开详情挂在 <tr @click> 上：鼠标能用，键盘用户进不去
                  （<tr> 不是可聚焦控件，读屏也不会把整行当按钮念）。
                  改成问题本身是一个真正的 <button>，带 aria-expanded ——
                  键盘 Tab 得到、回车能开、读屏会念"已展开/已折叠"，
                  而且不会再出现"点「对/错」时顺手把详情展开"的误触。
                -->
                <button
                  type="button"
                  class="q-btn"
                  :aria-expanded="expanded === r.id"
                  :title="expanded === r.id ? '收起详情' : '展开详情'"
                  @click="toggle(r.id)"
                >{{ r.question || '(原文已清理)' }}</button>
              </td>
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
              <td colspan="7">
                <div class="dpanel">
                  <div>
                    <div class="dlbl">问题</div>
                    <div class="dtext">{{ r.question || '(原文已清理)' }}</div>
                  </div>
                  <div>
                    <div class="dlbl">回答</div>
                    <div class="dtext">{{ r.answer || '(原文已清理)' }}</div>
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
/* 面板之间留出整页统一的竖向节奏 */
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

/* 展开详情改挂在问题按钮上（见模板注释）。按钮长得像文本 ——
   它是"标题"不是"控件"，画成按钮会在七列里显得很吵。 */
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

.q { max-width: 380px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nowrap { white-space: nowrap; }
.miss { color: var(--rust); }

/* 操作列右对齐。
   DataTable 的表头写了 text-align:left，而 .right 单类压不过它（元素选择器少一个），
   所以从 DataTable 根节点（带本页 scope）往下穿透，多带一个类型选择器。 */
.records :deep(th.right),
.records :deep(td.right) { text-align: right; white-space: nowrap; }
.records :deep(td.right .btn + .btn) { margin-left: var(--sp-1); }

/* 展开区：用"下沉表面"表示它属于另一层，不再额外描一层边框。
   border 只留给可交互元素，层级深浅交给 --surface-* 令牌。 */
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