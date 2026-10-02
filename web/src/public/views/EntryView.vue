<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { publicApi, describeError, ApiError } from '@shared/api/client'
import type { KbEntry } from '@shared/api/types'
import Panel from '@shared/ui/Panel.vue'
import Button from '@shared/ui/Button.vue'

const props = defineProps<{ title: string }>()
const route = useRoute()
const router = useRouter()

const entry = ref<KbEntry | null>(null)
const loading = ref(true)
const notFound = ref(false)
const error = ref('')

async function load(title: string) {
  if (!title) return
  loading.value = true
  notFound.value = false
  error.value = ''
  entry.value = null
  try {
    entry.value = await publicApi.get<KbEntry>(`/kb/page?title=${encodeURIComponent(title)}`)
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) notFound.value = true
    else error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => load(props.title))

/**
 * ★ 必须有 watch —— Vue 会复用同一个组件实例，
 * 路由参数变了 onMounted 不会再触发，导致「点第二个词条没反应」。
 */
watch(() => props.title, (t) => {
  load(t)
  window.scrollTo({ top: 0 })
})

/** 后端返回的正文是纯文本，按段落渲染 —— 不做 Markdown 解析（避免 XSS 面） */
function paragraphs(text: string): string[] {
  return text.split(/\n{2,}/).map(s => s.trim()).filter(Boolean)
}
function lines(p: string): string[] {
  return p.split('\n')
}
</script>

<template>
  <div class="page entry">
    <button class="back" type="button" @click="router.back()">← 返回</button>

    <p v-if="loading" class="muted">读取中…</p>

    <div v-else-if="notFound">
      <Panel corners>
        <h1 class="nf">没有这个词条</h1>
        <p class="muted">资料库里找不到「{{ props.title }}」。可能是名称不完全一致。</p>
        <RouterLink to="/library"><Button>去资料库搜索</Button></RouterLink>
      </Panel>
    </div>

    <p v-else-if="error" class="muted">{{ error }}</p>

    <template v-else-if="entry">
      <header class="head">
        <h1 class="title">{{ entry.title }}</h1>
        <div v-if="entry.cats?.length" class="cats">
          <span v-for="c in entry.cats" :key="c" class="cat">{{ c }}</span>
        </div>
      </header>

      <Panel class="body" corners>
        <template v-for="(p, i) in paragraphs(entry.text)" :key="i">
          <p v-if="p.startsWith('## ')" class="h2">{{ p.replace(/^##\s*/, '') }}</p>
          <p v-else class="para">
            <template v-for="(l, j) in lines(p)" :key="j">
              {{ l }}<br v-if="j < lines(p).length - 1" />
            </template>
          </p>
        </template>
      </Panel>

      <p class="src faint">
        来源：
        <a :href="entry.url" target="_blank" rel="noopener noreferrer">{{ entry.url }}</a>
      </p>
    </template>
  </div>
</template>

<style scoped>
.entry { padding-top: var(--sp-5); padding-bottom: var(--sp-7); max-width: 860px; }

.back {
  background: none;
  border: 0;
  color: var(--ink-dim);
  font-size: var(--fs-sm);
  cursor: pointer;
  padding: 0 0 var(--sp-4);
}
.back:hover { color: var(--flame); }

.nf { font-size: var(--fs-xl); margin-bottom: var(--sp-2); }

.head { margin-bottom: var(--sp-4); }
.title { font-size: var(--fs-2xl); letter-spacing: .03em; }
.cats { display: flex; flex-wrap: wrap; gap: 6px; margin-top: var(--sp-2); }
.cat {
  font-size: var(--fs-xs);
  color: var(--mist);
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  padding: 2px 10px;
  background: var(--bg-sunken);
}

.body { line-height: 1.85; }
.para { margin: 0 0 var(--sp-3); font-size: var(--fs-sm); }
.h2 {
  font-family: var(--font-title);
  font-size: var(--fs-lg);
  color: var(--flame);
  margin: var(--sp-4) 0 var(--sp-2);
}
.h2:first-child { margin-top: 0; }

.src { font-size: var(--fs-xs); margin-top: var(--sp-3); word-break: break-all; }
</style>
