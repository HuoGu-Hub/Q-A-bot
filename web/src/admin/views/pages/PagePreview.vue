<script setup lang="ts">
/**
 * 页面信息 —— 缩略预览。
 *
 * 编辑区旁边放一个「大概长什么样」的缩略版：改完一个字不用保存、不用切到公开站，
 * 就能看出这句话落在页面的哪个位置、有多长。
 *
 * ⚠️ 刻度是**刻意缩小**的：它表达的是版式与层级（谁大谁小、谁在谁上面），
 * 不是像素级还原。真正的样式以公开站为准 —— 这里不复制公开站的样式表，
 * 只按位置把块摆进去，所以公开站改版时这里不会跟着崩，但也可能落后。
 *
 * ⚠️ 所有文案一律走 renderInline() 再 v-html。它是**唯一**保证
 * 「先整体转义、再替换标记」的入口，自己拼 HTML 等于把 XSS 口子重新打开。
 */
import { renderInline } from '@/public/renderInline'

const props = defineProps<{
  /** page key：layout / home / about / library / plaza / notfound */
  page: string
  /** block key → 当前生效文案（含未保存的改动） */
  texts: Record<string, string>
  /** block key → 默认文案（用来标出哪些块被改过） */
  defaults: Record<string, string>
}>()

/**
 * 占位符替换。公开站由 useSiteText 的 t() 负责，这里复刻同一套语义：
 *   {kb}   → 知识库条目数；预览拿不到真实统计，用示例数字
 *   {year} → 当前年份
 * 认不出的占位符原样留着（和 t() 一致）—— 总比被悄悄吞掉好。
 */
const KB_SAMPLE = 4131
const placeholders: Record<string, string> = {
  kb: KB_SAMPLE.toLocaleString('en-US'),
  year: String(new Date().getFullYear()),
}

function raw(key: string): string {
  return props.texts[key] ?? ''
}

/** 替换占位符，返回纯文本 */
function plainText(s: string): string {
  return s.replace(/\{(\w+)\}/g, (m, name: string) =>
    Object.prototype.hasOwnProperty.call(placeholders, name) ? placeholders[name] : m)
}

/** 取文案并替换占位符，返回纯文本（不渲染标记） */
function plain(key: string): string {
  return plainText(raw(key))
}

/** 任意文本 → 替换占位符 → 行内标记渲染，返回可安全 v-html 的串 */
function htmlOf(s: string): string {
  return renderInline(plainText(s))
}

/** 取文案 → 替换占位符 → 行内标记渲染，返回可安全 v-html 的串 */
function html(key: string): string {
  return htmlOf(raw(key))
}

/**
 * 多行块 → 逐行列表（与公开站一致：空行忽略、每行一项）。
 * 「关于 · 回答怎么产生」与「首页 · 怎么用」都是这种块。
 */
function steps(key: string): string[] {
  return (has(key) ? plain(key) : '').split('\n').map((s) => s.trim()).filter(Boolean)
}

/** 该块是否存在（页面块清单由后端注册表决定，前端不假设一定齐全） */
const has = (key: string) => key in props.texts
const anyHtml = (keys: string[]) => keys.some(has) && keys.some((k) => html(k))

/** 改过的块：左边一条火色竖线 + 淡色底 */
const isChanged = (key: string) =>
  has(key) && props.texts[key] !== props.defaults[key]
const mark = (key: string) => ({ changed: isChanged(key) })

/** 标题加亮点：站点名「雾中灵火」的「灵火」用火色 —— 与公开站一致，但不可编辑 */
const TITLE_HEAD = '雾中'
const TITLE_TAIL = '灵火'
</script>

