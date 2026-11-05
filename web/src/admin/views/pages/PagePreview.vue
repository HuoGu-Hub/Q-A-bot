<script setup lang="ts">
import { renderInline } from '@/public/renderInline'

const props = defineProps<{
  page: string
  texts: Record<string, string>
  defaults: Record<string, string>
  botName: string
}>()

const KB_SAMPLE = 4131
const placeholders: Record<string, string> = {
  kb: KB_SAMPLE.toLocaleString('en-US'),
  year: String(new Date().getFullYear()),
}

function raw(key: string): string {
  return props.texts[key] ?? ''
}

function plainText(s: string): string {
  return s.replace(/\{(\w+)\}/g, (m, name: string) =>
    name === 'bot'
      ? props.botName
      : Object.prototype.hasOwnProperty.call(placeholders, name) ? placeholders[name] : m)
}

function plain(key: string): string {
  return plainText(raw(key))
}

function htmlOf(s: string): string {
  return renderInline(plainText(s))
}

function html(key: string): string {
  return htmlOf(raw(key))
}

function steps(key: string): string[] {
  return (has(key) ? plain(key) : '').split('\n').map((s) => s.trim()).filter(Boolean)
}

const has = (key: string) => key in props.texts
const anyHtml = (keys: string[]) => keys.some(has) && keys.some((k) => html(k))

const isChanged = (key: string) =>
  has(key) && props.texts[key] !== props.defaults[key]
const mark = (key: string) => ({ changed: isChanged(key) })

const TITLE_HEAD = '知识'
const TITLE_TAIL = '问答'
</script>

<template>
  <div class="preview">
    <div class="preview-cap">
      <span class="cap-line" aria-hidden="true" />
      <span class="faint">火色标记 = 已改动</span>
    </div>

    <div class="stage">
      <div v-if="page === 'layout'" class="m-foot">
        <div v-if="has('footer_copy')" class="m-faint m-html" v-html="html('footer_copy')" />
      </div>

      <div v-else-if="page === 'home'" class="m-home">
        <div class="m-brand">
          <span class="m-spark" aria-hidden="true">✦</span>
          <span class="m-brand-name">{{ props.botName }}</span>
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

      <div v-else-if="page === 'about'" class="m-about">
        <div class="m-section">关于</div>
        <template
          v-for="g in [
            { t: 'what_title', b: 'what_body' },
            { t: 'source_title', b: 'source_body' },
            { t: 'credit_title', b: 'credit_body' },
            { t: 'how_title', b: 'how_body' },
            { t: 'privacy_title', b: 'privacy_body' },
          ]"
          :key="g.t"
        >
          <div v-if="has(g.t) || has(g.b)" class="m-group">
            <h4 v-if="has(g.t)" class="m-h" :class="mark(g.t)">{{ plain(g.t) }}</h4>
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

      <div v-else-if="page === 'library'" class="m-library">
        <h3 class="m-title">资料库</h3>
        <div class="m-search">
          <span class="m-search-ph faint">输入关键词… 比如「怎么合成」「上限是多少」</span>
          <span class="m-search-btn">搜索</span>
        </div>
        <div v-if="has('empty_title')" class="m-empty">
          <div class="m-empty-glyph" aria-hidden="true">✦</div>
          <div class="m-empty-t" :class="mark('empty_title')">
            {{ plain('empty_title') }}
          </div>
        </div>
      </div>

      <div v-else-if="page === 'notfound'" class="m-nf">
        <div class="m-nf-box">
          <h3 v-if="has('title')" class="m-nf-title" :class="mark('title')">{{ plain('title') }}</h3>
          <span class="m-nf-btn">回到首页</span>
        </div>
      </div>

      <p v-else class="m-none faint">这个页面没有可预览的固定文案。</p>
    </div>
  </div>
</template>

<style scoped>
.preview { display: flex; flex-direction: column; gap: var(--sp-3); }

.preview-cap { display: flex; align-items: center; gap: var(--sp-2); font-size: var(--fs-meta); }
.cap-line { width: 2px; height: 12px; background: var(--flame); border-radius: var(--r-xs); flex: none; }

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

.changed {
  box-shadow: inset 2px 0 0 var(--flame);
  background: var(--flame-veil);
  border-radius: var(--r-xs);
  padding-left: var(--sp-2);
}

.m-html :deep(a) { color: var(--flame); text-decoration: underline; text-underline-offset: 2px; }
.m-html :deep(strong) { color: var(--ink); font-weight: var(--fw-semi); }

.m-faint { color: var(--ink-faint); font-size: var(--fs-meta); line-height: var(--lh-normal); }
.m-sub { color: var(--ink-dim); font-size: var(--fs-body); line-height: var(--lh-normal); margin: 0; }
.m-p { color: var(--ink-dim); font-size: var(--fs-meta); line-height: var(--lh-normal); margin: 0; }
.m-none { font-size: var(--fs-meta); margin: 0; }

.m-home { display: flex; flex-direction: column; gap: var(--sp-3); }
.m-brand { display: flex; align-items: center; gap: var(--sp-1); font-size: var(--fs-meta); color: var(--ink-dim); }
.m-spark { color: var(--flame); }
.m-brand-name { font-weight: var(--fw-medium); }
.m-display {
  font-family: var(--font-title);
  font-size: var(--fs-xl);
  font-weight: var(--fw-semi);
  letter-spacing: .02em;
  margin: 0;
  line-height: var(--lh-tight);
}
.m-flame { color: var(--flame); }

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

.m-about { display: flex; flex-direction: column; gap: var(--sp-3); }
.m-group { display: flex; flex-direction: column; gap: var(--sp-1); }
.m-h { color: var(--flame); font-size: var(--fs-body); font-weight: var(--fw-medium); margin: 0; }

.m-library { display: flex; flex-direction: column; gap: var(--sp-3); }
.m-title {
  font-family: var(--font-title);
  font-size: var(--fs-xl);
  font-weight: var(--fw-semi);
  margin: 0;
  line-height: var(--lh-tight);
}
.m-empty {
  background: var(--surface-raised);
  border-radius: var(--r-sm);
  padding: var(--sp-4) var(--sp-3);
  text-align: center;
}
.m-empty-glyph { color: var(--copper-dim); font-size: var(--fs-body); margin-bottom: var(--sp-1); }
.m-empty-t { color: var(--ink-dim); font-size: var(--fs-meta); }

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
