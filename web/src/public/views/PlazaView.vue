<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { renderInline } from '../renderInline'
import { useRoute, useRouter } from 'vue-router'
import { publicApi, describeError } from '@shared/api/client'
import type { PlazaKeywordPage, PlazaAnswer, PlazaKeywords } from '@shared/api/types'
import { num } from '@shared/utils/format'
import { t } from '../useSiteText'
import Button from '@shared/ui/Button.vue'
import Tag from '@shared/ui/Tag.vue'
import Empty from '@shared/ui/Empty.vue'
import Icon from '@shared/ui/Icon.vue'
import ListRow from '@shared/ui/ListRow.vue'
import PageHeader from '@shared/ui/PageHeader.vue'

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
    if (r) { a.up = r.up; a.down = r.down; a.outdated = r.outdated }
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
    const q = page.value?.answers?.[0]?.question ?? ('关于「' + kw + '」的问题')
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
  try {
    await navigator.clipboard.writeText(helpText.value)
    copied.value = true
  } catch {
    // 剪贴板不可用（非 HTTPS）就什么都不做 —— 文本本身可选可复制（.help-text 设了 user-select）
    copied.value = false
  }
}
const keyword = computed(() => String(route.query.k ?? ''))

onMounted(async () => {
  await loadHot()
  await loadPage(keyword.value)
})

watch(() => route.query.k, (v) => {
  loadPage(String(v ?? ''))
  window.scrollTo({ top: 0 })
})

function openKeyword(kw: string) {
  router.push({ name: 'plaza', query: { k: kw } })
}

/** 是不是所有答案都被踩了（决定是否显示「问问新答案」） */
const allDownvoted = computed(() => {
  const list = page.value?.answers ?? []
  if (!list.length) return false
  return list.every(a => a.down >= 2 && a.down > a.up)
})

/** 票数按钮的配置：一处定义，三个按钮共用 */
const VOTE_BTNS = [
  { key: 'up', icon: 'thumbs-up', label: '有帮助' },
  { key: 'down', icon: 'thumbs-down', label: '没帮助' },
  { key: 'outdated', icon: 'clock', label: '已过时' },
] as const
</script>