<template>
  <div class="preview">
    <!-- 唯一允许的一句功能性说明：解释那条火色线是什么意思 -->
    <div class="preview-cap">
      <span class="cap-line" aria-hidden="true" />
      <span class="faint">火色标记 = 已改动</span>
    </div>

    <div class="stage">
      <!-- ==================== 全站页脚 ==================== -->
      <div v-if="page === 'layout'" class="m-foot">
        <div v-if="has('footer_note')" class="m-faint m-html" v-html="html('footer_note')" />
        <div v-if="has('footer_copy')" class="m-faint m-html" v-html="html('footer_copy')" />
      </div>

      <!-- ==================== 首页 ==================== -->
      <div v-else-if="page === 'home'" class="m-home">
        <div class="m-brand">
          <span class="m-spark" aria-hidden="true">✦</span>
          <span class="m-brand-name">飘雪喵</span>
        </div>
        <h3 class="m-display">
          {{ TITLE_HEAD }}<span class="m-flame">{{ TITLE_TAIL }}</span>
        </h3>
        <p
          v-if="has('tagline')"
          class="m-sub m-html"
          :class="mark('tagline')"
          v-html="html('tagline')"
        />
        <!-- 首页没有搜索框（2026-09-27 起）：搜索只在「资料库」页。
             预览要跟着公开站走，否则后台会显示一个页面上不存在的控件。
             ⚠️ .m-search 那套样式仍然要给下面的「资料库」预览用，别一起删。 -->
        <!-- 「怎么用」= 一块多行文案，按行拆成有序列表（与公开站 HomeView 一致） -->
        <div v-if="has('howto_title') || anyHtml(['howto_body'])" class="m-howto">
          <div v-if="has('howto_title')" class="m-section" :class="mark('howto_title')">
            {{ plain('howto_title') }}
          </div>
          <ol class="m-steps">
            <li
              v-for="(s, i) in steps('howto_body')"
              :key="i"
              class="m-html"
              :class="mark('howto_body')"
              v-html="htmlOf(s)"
            />
          </ol>
        </div>
      </div>

      <!-- ==================== 关于 ==================== -->
      <div v-else-if="page === 'about'" class="m-about">
        <div class="m-section">关于</div>
        <template
          v-for="g in [
            { t: 'what_title', b: 'what_body' },
            { t: 'source_title', b: 'source_body' },
            { t: 'how_title', b: 'how_body' },
            { t: 'privacy_title', b: 'privacy_body' },
          ]"
          :key="g.t"
        >
          <div v-if="has(g.t) || has(g.b)" class="m-group">
            <h4 v-if="has(g.t)" class="m-h" :class="mark(g.t)">{{ plain(g.t) }}</h4>
            <!-- how_body：换行分隔的步骤 → 有序列表（与公开站一致） -->
            <ol v-if="g.b === 'how_body'" class="m-steps" :class="mark(g.b)">
              <li v-for="(s, i) in steps('how_body')" :key="i">{{ s }}</li>
            </ol>
            <p
              v-else-if="has(g.b)"
              class="m-p m-html"
              :class="mark(g.b)"
              v-html="html(g.b)"
            />
          </div>
        </template>
      </div>

      <!-- ==================== 资料库 ==================== -->
      <div v-else-if="page === 'library'" class="m-library">
        <h3 class="m-title">资料库</h3>
        <p
          v-if="has('subtitle')"
          class="m-sub m-html"
          :class="mark('subtitle')"
          v-html="html('subtitle')"
        />
        <div class="m-search">
          <span class="m-search-ph faint">搜点什么… 比如「废料杯」「爆炸箭」「游牧高地」</span>
          <span class="m-search-btn">搜索</span>
        </div>
        <div v-if="has('empty_title') || has('empty_hint')" class="m-empty">
          <div class="m-empty-glyph" aria-hidden="true">✦</div>
          <div v-if="has('empty_title')" class="m-empty-t" :class="mark('empty_title')">
            {{ plain('empty_title') }}
          </div>
          <div v-if="has('empty_hint')" class="m-empty-h m-html" :class="mark('empty_hint')" v-html="html('empty_hint')" />
        </div>
      </div>

      <!-- ==================== 问答广场 ==================== -->
      <div v-else-if="page === 'plaza'" class="m-plaza">
        <div class="m-section-title">
          <span class="m-rule" aria-hidden="true" />
          <span>问答广场</span>
          <span class="m-rule" aria-hidden="true" />
        </div>
        <p v-if="has('intro')" class="m-p m-html" :class="mark('intro')" v-html="html('intro')" />
      </div>

      <!-- ==================== 404 ==================== -->
      <div v-else-if="page === 'notfound'" class="m-nf">
        <div class="m-nf-box">
          <h3 v-if="has('title')" class="m-nf-title" :class="mark('title')">{{ plain('title') }}</h3>
          <p v-if="has('desc')" class="m-nf-desc m-html" :class="mark('desc')" v-html="html('desc')" />
          <span class="m-nf-btn">回到首页</span>
        </div>
      </div>

      <p v-else class="m-none faint">这个页面没有可预览的固定文案。</p>
    </div>
  </div>
</template>

<style scoped>
/*
  层级靠背景深浅表达（--surface-inset 的舞台 + --surface-raised 的子块），
  容器只用一道 --edge-soft 柔边。不做像素级还原，只保住「谁大谁小、谁在谁上面」。
  字号一律走令牌，整体比公开站小一号 —— 它是预览。
*/
.preview { display: flex; flex-direction: column; gap: var(--sp-3); }

.preview-cap { display: flex; align-items: center; gap: var(--sp-2); font-size: var(--fs-meta); }
.cap-line { width: 2px; height: 12px; background: var(--flame); border-radius: var(--r-xs); flex: none; }

/* 舞台：凹槽底色 + 柔和边缘，把「这是另外一个东西」讲清楚而不描一圈重边 */
.stage {
  background: var(--surface-inset);
  border: 1px solid var(--edge-soft);
  border-radius: var(--r-md);
  padding: var(--sp-4);
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  overflow-wrap: anywhere;
}

/* 改过的块：左侧火色细竖线 + 极淡火色底。不动布局，只做叠加 */
.changed {
  box-shadow: inset 2px 0 0 var(--flame);
  background: var(--flame-veil);
  border-radius: var(--r-xs);
  padding-left: var(--sp-2);
}

/* 行内标记渲染出来的东西在缩略里有对应刻度 */
.m-html :deep(a) { color: var(--flame); text-decoration: underline; text-underline-offset: 2px; }
.m-html :deep(strong) { color: var(--ink); font-weight: var(--fw-semi); }

