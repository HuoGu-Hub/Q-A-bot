<script setup lang="ts">
/**
 * 「变量」页 —— 只读。
 *
 * 系统变量由代码决定，随版本增减，所以这里不做增删改：做成可编辑的配置
 * 只会多出「配了但代码里没有」这种状态。管理员真正要回答的是两个问题：
 * **现在能写什么**、**什么时候取值**。
 *
 * 同一份清单也喂给指令编辑器里的「点击插入」按钮，不另存第二份。
 */
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { CommandVariables, CommandVariable } from '@shared/api/types'
import Panel from '@shared/ui/Panel.vue'
import DataTable from '@shared/ui/DataTable.vue'
import Input from '@shared/ui/Input.vue'
import Notice from '@shared/ui/Notice.vue'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'

const loading = ref(false)
const error = ref('')
const data = ref<CommandVariables | null>(null)
const keyword = ref('')
/** 点过「取当前值」之后显示真实值，之前显示示例值 */
const showCurrent = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await adminApi.get<CommandVariables>('/commands/variables')
  } catch (e) { error.value = describeError(e) } finally {
    loading.value = false
  }
}

const rows = computed<CommandVariable[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  const all = data.value?.variables ?? []
  if (!kw) return all
  return all.filter((v) => v.name.toLowerCase().includes(kw) || v.label.toLowerCase().includes(kw))
})

/** 会话相关的变量在管理端没有会话可取，取值是占位 —— 用来源标出来 */
function sourceOf(v: CommandVariable) {
  if (v.name === 'arg.名称' || v.name === 'text' || v.name.startsWith('args')) return '命令'
  if (v.name === 'bot') return '配置'
  if (v.name.startsWith('time') || v.name === 'date' || v.name === 'datetime'
      || v.name === 'weekday' || v.name === 'uptime') return '时刻'
  if (v.name.startsWith('kb') || v.name.startsWith('qa')) return '统计'
  if (v.name.startsWith('cmd')) return '指令'
  return '会话'
}

/** 这几个变量的含义是"谁发的、哪个群"，管理端没有会话，取不到真值 */
const SESSION_SCOPED = ['user', 'group', 'user.id', 'group.id']

function valueOf(v: CommandVariable) {
  // 取当前值时，会话相关的直接说"去群里看"，不给"朋友""私聊""0"这种看着像真值的占位
  if (showCurrent.value && SESSION_SCOPED.includes(v.name)) {
    return '需在群里看'
  }
  const table = showCurrent.value ? data.value?.current : data.value?.preview
  return table?.[v.name] ?? '—'
}

defineExpose({
  reload: load,
  isLoading: () => loading.value,
})

onMounted(load)
</script>

<template>
  <div class="panel-wrap">
    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <Panel title="取值时刻">
      <div class="rules">
        <div class="rule">
          <div class="rule-k">写在 #名字-值# 里</div>
          <div class="rule-v">解析那一刻展开。顺序在类型校验之前 ——
            所以 #time-{time}# 先变成 21:30 才可能通过时间校验。</div>
        </div>
        <div class="rule">
          <div class="rule-k">写在文本里</div>
          <div class="rule-v">用时展开。定时任务到点才展开，
            所以 {time} 是真正发出去那一刻，不是设置它的那一刻。</div>
        </div>
        <div class="rule">
          <div class="rule-k">只展开一次</div>
          <div class="rule-v">展开出来的文字不会再被扫一遍。群昵称里写了 {cmd.list}，
            发出去的就是那几个字。</div>
        </div>
      </div>
    </Panel>

    <Panel title="可用变量" :count="rows.length">
      <template #actions>
        <Button size="sm" :disabled="loading" @click="load">刷新</Button>
        <Button size="sm" @click="showCurrent = !showCurrent">
          {{ showCurrent ? '看示例值' : '取当前值' }}
        </Button>
      </template>

      <Input
        :model-value="keyword"
        placeholder="搜变量名或含义"
        aria-label="搜索变量"
        @update:model-value="keyword = $event"
      />

      <DataTable :rows="rows.length" empty="没有匹配的变量" empty-hint="换个关键词试试">
        <thead>
          <tr>
            <th>变量</th>
            <th>来源</th>
            <th>含义</th>
            <th>写在 #参数值# 里</th>
            <th>写在文本里</th>
            <th>示例 / 当前值</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="v in rows" :key="v.name">
            <td class="var">{{ '{' + v.name + '}' }}</td>
            <td class="muted">{{ sourceOf(v) }}</td>
            <td class="muted">{{ v.label }}</td>
            <td>
              <Tag v-if="v.inParam" tone="good">能</Tag>
              <span v-else class="muted">不能</span>
            </td>
            <td>
              <Tag v-if="v.inText" tone="good">能</Tag>
              <span v-else class="muted">不能</span>
            </td>
            <td class="val">{{ valueOf(v) }}</td>
          </tr>
        </tbody>
      </DataTable>

      <p class="faint note">
        没有会话的变量（{user} {group} {user.id} {group.id}）在管理端取不到真实值，
        点「取当前值」时它们显示「需在群里看」—— 它们在群里才有意义。
        {{ data?.allowUserIds ? '' : '高级变量当前未启用，在「Bot 设置」里打开。' }}
      </p>
    </Panel>
  </div>
</template>

<style scoped>
.panel-wrap { display: flex; flex-direction: column; gap: var(--sp-4); }

.rules { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: var(--sp-4); }
.rule-k { color: var(--flame); font-size: var(--fs-meta); margin-bottom: var(--sp-1); }
.rule-v { color: var(--mist); font-size: var(--fs-meta); line-height: 1.75; }

/* 变量名等宽：一眼能和说明文字分开 */
.var { font-family: var(--font-mono); color: var(--flame); white-space: nowrap; }
.val { font-family: var(--font-mono); font-size: var(--fs-meta); color: var(--moss); overflow-wrap: anywhere; }
.note { font-size: var(--fs-meta); margin: var(--sp-3) 0 0; }
</style>
