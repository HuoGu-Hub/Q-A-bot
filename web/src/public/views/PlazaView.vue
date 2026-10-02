<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { renderInline } from '../renderInline'
import { useRoute, useRouter } from 'vue-router'
import { publicApi, describeError } from '@shared/api/client'
import type { PlazaKeywordPage, PlazaAnswer, PlazaKeywords } from '@shared/api/types'
import { num } from '@shared/utils/format'
import { t } from '../useSiteText'
import Panel from '@shared/ui/Panel.vue'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'
import Empty from '@shared/ui/Empty.vue'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const error = ref('')
const page = ref<PlazaKeywordPage | null>(null)
const hot = ref<PlazaKeywords | null>(null)

/** 投票进行中（防止连点） */
const voting = ref<number | null>(null)
/** 我投过的票：statId → vote */
const myVotes = ref<Record<number, string>>({})

/**
 * 匿名身份：localStorage 里存一个随机串。
 * 不是登录 —— 只是让服务端能区分「不同的人」，避免一个人反复点。
 */
function clientId(): string {
  const KEY = 'plaza_client_id'
  let id = localStorage.getItem(KEY)
  if (!id) {
    id = Math.random().toString(36).slice(2) + Date.now().toString(36)
    localStorage.setItem(KEY, id)
  }
  return id
}

async function loadHot() {
  try {
    hot.value = await publicApi.get<PlazaKeywords>('/plaza/keywords?limit=24')
  } catch { /* 首页热词失败不影响主体 */ }
}

async function loadPage(kw: string) {
  if (!kw) {
    page.value = null
    return
  }
  loading.value = true
  error.value = ''
  try {
    page.value = await publicApi.get<PlazaKeywordPage>('/plaza/keyword?keyword=' + encodeURIComponent(kw))
    myVotes.value = {}
  } catch (e) {
    error.value = describeError(e)
    page.value = null
  } finally {
    loading.value = false
  }
}

async function vote(a: PlazaAnswer, v: string) {
  if (voting.value !== null) return
  voting.value = a.statId
  try {
    const r = await publicApi.post<{ up: number; down: number; outdated: number }>('/plaza/vote', {
      statId: a.statId, vote: v, clientId: clientId(),
    })
    myVotes.value = { ...myVotes.value, [a.statId]: v }
    // 就地更新数字，不整页刷新（体验更顺）
    if (r) { a.up = r.up; a.down = r.down; a.outdated = r.outdated; }
  } catch (e) {
    error.value = describeError(e)
  } finally {
    voting.value = null
  }
}

/** 新生成的答案（第 2 级降级的结果） */
const newAnswer = ref<string | null>(null)
const asking = ref(false)
const helping = ref(false)

/** 第 2 级：让机器人重新生成一条 */
async function askNew() {
  const kw = keyword.value
  if (!kw) return
  asking.value = true
  error.value = ''
  try {
    const r = await publicApi.post<{ ok: boolean; answer?: string; error?: string }>('/plaza/ask-new', {
      keyword: kw,
      // 用已经出现过的提问当 prompt —— 比只给关键词更准
      question: page.value?.answers?.[0]?.question ?? '',
    })
    if (r.ok && r.answer) {
      newAnswer.value = r.answer
    } else {
      error.value = r.error ?? '生成失败'
    }
  } catch (e) {
    error.value = describeError(e)
  } finally {
    asking.value = false
  }
}

/**
 * 第 3 级：求助大佬。
 *
 * ⚠️ 不再让用户填群号 —— 那样网页就能向任意群发消息（滥用风险）。
 * 改成：生成一句「求助：xxx」，让用户复制到自己群里 @ 机器人。
 * 群号由群聊上下文决定，天然只能发到他所在的群。
 */
const helpText = ref<string | null>(null)
const copied = ref(false)

async function askHelp() {
  const kw = keyword.value
  if (!kw) return
  helping.value = true
  error.value = ''
  try {
    const q = page.value?.answers?.[0]?.question ?? ("关于「" + kw + "」的问题")
    const r = await publicApi.post<{ ok: boolean; code: string; text: string; howto: string; error?: string }>('/plaza/help-request', {
      keyword: kw,
      question: q,
    })
    if (r.ok) {
      helpText.value = r.text
      copied.value = false
    } else {
      error.value = r.error ?? '生成失败'
    }
  } catch (e) {
    error.value = describeError(e)
  } finally {
    helping.value = false
  }
}

async function copyHelp() {
  if (!helpText.value) return
  const full = helpText.value + "\n（把上面这句连同 @机器人 一起发到群里）"
  try {
    await navigator.clipboard.writeText(helpText.value)
    copied.value = true
  } catch {
    // 剪贴板不可用（非 HTTPS）就选中文本让用户手动复制
    copied.value = false
  }
}
const keyword = computed(() => String(route.query.k ?? ''))

