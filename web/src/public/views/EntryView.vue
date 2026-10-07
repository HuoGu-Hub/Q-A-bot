<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { publicApi, describeError, ApiError } from '@shared/api/client'
import type { KbEntry } from '@shared/api/types'
import Icon from '@shared/ui/Icon.vue'

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
  <article class="page entry read">
    <button class="back" type="button" @click="router.back()">
      <Icon name="arrow-left" :size="16" /> 返回
    </button>

    <p v-if="loading" class="muted">读取中…</p>

    <div v-else-if="notFound" class="missing">
      <span class="missing-ico" aria-hidden="true"><Icon name="search" :size="22" /></span>
      <h1 class="nf">没有这个词条</h1>
      <p class="muted">资料库里找不到「{{ props.title }}」。可能是名称不完全一致。</p>
      <RouterLink class="nf-go" to="/library">
        <Icon name="book" :size="16" /> 去资料库搜索
      </RouterLink>
    </div>

    <p v-else-if="error" class="muted">{{ error }}</p>

    <template v-else-if="entry">
      <header class="head">
        <p class="eyebrow">Wiki Entry</p>
        <h1 class="title">{{ entry.title }}</h1>
        <div v-if="entry.cats?.length" class="cats">
          <span v-for="c in entry.cats" :key="c" class="cat">{{ c }}</span>
        </div>
      </header>

      <!--
        正文不再套面板：这是一篇**要读的**东西，不是一块仪表盘。
        面板的圆角与内边距只会把行宽压窄、把段落挤在一起；
        阅读版心（.read = 760px）本身才是可读性的关键。
      -->
      <div class="body">
        <template v-for="(p, i) in paragraphs(entry.text)" :key="i">
          <h2 v-if="p.startsWith('## ')" class="h2">{{ p.replace(/^##\s*/, '') }}</h2>
          <p v-else class="para">
            <template v-for="(l, j) in lines(p)" :key="j">
              {{ l }}<br v-if="j < lines(p).length - 1">
            </template>
          </p>
        </template>
      </div>

      <p class="src">
        <span class="faint">来源</span>
        <a :href="entry.url" target="_blank" rel="noopener noreferrer">
          {{ entry.url }}
          <Icon name="external-link" :size="13" />
        </a>
      </p>
    </template>
  </article>
</template>

<style scoped>
.entry { padding-top: var(--sp-5); padding-bottom: var(--sp-8); }

.back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  background: none;
  border: 0;
  color: var(--ink-3);
  font-size: var(--fs-sm);
  cursor: pointer;
  padding: 0 0 var(--sp-4);
  text-decoration: none;
}
.back:hover { color: var(--ember); }

.missing {
  text-align: center;
  padding: var(--sp-8) var(--sp-4);
  background: var(--stone-300);
  border-radius: var(--r-md);
  box-shadow: var(--bevel-raised);
}
.missing-ico {
  display: grid;
  place-items: center;
  width: 48px; height: 48px;
  margin: 0 auto var(--sp-4);
  border-radius: 50%;
  background: var(--stone-void);
  box-shadow: var(--bevel-inset);
  color: var(--ink-4);
}
.nf { font-size: var(--fs-xl); margin-bottom: var(--sp-2); }
.nf-go {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-4);
  padding: 9px 18px;
  border-radius: var(--r-sm);
  text-decoration: none;
  background: var(--stone-400);
  border: 1px solid var(--edge);
  color: var(--ink);
  font-size: var(--fs-base);
}
.nf-go:hover { background: var(--stone-500); border-color: var(--edge-hover); text-decoration: none; }

.head { margin-bottom: var(--sp-5); padding-bottom: var(--sp-4); border-bottom: 1px solid var(--hairline); }
.title { font-size: var(--fs-2xl); letter-spacing: .04em; margin-top: var(--sp-2); }
.cats { display: flex; flex-wrap: wrap; gap: 6px; margin-top: var(--sp-3); }
.cat {
  font-size: var(--fs-xs);
  color: var(--ink-3);
  border-radius: var(--r-pill);
  padding: 2px 10px;
  background: var(--stone-400);
}

.body { line-height: 1.9; }
/* 行宽控制在 38 个汉字左右 —— 中文长行读到行尾会丢行 */
.para { margin: 0 0 var(--sp-4); font-size: var(--fs-lg); color: var(--ink); }
.h2 {
  font-family: var(--font-title);
  font-size: var(--fs-xl);
  color: var(--ember);
  letter-spacing: .03em;
  margin: var(--sp-6) 0 var(--sp-3);
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}
.h2::after {
  content: '';
  flex: 1;
  height: 1px;
  background: linear-gradient(90deg, var(--hairline), transparent);
}

.src {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
  margin-top: var(--sp-7);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--hairline);
  font-size: var(--fs-xs);
  word-break: break-all;
}
.src a { display: inline-flex; align-items: center; gap: 4px; }

@media (max-width: 760px) {
  .para { font-size: var(--fs-base); }
}
</style>
