<script setup lang="ts">
import { onActivated, onDeactivated, onMounted, ref, watch } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { MissResponse, SourcesResponse } from '@shared/api/types'
import { cosine as fmtCosine, pct, shortTime } from '@shared/utils/format'
import { sourceLabel } from '@shared/utils/source'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Notice from '@shared/ui/Notice.vue'
import Tag from '@shared/ui/Tag.vue'

/**
 * 「统计 → 检索」面板。
 *
 * <p>从总览里搬出来的两块：未命中告诉你"该补什么"，
 * 来源告诉你"答案是从哪条路找出来的"。它们回答的是**检索质量**，
 * 而总览回答的是"用得怎么样" —— 混在一屏里，前者永远被后者的数字压住。
 *
 * <p>没有自动刷新：这两个表看的是补数据的线索，不是时间敏感的读数。
 * 切走时记一笔脏标记，切回来补拉一次，和总览的做法一致。
 */
const props = defineProps<{ days: number }>()

const loading = ref(false)
const error = ref('')
const misses = ref<MissResponse>({ withKeyword: [], unmatched: [] })
const src = ref<SourcesResponse>({ sources: [], verdicts: [] })

let active = true
let dirty = false

watch(() => props.days, () => {
  if (active) load()
  else dirty = true
})

onDeactivated(() => { active = false })

onActivated(() => {
  active = true
  if (dirty) {
    dirty = false
    load()
  }
})

async function load() {
  loading.value = true
  error.value = ''
  const q = '?days=' + props.days
  try {
    const [m, s] = await Promise.all([
      // 各取前 10 条：这两张表是「该补什么」的线索，不是完整清单。
      // 一次拉 20+20 条会让面板变长，也对排查没有额外帮助。
      adminApi.get<MissResponse>('/misses' + q + '&limit=10'),
      adminApi.get<SourcesResponse>('/sources' + q),
    ])
    misses.value = m
    src.value = s
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
})
</script>

<template>
  <div class="wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <Panel title="未命中 · 该补什么" hint="各取前 10 条">
      <div class="miss">
        <div>
          <div class="sub faint">认出了词，但知识库没有资料</div>
          <DataTable cards :rows="misses.withKeyword.length" empty="很好，没有这类问题">
            <thead>
              <tr><th>关键词</th><th>英文名</th><th class="num">次数</th><th class="num">A 路余弦</th></tr>
            </thead>
            <tbody>
              <tr v-for="m in misses.withKeyword" :key="m.zh">
                <td data-label="关键词">{{ m.zh }}</td><td class="muted" data-label="英文名">{{ m.en }}</td>
                <td class="num" data-label="次数">{{ m.count }}</td>
                <td class="num" :class="{ near: m.bestCosineRaw >= 0.35 }" data-label="A 路余弦">{{ fmtCosine(m.bestCosineRaw) }}</td>
              </tr>
            </tbody>
          </DataTable>
        </div>
        <div>
          <div class="sub faint">连词都没认出来（术语表里没有）</div>
          <DataTable cards :rows="misses.unmatched.length" empty="很好，没有这类问题">
            <thead><tr><th>时间</th><th>问题</th><th class="num">A 路余弦</th></tr></thead>
            <tbody>
              <tr v-for="(m, i) in misses.unmatched" :key="i">
                <td class="muted nowrap" data-label="时间">{{ shortTime(m.ts) }}</td>
                <td class="q" data-label="问题">{{ m.question }}</td>
                <td class="num" :class="{ near: m.bestCosineRaw >= 0.35 }" data-label="A 路余弦">{{ fmtCosine(m.bestCosineRaw) }}</td>
              </tr>
            </tbody>
          </DataTable>
        </div>
      </div>
    </Panel>

    <Panel title="检索来源 / 标注">
      <div class="sub faint">来源</div>
      <DataTable cards :rows="src.sources.length" empty="这段时间还没有检索记录">
        <thead><tr><th>来源</th><th class="num">次数</th><th class="num">命中率</th></tr></thead>
        <tbody>
          <tr v-for="s in src.sources" :key="s.source">
            <td data-label="来源">{{ sourceLabel(s.source) }}</td><td class="num" data-label="次数">{{ s.count }}</td>
            <td class="num" data-label="命中率">{{ pct(s.hitRate) }}</td>
          </tr>
        </tbody>
      </DataTable>
      <template v-if="src.verdicts.length">
      <div class="sub sub--gap faint">标注结论</div>
      <div class="verdicts">
        <Tag v-for="v in src.verdicts" :key="v.verdict"
             :tone="v.verdict === 'good' ? 'good' : v.verdict === 'bad' ? 'bad' : v.verdict === 'unknown' ? 'neutral' : 'warn'">
          {{ v.verdict }} · {{ v.count }}
        </Tag>
      </div>
      </template>
    </Panel>

    <p v-if="loading && !misses.withKeyword.length && !src.sources.length" class="muted">读取中…</p>
  </div>
</template>

<style scoped>
.wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

/* 两栏的下限必须写 minmax(0, …)：右边那张表的"问题"单元格
   min-content 能撑到 900px，只写 1fr 会把整页顶出横向滚动条。 */
.miss { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: var(--sp-5); }

.sub { font-size: var(--fs-meta); letter-spacing: var(--tracking-label); margin-bottom: var(--sp-2); }
.sub--gap { margin-top: var(--sp-4); }

/* 「差一点被 min-score 挡住」—— 明显到值得你动手调阈值的那种 */
.near { color: var(--rust); }
.nowrap { white-space: nowrap; }
.q { max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.verdicts { display: flex; flex-wrap: wrap; gap: var(--sp-2); }

@media (max-width: 900px) {
  .miss { grid-template-columns: minmax(0, 1fr); }
}
</style>