onMounted(async () => {
  await loadHot()
  await loadPage(keyword.value)
});

watch(() => route.query.k, (v) => {
  loadPage(String(v ?? ""))
  window.scrollTo({ top: 0 });
});

function openKeyword(kw: string) {
  router.push({ name: 'plaza', query: { k: kw } })
}

/** 是不是所有答案都被踩了（决定是否显示「问问新答案」） */
const allDownvoted = computed(() => {
  const list = page.value?.answers ?? []
  if (!list.length) return false
  return list.every(a => a.down >= 2 && a.down > a.up)
});
</script>

<template>
  <div class="page plaza">
    <section class="section-title">问答广场</section>

    <p v-if="error" class="err">{{ error }}</p>

    <!-- 关键词页 -->
    <template v-if="keyword">
      <div class="head">
        <button class="back" type="button" @click="router.push({ name: 'plaza' })">← 返回</button>
        <h1 class="kw">{{ page?.keyword ?? keyword }}</h1>
        <span v-if="page?.termEn" class="faint en">{{ page.termEn }}</span>
        <span v-if="page?.askedCount" class="faint asked">被问过 {{ page.askedCount }} 次</span>
      </div>

      <p v-if="loading" class="muted">读取中…</p>

      <template v-else-if="page">
        <div v-if="!page.answers.length" class="empty-wrap">
          <Empty
            text="还没有被认可的答案"
            :hint="page.askedCount > 0
              ? '这个问题被问过 ' + page.askedCount + ' 次，但还没有答案被点赞过'
              : '去群里 @ 机器人问问吧'"
          />
        </div>

        <div v-else class="answers">
          <article v-for="a in page.answers" :key="a.statId" class="ans panel texture-noise">
            <div class="ans-head">
              <Tag v-if="a.badge === 'best'" tone="flame">最受认可</Tag>
              <Tag v-if="a.badge === 'outdated'" tone="warn">可能已过时</Tag>
              <span class="question">{{ a.question }}</span>
            </div>
            <div class="ans-body">{{ a.answer }}</div>

            <div class="votes">
              <button
                class="vbtn"
                :class="{ active: myVotes[a.statId] === 'up' }"
                :disabled="voting === a.statId"
                @click="vote(a, 'up')"
              >👍 {{ a.up }}</button>
              <button
                class="vbtn"
                :class="{ active: myVotes[a.statId] === 'down' }"
                :disabled="voting === a.statId"
                @click="vote(a, 'down')"
              >👎 {{ a.down }}</button>
              <button
                class="vbtn"
                :class="{ active: myVotes[a.statId] === 'outdated' }"
                :disabled="voting === a.statId"
                @click="vote(a, 'outdated')"
              >🕐 已过时 {{ a.outdated }}</button>
            </div>
          </article>
        </div>

        <!-- ★ 第 2 级降级的结果：新生成的答案 -->
        <article v-if="newAnswer" class="ans panel new-ans">
          <div class="ans-head">
            <Tag tone="flame">新生成的回答</Tag>
            <span class="question">机器人刚为你重新生成</span>
          </div>
          <div class="ans-body">{{ newAnswer }}</div>
        </article>

        <!-- 降级入口 -->
        <div v-if="allDownvoted || page.needsNewAnswer" class="fallback">
          <p class="faint fb-hint">这些答案好像都不太行？</p>
          <div class="fb-btns">
            <Button variant="primary" :disabled="asking" @click="askNew">
              {{ asking ? "生成中…" : "问问新答案" }}
            </Button>
            <Button :disabled="helping" @click="askHelp">
              {{ helping ? "生成中…" : "求助大佬" }}
            </Button>
          </div>
          <p class="faint fb-note">
            「问问新答案」会让机器人重新生成一条；还是不行就让群友来答。
          </p>

          <!-- 求助文案（复制到群里） -->
          <div v-if="helpText" class="help-box">
            <p class="help-howto">把下面这句连同 <code>@机器人</code> 一起发到群里：</p>
            <div class="help-text">{{ helpText }}</div>
            <div class="help-actions">
              <Button size="sm" @click="copyHelp">{{ copied ? "已复制 ✓" : "复制" }}</Button>
            </div>
            <p class="faint help-note">
              为什么要这样：网页没法确认你在哪个群，所以只能由你从群里发起 ——
              这样就不会有人拿它往别人的群发消息。
            </p>
          </div>
        </div>
      </template>
    </template>

    <!-- 广场首页：热词列表 -->
    <template v-else>
      <p class="lead faint">
        <span v-html="renderInline(t('plaza.intro', '这里是群友们问过、并且被点赞认可的答案。点关键词查看 —— 如果都不满意，可以投票让更好的答案浮现出来。'))" />
      </p>

      <div v-if="hot?.keywords?.length" class="hot">
        <button
          v-for="k in hot.keywords"
          :key="k.keyword"
          class="hot-item"
          type="button"
          @click="openKeyword(k.keyword)"
        >
          <span class="hot-kw">{{ k.keyword }}</span>
          <span v-if="k.termEn" class="hot-en faint">{{ k.termEn }}</span>
          <span class="hot-count num">{{ num(k.votedCount || k.count) }}</span>
        </button>
      </div>
      <Empty
        v-else
        text="还没有人点赞过任何答案"
        hint="等群友们用起来，被认可的答案会出现在这里"
      />
    </template>
  </div>
