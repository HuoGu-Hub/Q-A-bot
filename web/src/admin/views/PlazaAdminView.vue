<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { PlazaOverview, PlazaAdminAnswer, PlazaHelpRequest } from '@shared/api/types'
import AdminPage from '@shared/ui/AdminPage.vue'
import Panel from '@shared/ui/Panel.vue'
import Stat from '@shared/ui/Stat.vue'
import Tabs from '@shared/ui/Tabs.vue'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'
import Notice from '@shared/ui/Notice.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Empty from '@shared/ui/Empty.vue'

const loading = ref(false)
const error = ref('')
const ok = ref('')
const overview = ref<PlazaOverview | null>(null)
const answers = ref<PlazaAdminAnswer[]>([])
const help = ref<PlazaHelpRequest[]>([])
/**
 * 当前二级目录。
 * 原来是字面量联合类型，但共享 Tabs 的 v-model 是 string —— 这里放宽到 string，
 * 判断仍走 `tab === 'answers'`，行为不变。
 */
const tab = ref('answers')

async function load() {
  loading.value = true
  error.value = ''
  try {
    overview.value = await adminApi.get<PlazaOverview>('/plaza/overview')
    const a = await adminApi.get<{ answers: PlazaAdminAnswer[] }>('/plaza/answers?limit=100')
    answers.value = a.answers ?? []
    const h = await adminApi.get<{ requests: PlazaHelpRequest[] }>('/plaza/help?limit=50')
    help.value = h.requests ?? []
  } catch (e) { error.value = describeError(e) } finally {
    loading.value = false
  }
}

async function takedown(a: PlazaAdminAnswer) {
  if (!window.confirm("确定下架这条？\n\n" + (a.question ?? "").slice(0, 60))) return
  try {
    await adminApi.post('/plaza/takedown', { statId: a.statId })
  if (!window.confirm("确定下架这条？" + String.fromCharCode(10, 10) + (a.question ?? "").slice(0, 60))) return
    await load()
  } catch (e) { error.value = describeError(e) }
}

/** 是否达到「上架」门槛（有赞） */
const isPublic = (a: PlazaAdminAnswer) => a.up > 0
/** 是否被踩到该降级 */
const isBadlyDownvoted = (a: PlazaAdminAnswer) =>
  overview.value ? a.down >= overview.value.downvoteThreshold && a.down > a.up : false

const stats = computed(() => {
  const list = answers.value
  return {
    total: list.length,
    publicCount: list.filter(isPublic).length,
    bad: list.filter(isBadlyDownvoted).length,
  }
});

/** 二级目录：计数徽标沿用原来的括号数字 */
const tabs = computed(() => [
  { key: 'answers', label: '投票内容', count: answers.value.length },
  { key: 'help', label: '求助记录', count: help.value.length },
])

onMounted(load)
</script>