.m-faint { color: var(--ink-faint); font-size: var(--fs-meta); line-height: var(--lh-normal); }
.m-sub { color: var(--ink-dim); font-size: var(--fs-body); line-height: var(--lh-normal); margin: 0; }
.m-p { color: var(--ink-dim); font-size: var(--fs-meta); line-height: var(--lh-normal); margin: 0; }
.m-none { font-size: var(--fs-meta); margin: 0; }

/* ---------- 首页 ---------- */
.m-home { display: flex; flex-direction: column; gap: var(--sp-3); }
.m-brand { display: flex; align-items: center; gap: var(--sp-1); font-size: var(--fs-meta); color: var(--ink-dim); }
.m-spark { color: var(--flame); }
.m-brand-name { font-weight: var(--fw-medium); }
/* 缩略版大标题：比编辑区任何字都大，让它一眼就是「标题」 */
.m-display {
  font-family: var(--font-title);
  font-size: var(--fs-xl);
  font-weight: var(--fw-semi);
  letter-spacing: .02em;
  margin: 0;
  line-height: var(--lh-tight);
}
.m-flame { color: var(--flame); }

/* 搜索框：凹槽 + 边框 —— 它是可交互控件，只有它配描边 */
.m-search {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  background: var(--surface-page);
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  padding: var(--sp-2) var(--sp-3);
}
.m-search-ph { flex: 1; min-width: 0; color: var(--ink-faint); font-size: var(--fs-meta); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.m-search-btn {
  flex: none;
  background: var(--flame);
  color: var(--ink-on-flame);
  border-radius: var(--r-xs);
  padding: 1px var(--sp-2);
  font-size: var(--fs-meta);
  font-weight: var(--fw-medium);
}

.m-howto { display: flex; flex-direction: column; gap: var(--sp-2); }
.m-section {
  font-family: var(--font-title);
  font-size: var(--fs-section);
  font-weight: var(--fw-medium);
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.m-section::after { content: ''; height: 1px; flex: 1; background: var(--edge-soft); }

.m-steps { margin: 0; padding-left: 1.4em; display: flex; flex-direction: column; gap: var(--sp-1); }
.m-steps li { color: var(--ink-dim); font-size: var(--fs-meta); line-height: var(--lh-normal); }
.m-steps li::marker { color: var(--copper); font-family: var(--font-mono); }

/* ---------- 关于 ---------- */
.m-about { display: flex; flex-direction: column; gap: var(--sp-3); }
.m-group { display: flex; flex-direction: column; gap: var(--sp-1); }
.m-h { color: var(--flame); font-size: var(--fs-body); font-weight: var(--fw-medium); margin: 0; }

/* ---------- 资料库 ---------- */
.m-library { display: flex; flex-direction: column; gap: var(--sp-3); }
.m-title {
  font-family: var(--font-title);
  font-size: var(--fs-xl);
  font-weight: var(--fw-semi);
  margin: 0;
  line-height: var(--lh-tight);
}
/* 空态区：抬升面把它和上面的正文分开，靠色差而不是描边 */
.m-empty {
  background: var(--surface-raised);
  border-radius: var(--r-sm);
  padding: var(--sp-4) var(--sp-3);
  text-align: center;
}
.m-empty-glyph { color: var(--copper-dim); font-size: var(--fs-body); margin-bottom: var(--sp-1); }
.m-empty-t { color: var(--ink-dim); font-size: var(--fs-meta); }
.m-empty-h { color: var(--ink-faint); font-size: var(--fs-meta); margin-top: var(--sp-1); line-height: var(--lh-normal); }

/* ---------- 广场 ---------- */
.m-plaza { display: flex; flex-direction: column; gap: var(--sp-3); }
.m-section-title {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-family: var(--font-title);
  font-size: var(--fs-section);
  font-weight: var(--fw-medium);
}
.m-rule { height: 1px; flex: 1; background: var(--copper-dim); }

/* ---------- 404 ---------- */
.m-nf { display: flex; align-items: center; justify-content: center; padding: var(--sp-5) 0; }
.m-nf-box {
  background: var(--surface-raised);
  border-radius: var(--r-md);
  padding: var(--sp-5) var(--sp-4);
  text-align: center;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-2);
}
.m-nf-title {
  font-family: var(--font-title);
  font-size: var(--fs-xl);
  font-weight: var(--fw-semi);
  margin: 0;
  line-height: var(--lh-tight);
}
.m-nf-desc { color: var(--ink-dim); font-size: var(--fs-meta); margin: 0; line-height: var(--lh-normal); }
/* 纯装饰按钮：不可点，所以不做悬浮反馈，也不给 pointer */
.m-nf-btn {
  background: linear-gradient(180deg, var(--flame), var(--flame-deep));
  color: var(--ink-on-flame);
  border-radius: var(--r-sm);
  padding: var(--sp-1) var(--sp-4);
  font-size: var(--fs-meta);
  font-weight: var(--fw-medium);
  margin-top: var(--sp-1);
}
</style>
