<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { RecordRow } from '@shared/api/types'
import { cosine as fmtCosine, ms, shortTime, VERDICT_LABEL, verdictTone } from '@shared/utils/format'
import { guardLabel } from '@shared/utils/guard'
import { sourceHint, sourceLabel } from '@shared/utils/source'
import AdminPage from '@shared/ui/AdminPage.vue'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Select from '@shared/ui/Select.vue'
import Notice from '@shared/ui/Notice.vue'
import Tag from '@shared/ui/Tag.vue'
import Button from '@shared/ui/Button.vue'

/**
 * 记录范围。
 *
 * ⚠️ 这个页面原来列出的是【全部群消息】，其中 93% 是群里没 @ 机器人的闲聊，
 * 却顶着「去群里 @ 机器人问几个问题」的提示语 —— 自相矛盾。
 * 现在默认只看提问：@ 了机器人 + 机器人有效回应且未被拦截。
 * 想看全部消息（排查「为什么没回我」）可以切到 all。
 */
const scope = ref<'questions' | 'all'>('questions')
const SCOPE_OPTIONS = [
  { value: 'questions', label: '只看提问' },
  { value: 'all', label: '全部消息' },
]
const days = ref(30)
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
 * 天数下拉。Select 的值来自原生 select，本来就是字符串；
 * 但 `days` 要原样拼进 `?days=`，所以在这里换算。
 * 先落值、再请求 —— 保持原来 `v-model` + `@change` 的时序。
 */
const DAY_OPTIONS = [
  { value: '7', label: '最近 7 天' },
  { value: '30', label: '最近 30 天' },
  { value: '90', label: '最近 90 天' },
  { value: '0', label: '全部' },
]
function onDays(v: string) {
  days.value = Number(v)
  load()
}

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
      `/records?days=${days.value}&limit=100&scope=${scope.value}`)
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

onMounted(load)
</script>

<template>
  <AdminPage width="wide">
    <template #actions>
      <Select
        class="scope"
        :model-value="scope"
        :options="SCOPE_OPTIONS"
        @update:model-value="(v: string) => { scope = v as 'questions' | 'all'; load() }"
      />
      <Select
        class="days"
        :model-value="String(days)"
        :options="DAY_OPTIONS"
        @update:model-value="onDays"
      />
      <Button size="sm" :disabled="loading" @click="load">{{ loading ? '读取中…' : '刷新' }}</Button>
    </template>

    <template #notice>
      <Notice v-if="error" tone="error">{{ error }}</Notice>
      <Notice v-else-if="scope === 'questions'" tone="info">
        共 {{ total }} 条提问<template v-if="prop"> · 标记「有帮助」的回答累计
        <strong class="num">{{ prop.pendingGood }}</strong>/<span class="num">{{ prop.threshold }}</span> 条后，
        可在「知识库 → 提案」让 agent 分析并给出改进建议</template>
      </Notice>
    </template>

    <Panel>
      <DataTable
        class="records"
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
            <tr class="row" @click="toggle(r.id)">
              <td class="muted nowrap">{{ shortTime(r.ts) }}</td>
              <td class="q">{{ r.question || '(原文已清理)' }}</td>
              <td class="num" :class="{ miss: r.hitCount === 0 }">{{ r.hitCount }}</td>
              <td class="num">{{ fmtCosine(r.bestCosine) }}</td>
              <td class="muted" :title="sourceHint(r.sources)">{{ sourceLabel(r.sources) }}</td>
              <td>
                <Tag :tone="verdictTone(r.verdict)">{{ VERDICT_LABEL[r.verdict ?? 'unknown'] ?? r.verdict }}</Tag>
              </td>
              <td class="right" @click.stop>
                <Button size="sm" :disabled="busyId === r.id" @click="annotate(r.id, 'good')">对</Button>
                <Button size="sm" variant="danger" :disabled="busyId === r.id" @click="annotate(r.id, 'bad')">错</Button>
              </td>
            </tr>
            <tr v-if="expanded === r.id">
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
  </AdminPage>
</template>

<style scoped>
/* 头部下拉定宽。共享控件自带 width:100%，用 flex-basis 才压得住，
   也不受两个单文件样式谁先注入的影响（width 会打成平手）。 */
.days { flex: 0 0 128px; }
.scope { flex: 0 0 128px; }

/* 行可点展开：DataTable 已经给了悬浮底色，这里只补"可点"的光标 */
.row { cursor: pointer; }

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