<template>
  <div class="page plaza">
    <p v-if="error" class="err" role="alert">{{ error }}</p>

    <!-- ==================== 关键词页 ==================== -->
    <template v-if="keyword">
      <header class="kw-head">
        <button class="back" type="button" @click="router.push({ name: 'plaza' })">
          <Icon name="arrow-left" :size="16" /> 返回
        </button>
        <h1 class="kw">{{ page?.keyword ?? keyword }}</h1>
        <p class="kw-meta">
          <span v-if="page?.termEn" class="faint num">{{ page.termEn }}</span>
          <span v-if="page?.askedCount" class="faint">被问过 {{ page.askedCount }} 次</span>
        </p>
      </header>

      <p v-if="loading" class="muted">读取中…</p>

      <template v-else-if="page">
        <div v-if="!page.answers.length" class="empty-wrap">
          <Empty
            icon="chat"
            text="还没有被认可的答案"
            :hint="page.askedCount > 0
              ? '这个问题被问过 ' + page.askedCount + ' 次，但还没有答案被点赞过'
              : '去群里 @ 机器人问问吧'"
          />
        </div>

        <div v-else class="answers">
          <article v-for="a in page.answers" :key="a.statId" class="ans" :class="{ best: a.badge === 'best' }">
            <div class="ans-head">
              <Tag v-if="a.badge === 'best'" tone="flame">最受认可</Tag>
              <Tag v-if="a.badge === 'outdated'" tone="warn">可能已过时</Tag>
              <span class="question">{{ a.question }}</span>
            </div>
            <div class="ans-body">{{ a.answer }}</div>

            <div class="votes">
              <button
                v-for="b in VOTE_BTNS"
                :key="b.key"
                type="button"
                class="vbtn"
                :class="{ active: myVotes[a.statId] === b.key }"
                :disabled="voting === a.statId"
                :aria-pressed="myVotes[a.statId] === b.key"
                :aria-label="b.label"
                @click="vote(a, b.key)"
              >
                <Icon :name="b.icon" :size="14" />
                <span class="vnum num">{{ b.key === 'up' ? a.up : b.key === 'down' ? a.down : a.outdated }}</span>
                <span v-if="b.key === 'outdated'" class="vlabel">已过时</span>
              </button>
            </div>
          </article>
        </div>

        <!-- ★ 第 2 级降级的结果：新生成的答案 -->
        <article v-if="newAnswer" class="ans new-ans">
          <div class="ans-head">
            <Tag tone="flame">新生成的回答</Tag>
            <span class="question">机器人刚为你重新生成</span>
          </div>
          <div class="ans-body">{{ newAnswer }}</div>
        </article>

        <!-- 降级入口 -->
        <div v-if="allDownvoted || page.needsNewAnswer" class="fallback">
          <p class="fb-hint">这些答案好像都不太行？</p>
          <div class="fb-btns">
            <Button variant="primary" :disabled="asking" @click="askNew">
              {{ asking ? '生成中…' : '问问新答案' }}
            </Button>
            <Button :disabled="helping" @click="askHelp">
              {{ helping ? '生成中…' : '求助大佬' }}
            </Button>
          </div>
          <p class="fb-note faint">
            「问问新答案」会让机器人重新生成一条；还是不行就让群友来答。
          </p>

          <div v-if="helpText" class="help-box">
            <p class="help-howto">把下面这句连同 <code>@机器人</code> 一起发到群里：</p>
            <div class="help-text">{{ helpText }}</div>
            <div class="help-actions">
              <Button size="sm" @click="copyHelp">
                <Icon :name="copied ? 'check' : 'copy'" :size="15" />
                {{ copied ? '已复制' : '复制' }}
              </Button>
            </div>
            <p class="faint help-note">
              为什么要这样：网页没法确认你在哪个群，所以只能由你从群里发起 ——
              这样就不会有人拿它往别人的群发消息。
            </p>
          </div>
        </div>
      </template>
    </template>

    <!-- ==================== 广场首页：热词列表 ==================== -->
    <template v-else>
      <PageHeader title="问答广场">
        <template #desc>
          <span v-html="renderInline(t('plaza.intro', '这里是群友们问过、并且被点赞认可的答案。点关键词查看 —— 如果都不满意，可以投票让更好的答案浮现出来。'))" />
        </template>
      </PageHeader>

      <div v-if="hot?.keywords?.length" class="rowlist kw-list">
        <ListRow
          v-for="k in hot.keywords"
          :key="k.keyword"
          :title="k.keyword"
          :sub="k.termEn || ''"
          :meta="num(k.votedCount || k.count)"
          chevron
          @click="openKeyword(k.keyword)"
        />
      </div>
      <Empty
        v-else
        icon="chat"
        text="还没有人点赞过任何答案"
        hint="等群友们用起来，被认可的答案会出现在这里"
      />
    </template>
  </div>
</template>

<style scoped>
.plaza { padding-top: var(--sp-5); padding-bottom: var(--sp-8); max-width: 1040px; }
/* 答案正文仍然收在 760：关键词索引可以用满宽度，但读长答案的版心不能跟着变宽 */
.answers, .fallback, .empty-wrap { max-width: var(--read-max); }
.err {
  color: var(--blight-lift);
  font-size: var(--fs-base);
  background: var(--blight-veil);
  border: 1px solid color-mix(in srgb, var(--blight) 40%, transparent);
  border-radius: var(--r-sm);
  padding: var(--sp-3) var(--sp-4);
  margin-bottom: var(--sp-4);
}

/* ==================== 页头 ====================
   关键词页的标题与资料库、关于共用同一套规格：26px + 左侧余烬刻痕 + 下方一句元信息。
   上一版这里是 38px，比页内所有内容大两档，像一张海报。 */
.kw-head { margin-bottom: var(--sp-5); padding-bottom: var(--sp-3); border-bottom: 1px solid var(--hairline); }
.back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  background: none;
  border: 0;
  color: var(--ink-3);
  font-size: var(--fs-sm);
  cursor: pointer;
  padding: 6px 0;
  min-height: 40px;
  margin-bottom: var(--sp-1);
  text-decoration: none;
}
.back:hover { color: var(--ember); }
.kw {
  position: relative;
  font-size: var(--fs-2xl);
  letter-spacing: var(--tracking-ink);
  margin: 0;
  padding-left: 14px;
}
.kw::before {
  content: '';
  position: absolute;
  left: 0;
  top: .18em;
  bottom: .18em;
  width: 3px;
  border-radius: 1px;
  background: var(--ember);
}
.kw-meta { display: flex; align-items: baseline; gap: var(--sp-3); flex-wrap: wrap; margin: var(--sp-2) 0 0; font-size: var(--fs-xs); }

