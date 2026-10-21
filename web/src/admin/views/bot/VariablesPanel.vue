<script setup lang="ts">
/**
 * 变量页 —— 只读索引。
 *
 * 系统变量随版本增减，不做增删改；页面只回答「能写什么」与「值从哪来」。
 * 列表按来源分组，变量名可点击复制，方便直接粘进回复模板。
 */
import { computed, onMounted, ref } from 'vue'
import { adminApi, describeError } from '@shared/api/client'
import type { CommandVariables, CommandVariable } from '@shared/api/types'
import Input from '@shared/ui/Input.vue'
import Notice from '@shared/ui/Notice.vue'
import Tag from '@shared/ui/Tag.vue'

const loading = ref(false)
const error = ref('')
const data = ref<CommandVariables | null>(null)
const keyword = ref('')
/** 点过「当前值」之后显示真实值，之前显示示例值 */
const showCurrent = ref(false)
const copied = ref('')

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

const GROUP_ORDER = ['会话', '命令', '时刻', '统计', '配置']

function sourceOf(v: CommandVariable) {
  if (v.name === 'arg.名称' || v.name === 'text' || v.name.startsWith('args')) return '命令'
  if (v.name === 'bot') return '配置'
  if (v.name.startsWith('time') || v.name === 'date' || v.name === 'datetime'
      || v.name === 'weekday' || v.name === 'uptime') return '时刻'
  if (v.name.startsWith('kb') || v.name.startsWith('qa') || v.name.startsWith('cmd')) return '统计'
  return '会话'
}

const groups = computed(() => {
  const map = new Map<string, CommandVariable[]>()
  for (const v of rows.value) {
    const key = sourceOf(v)
    map.set(key, [...(map.get(key) ?? []), v])
  }
  return GROUP_ORDER
    .filter((key) => map.has(key))
    .map((label) => ({ label, items: map.get(label) ?? [] }))
})

const SESSION_SCOPED = ['user', 'group', 'user.id', 'group.id']

function valueOf(v: CommandVariable) {
  if (showCurrent.value && SESSION_SCOPED.includes(v.name)) return '群里取值'
  const table = showCurrent.value ? data.value?.current : data.value?.preview
  return table?.[v.name] ?? '—'
}

async function copyVar(name: string) {
  try {
    await navigator.clipboard.writeText('{' + name + '}')
    copied.value = '{' + name + '}'
    window.setTimeout(() => { copied.value = '' }, 1400)
  } catch {
    copied.value = ''
  }
}

defineExpose({
  reload: load,
  isLoading: () => loading.value,
})

onMounted(load)
</script>

<template>
  <div class="var-page">
    <Notice v-if="error" tone="error">{{ error }}</Notice>

    <header class="var-head">
      <div class="var-title">
        <h2>变量</h2>
        <span class="var-count">{{ rows.length }} 个</span>
      </div>
      <div class="var-tools">
        <Input
          :model-value="keyword"
          placeholder="搜索变量"
          aria-label="搜索变量"
          @update:model-value="keyword = $event"
        />
        <button type="button" class="mode" @click="showCurrent = !showCurrent">
          {{ showCurrent ? '看示例值' : '看当前值' }}
        </button>
      </div>
    </header>

    <div class="rule-strip">
      <div><code>#名字#</code><span>解析时展开</span></div>
      <div><code>文本</code><span>发送时展开</span></div>
      <div><code>一次</code><span>结果不再展开</span></div>
    </div>

    <div v-if="data && !data.allowUserIds" class="advanced">
      <Tag tone="warn">高级变量未启用</Tag>
    </div>

    <div v-if="groups.length" class="var-groups">
      <section v-for="group in groups" :key="group.label" class="var-group">
        <header class="group-head">
          <h3>{{ group.label }}</h3>
          <span>{{ group.items.length }}</span>
        </header>
        <button
          v-for="v in group.items"
          :key="v.name"
          type="button"
          class="var-row"
          :title="'复制 {' + v.name + '}'"
          @click="copyVar(v.name)"
        >
          <code class="name">{{ '{' + v.name + '}' }}</code>
          <span class="meaning">{{ v.label }}</span>
          <span class="use">
            <span v-if="v.inParam" title="可用于参数">参数</span>
            <span v-if="v.inText" title="可用于文本">文本</span>
          </span>
          <span class="value" :title="valueOf(v)">{{ valueOf(v) }}</span>
        </button>
      </section>
    </div>
    <p v-else class="empty">没有匹配的变量</p>

    <p v-if="copied" class="copied" aria-live="polite">已复制 {{ copied }}</p>
  </div>