<template>
  <!-- 原来局部写死的 max-width:1200px 收归 AdminPage（1200 → wide 档） -->
  <AdminPage width="wide">
    <template #tabs>
      <!-- 二级目录改用共享 Tabs（原先本页自写一套下划线 tab），挪进吸顶页面栏 -->
      <Tabs v-model="tab" :tabs="tabs" />
    </template>

    <template #actions>
      <Button size="sm" :disabled="loading" @click="load">刷新</Button>
    </template>

    <template #notice>
      <Notice v-if="error" tone="error">{{ error }}</Notice>
      <Notice v-if="ok" tone="ok">{{ ok }}</Notice>
    </template>

    <!-- 概览：原来手写了一套 .cards/.card/.card-label/.card-value/.card-sub，与知识库页逐字重复，改用 Stat -->
    <div v-if="overview" class="kpis">
      <Stat label="投票总数" :value="overview.voteCount" tone="flame" />
      <Stat
        label="被投过票的回答"
        :value="stats.total"
        tone="mist"
        :hint="`其中 ${stats.publicCount} 条已上架公开站`"
      />
      <Stat
        label="该降级的内容"
        :value="stats.bad"
        :tone="stats.bad > 0 ? 'rust' : 'mist'"
        :hint="`踩 ≥ ${overview.downvoteThreshold} 且 踩 > 赞`"
      />
      <Stat label="求助次数" :value="overview.helpCount" tone="mist" />
      <Stat
        label="今日问新答案"
        :value="overview.usage?.askTodayTotal ?? 0"
        tone="mist"
        :hint="`上限 ${overview.usage?.askLimitGlobal ?? 0}/天`"
      />
    </div>

    <!-- 开关状态 -->
    <Panel v-if="overview" title="当前策略">
      <div class="policies">
        <div class="policy">
          <Tag :tone="overview.enabled ? 'good' : 'neutral'">
            {{ overview.enabled ? "广场已启用" : "广场已关闭" }}
          </Tag>
        </div>
        <div class="policy">
          <Tag :tone="overview.onlyVoted ? 'good' : 'warn'">
            {{ overview.onlyVoted ? "只展示被点赞过的（隐私保护）" : "⚠️ 所有问答都会公开" }}
          </Tag>
        </div>
      </div>
      <p class="faint note">在「设置」页可以调整这些策略。</p>
    </Panel>

    <!-- 投票内容 -->
    <Panel v-if="tab === 'answers'" title="被投票的内容">
      <p class="faint note">
        源码来自群内问答或广场生成；「已上架」= 有赞，会出现在公开站。
        下架会清空投票（原文保留）。
      </p>
      <Empty v-if="!answers.length" text="还没有人投过票" />
      <div v-else class="list">
        <article v-for="a in answers" :key="a.statId" class="item">
          <div class="item-head">
            <Tag v-if="isPublic(a)" tone="good">已上架</Tag>
            <Tag v-else tone="neutral">未上架</Tag>
            <Tag v-if="isBadlyDownvoted(a)" tone="bad">该降级</Tag>
            <Tag v-if="a.source === 'plaza'" tone="flame">广场生成</Tag>
            <span class="faint when">{{ (a.ts ?? "").slice(0, 16).replace("T", " ") }}</span>
            <!-- 用 margin-left:auto 顶开，替掉原来手写的 .spacer -->
            <span class="votes-mini">👍{{ a.up }} 👎{{ a.down }} 🕐{{ a.outdated }}</span>
            <Button size="sm" @click="takedown(a)">下架</Button>
          </div>
          <div class="q">{{ a.question }}</div>
          <div class="a">{{ a.answer }}</div>
          <div class="meta faint">
            statId={{ a.statId }} · 群 {{ a.groupId }} · 用户 {{ a.userId }}
          </div>
        </article>
      </div>
    </Panel>

    <!-- 求助记录 -->
    <Panel v-if="tab === 'help'" title="求助记录">
      <p class="faint note">
        「待确认」= 有人在网页上申请了但还没发到群里；「已发送」= 已经在群里问过了。
      </p>
      <DataTable :rows="help.length" empty="还没有求助记录">
        <thead>
          <tr>
            <th>时间</th><th>关键词</th><th>问题</th>
            <th class="num">群</th><th class="num">用户</th><th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(h, i) in help" :key="i">
            <td class="faint mono">{{ (h.ts ?? "").slice(5, 16).replace("T", " ") }}</td>
            <td class="mono">{{ h.keyword || "-" }}</td>
            <td>{{ (h.question ?? "").slice(0, 50) }}</td>
            <td class="num">{{ h.groupId || "-" }}</td>
            <td class="num">{{ h.userId || "-" }}</td>
            <td>
              <Tag :tone="h.status === 'sent' ? 'good' : 'warn'">
                {{ h.status === "sent" ? "已发送" : "待确认" }}
              </Tag>
            </td>
          </tr>
        </tbody>
      </DataTable>
    </Panel>
  </AdminPage>
</template>

<style scoped>
/* 只保留本页特有结构：.page/.toolbar/.spacer/.hint/.err/.okmsg/.cards/.card/.tabs/.tab/.tbl 均改由共享层提供 */
.kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: var(--sp-3); }
.note { font-size: var(--fs-meta); line-height: 1.8; }
.note:last-child { margin-bottom: 0; }

.policies { display: flex; gap: var(--sp-3); flex-wrap: wrap; margin-bottom: var(--sp-2); }

/* 同级条目靠留白分开（sp-4），底色 + 柔和边缘只用来界定"这是一条" */
.list { display: flex; flex-direction: column; gap: var(--sp-4); }
.item {
  background: var(--surface-inset);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-sm);
  padding: var(--sp-3);
}
.item-head { display: flex; align-items: center; gap: var(--sp-2); margin-bottom: var(--sp-2); flex-wrap: wrap; }
.when { font-size: var(--fs-meta); }
/* 票数是数字 → 等宽 + tabular-nums，多行之间上下对得齐 */
.votes-mini {
  margin-left: auto;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: var(--fs-meta);
  color: var(--ink-dim);
}
/* 问题是一行的入口，给正文级字号；回答是长文，降一级并调暗，避免整页字重一样 */
.q { font-size: var(--fs-body); color: var(--ink); margin-bottom: var(--sp-1); }
.a { font-size: var(--fs-sm); line-height: 1.7; color: var(--ink-dim); }
/* id 类元信息：等宽数字，方便纵向比对 */
.meta {
  margin-top: var(--sp-2);
  font-size: var(--fs-meta);
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
}
</style>