/* ==================== 答案 ====================
   一条答案 = 一块石板。左侧那道 2px 竖线是"碑文"的界格；
   只有"最受认可"那条的界格是灵火色 —— 用一个颜色标出唯一要读的那条。 */
.answers { display: flex; flex-direction: column; gap: var(--sp-4); }
.ans {
  position: relative;
  background: var(--stone-300);
  border-radius: var(--r-md);
  padding: var(--sp-4) var(--sp-5);
  box-shadow: var(--bevel-raised);
  min-width: 0;
}
.ans::before {
  content: '';
  position: absolute;
  left: 0; top: var(--sp-4); bottom: var(--sp-4);
  width: 2px;
  border-radius: var(--r-pill);
  background: var(--stone-600);
}
.ans.best::before { background: var(--ember); box-shadow: 0 0 12px -2px var(--ember-glow); }
.ans-head { display: flex; align-items: center; gap: var(--sp-2); margin-bottom: var(--sp-3); flex-wrap: wrap; }
.question { font-size: var(--fs-sm); color: var(--ink-3); }
.ans-body {
  font-size: var(--fs-base);
  line-height: 1.85;
  color: var(--ink);
  white-space: pre-wrap;
  overflow-wrap: break-word;
}

/* 投票：图标 + 等宽数字，都是"操作"而不是"表情"。
   上一版是 👍 👎 🕐 三个 emoji —— 每个平台的样式不同，而且自带的高饱和色
   会在这一页唯一的重点（那条最受认可的答案）旁边抢注意力。 */
.votes { display: flex; gap: var(--sp-2); margin-top: var(--sp-4); flex-wrap: wrap; }
.vbtn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: var(--stone-void);
  border: 1px solid var(--edge);
  border-radius: var(--r-pill);
  padding: 0 14px;
  min-height: 34px;
  font-size: var(--fs-sm);
  color: var(--ink-3);
  cursor: pointer;
  box-shadow: var(--bevel-inset);
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease);
}
.vbtn:hover:not(:disabled) { border-color: var(--edge-hover); color: var(--ink); }
.vbtn.active {
  border-color: var(--ember);
  background: var(--ember-veil);
  color: var(--ember-hot);
  box-shadow: var(--bevel-inset), 0 0 12px -4px var(--ember-glow);
}
.vbtn:disabled { opacity: .5; cursor: default; }
.vlabel { font-size: var(--fs-xs); }

/* ==================== 降级入口 ==================== */
.fallback {
  margin-top: var(--sp-5);
  padding: var(--sp-5);
  text-align: center;
  /* 一颗"刻进去的槽"：内容是不确定的、待补的，形式上就不该和答案一样凸出来 */
  background: var(--stone-void);
  border-radius: var(--r-md);
  box-shadow: var(--bevel-inset);
}
.fb-hint { font-size: var(--fs-base); margin-bottom: var(--sp-4); color: var(--ink-2); }
.fb-btns { display: flex; gap: var(--sp-3); justify-content: center; flex-wrap: wrap; }
.fb-note { font-size: var(--fs-xs); margin-top: var(--sp-3); }

.help-box {
  margin-top: var(--sp-5);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--hairline);
  text-align: left;
}
.help-howto { font-size: var(--fs-sm); color: var(--ink-2); margin-bottom: var(--sp-2); }
.help-text {
  background: var(--stone-200);
  border: 1px solid var(--ember-veil);
  border-radius: var(--r-sm);
  padding: var(--sp-3);
  font-size: var(--fs-sm);
  color: var(--ink);
  user-select: all;
}
.help-actions { margin-top: var(--sp-2); }
.help-note { font-size: var(--fs-xs); margin-top: var(--sp-2); line-height: 1.75; }

.new-ans { margin-top: var(--sp-5); box-shadow: var(--bevel-raised), 0 0 0 1px var(--ember-veil); }
.new-ans::before { background: var(--ember); }

/* 热词清单：宽屏两列，把 24 条压成 12 行。
   ⚠️ 分列后「上一行的下边框」会跑到右列第一项头上，所以关掉相邻边框、改成每项自带底边。 */
@media (min-width: 900px) {
  .kw-list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    column-gap: var(--sp-5);
  }
  .kw-list > * + * { border-top: 0; }
  .kw-list > * { border-bottom: 1px solid var(--hairline); }
}

.empty-wrap { padding: var(--sp-6) 0; }

@media (max-width: 760px) {
  .ans { padding: var(--sp-4); }
}
</style>