</template>

<style scoped>
.var-page { display: flex; flex-direction: column; gap: var(--sp-5); }

.var-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-4);
  padding-bottom: var(--sp-4);
  border-bottom: 1px solid var(--hairline-lit);
}
.var-title { display: flex; align-items: baseline; gap: var(--sp-3); }
.var-title h2 {
  margin: 0;
  font-family: var(--font-title);
  font-size: 28px;
  font-weight: var(--fw-semi);
  letter-spacing: .02em;
}
.var-count { color: var(--ink-4); font-family: var(--font-mono); font-size: var(--fs-xs); }
.var-tools { display: flex; align-items: center; gap: var(--sp-2); }
.var-tools :deep(.inp) { width: 220px; }
.mode {
  min-height: 34px;
  padding: 0 12px;
  background: none;
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  color: var(--ink-2);
  font-size: var(--fs-xs);
  cursor: pointer;
}
.mode:hover { border-color: var(--edge-hover); color: var(--ink); }
.mode:focus-visible { outline: 2px solid var(--flame); outline-offset: 2px; }

.rule-strip {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border-top: 1px solid var(--hairline);
  border-bottom: 1px solid var(--hairline);
}
.rule-strip div {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 10px 0;
  color: var(--ink-3);
  font-size: var(--fs-xs);
}
.rule-strip div + div { padding-left: var(--sp-4); border-left: 1px solid var(--hairline); }
.rule-strip code { color: var(--ember); font-family: var(--font-mono); }
.advanced { display: flex; justify-content: flex-end; }

.var-groups { display: flex; flex-direction: column; gap: var(--sp-5); }
.var-group { display: flex; flex-direction: column; }
.group-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--hairline);
}
.group-head h3 {
  margin: 0;
  color: var(--ink-3);
  font-family: var(--font-body);
  font-size: 10.5px;
  font-weight: var(--fw-medium);
  letter-spacing: .16em;
}
.group-head span { color: var(--ink-4); font-family: var(--font-mono); font-size: 10.5px; }

.var-row {
  display: grid;
  grid-template-columns: minmax(130px, 1fr) minmax(120px, 1.4fr) 76px minmax(110px, 1fr);
  gap: var(--sp-4);
  align-items: center;
  width: 100%;
  padding: 9px 6px;
  background: none;
  border: 0;
  border-bottom: 1px solid var(--hairline);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease);
}
.var-row:hover { background: var(--surface-hover); }
.var-row:hover .name { color: var(--ember-hot); }
.var-row:focus-visible { outline: 2px solid var(--flame); outline-offset: -2px; }
.name { color: var(--ember); font-family: var(--font-mono); font-size: var(--fs-sm); }
.meaning { overflow: hidden; color: var(--ink-3); font-size: var(--fs-sm); text-overflow: ellipsis; white-space: nowrap; }
.use { display: flex; gap: 5px; color: var(--ink-4); font-size: 10.5px; }
.use span {
  padding: 1px 4px;
  border: 1px solid var(--edge-soft);
  border-radius: 3px;
  white-space: nowrap;
}
.value {
  overflow: hidden;
  color: var(--mist);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.empty { margin: 0; padding: var(--sp-6) 0; color: var(--ink-3); text-align: center; }
.copied {
  position: sticky;
  bottom: 16px;
  align-self: flex-end;
  margin: 0;
  padding: 6px 10px;
  background: var(--stone-400);
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  color: var(--vital);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}

@media (max-width: 900px) {
  .var-head { flex-direction: column; align-items: stretch; }
  .var-tools { justify-content: space-between; }
  .var-tools :deep(.inp) { width: auto; flex: 1; }
  .rule-strip { grid-template-columns: 1fr; }
  .rule-strip div + div { padding-left: 0; border-left: 0; border-top: 1px solid var(--hairline); }
  .var-row { grid-template-columns: 1fr auto; gap: 4px 12px; }
  .meaning { grid-column: 1 / -1; grid-row: 2; }
  .use { grid-column: 2; grid-row: 1; justify-self: end; }
  .value { display: none; }
}
@media (prefers-reduced-motion: reduce) {
  .var-row { transition: none; }
}
</style>