</template>

<style scoped>
.plaza { padding-top: var(--sp-6); padding-bottom: var(--sp-7); max-width: 900px; }
.lead { font-size: var(--fs-sm); line-height: 1.9; margin-bottom: var(--sp-5); }
.err { color: var(--rust); font-size: var(--fs-sm); }

.head { margin-bottom: var(--sp-5); }
.back {
  background: none; border: 0; color: var(--ink-dim); font-size: var(--fs-sm);
  cursor: pointer; padding: 0 0 var(--sp-3);
}
.back:hover { color: var(--flame); }
.kw { font-size: var(--fs-2xl); display: inline-block; margin-right: var(--sp-3); }
.en { font-family: var(--font-mono); font-size: var(--fs-xs); }
.asked { font-size: var(--fs-xs); margin-left: var(--sp-3); }

.answers { display: flex; flex-direction: column; gap: var(--sp-4); }
.ans { padding: var(--sp-4); }
.ans-head { display: flex; align-items: center; gap: var(--sp-2); margin-bottom: var(--sp-3); flex-wrap: wrap; }
.question { font-size: var(--fs-sm); color: var(--ink-dim); }
.ans-body {
  font-size: var(--fs-sm); line-height: 1.85; white-space: pre-wrap;
  border-left: 2px solid var(--copper-dim); padding-left: var(--sp-3);
}
.votes { display: flex; gap: var(--sp-2); margin-top: var(--sp-4); }
.vbtn {
  background: var(--bg-sunken); border: 1px solid var(--line-strong);
  border-radius: var(--r-pill); padding: 4px 14px; font-size: var(--fs-xs);
  color: var(--ink-dim); cursor: pointer; transition: all var(--dur-fast) var(--ease);
}
.vbtn:hover:not(:disabled) { border-color: var(--flame); color: var(--flame-bright); }
.vbtn.active { border-color: var(--flame); background: var(--flame-veil); color: var(--flame-bright); }
.vbtn:disabled { opacity: .5; cursor: default; }

.fallback {
  margin-top: var(--sp-5); padding: var(--sp-5); text-align: center;
  border: 1px dashed var(--line-strong); border-radius: var(--r-md);
}
.fb-hint { font-size: var(--fs-sm); margin-bottom: var(--sp-3); }
.fb-btns { display: flex; gap: var(--sp-3); justify-content: center; }
.fb-note { font-size: var(--fs-xs); margin-top: var(--sp-3); }

.hot { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: var(--sp-3); }
.hot-item {
  display: flex; align-items: baseline; gap: var(--sp-2);
  background: var(--bg-shroud); border: 1px solid var(--line);
  border-radius: var(--r-md); padding: var(--sp-3) var(--sp-4);
  cursor: pointer; text-align: left; transition: all var(--dur-fast) var(--ease);
}
.hot-item:hover { border-color: var(--copper); }
.hot-item:hover .hot-kw { color: var(--flame-bright); }
.hot-kw { font-size: var(--fs-sm); color: var(--ink); flex: 1; }
.hot-en { font-size: 10px; }
.hot-count { font-size: var(--fs-xs); color: var(--mist); }
.empty-wrap { padding: var(--sp-6) 0; }
.help-box { margin-top: var(--sp-4); padding-top: var(--sp-4); border-top: 1px dashed var(--line-strong); text-align: left; }
.help-howto { font-size: var(--fs-xs); color: var(--ink-dim); margin-bottom: var(--sp-2); }
.help-text { background: var(--bg-abyss); border: 1px solid var(--copper-dim); border-radius: var(--r-sm); padding: var(--sp-3); font-size: var(--fs-sm); user-select: all; }
.help-actions { margin-top: var(--sp-2); }
.help-note { font-size: 10px; margin-top: var(--sp-2); line-height: 1.7; }
.new-ans { margin-top: var(--sp-5); border-color: var(--flame); box-shadow: 0 0 0 3px var(--flame-glow); }
</style>
