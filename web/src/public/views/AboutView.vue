<script setup lang="ts">
import { computed } from 'vue'
import { renderInline } from '../renderInline'
import { site, t } from '../useSiteText'
import Icon from '@shared/ui/Icon.vue'

/**
 * 「回答是怎么产生的」正文按换行拆成有序列表：一个文案块承载整段正文，
 * 后台是多行编辑框（一行一条）。默认值 = 原来的 4 条硬编码。
 */
const HOW_STEPS_DEFAULT = [
  '把问题变成向量，在本地资料库里找最相关的几段',
  '同时用中英术语表做一次关键词匹配',
  '两路结果按排名融合，取前几条',
  '把资料交给大模型，让它依据资料作答',
].join('\n')

const howSteps = computed(() =>
  t('about.how_body', HOW_STEPS_DEFAULT).split('\n').map((s) => s.trim()).filter(Boolean))
</script>

<template>
  <div class="page about read">
    <header class="head">
      <p class="eyebrow">About</p>
      <h1 class="title">关于</h1>
    </header>

    <section class="sec">
      <h2 class="h"><span v-html="renderInline(t('about.what_title', '飘雪喵是什么'))" /></h2>
      <p class="muted">
        <span v-html="renderInline(t('about.what_body', '一个《雾锁王国》（Enshrouded）的问答助手。在 QQ 群里 @ 我提问，我会先在本地资料库里检索，再依据检索到的内容回答 —— 而不是凭空编。'))" />
      </p>
    </section>

    <section class="sec">
      <h2 class="h"><span v-html="renderInline(t('about.source_title', '资料从哪来'))" /></h2>
      <p class="muted">
        <span v-html="renderInline(t('about.source_body', '全部来自 [官方 Enshrouded Wiki](https://enshrouded.wiki.gg/wiki/Main_Page)，采用 [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/) 许可。游戏内容与素材的版权归 [Keen Games](https://www.keengames.com/) 所有。本站非商业用途。'))" />
      </p>
    </section>

    <section v-if="site && (site.groupName || site.groupDesc)" class="sec">
      <h2 class="h">加入我们</h2>
      <p v-if="site.groupDesc" class="muted">{{ site.groupDesc }}</p>
      <div v-if="site.groupName || site.groupNumber" class="group-box">
        <div v-if="site.groupName" class="group-row">
          <span class="group-label">群名称</span>
          <span class="group-value">{{ site.groupName }}</span>
        </div>
        <div v-if="site.groupNumber" class="group-row">
          <span class="group-label">群号</span>
          <span class="group-value num">{{ site.groupNumber }}</span>
        </div>
        <p v-if="site.joinHint" class="faint join-hint">{{ site.joinHint }}</p>
      </div>
    </section>

    <section class="sec">
      <h2 class="h"><span v-html="renderInline(t('about.how_title', '回答是怎么产生的'))" /></h2>
      <!-- 这是**真的流程**（先后有依赖关系），所以编号是有信息的，不是装饰 -->
      <ol class="steps">
        <li v-for="(step, i) in howSteps" :key="i">
          <span class="step-n num">{{ i + 1 }}</span>
          <span class="step-t">{{ step }}</span>
        </li>
      </ol>
      <p class="muted note">
        <Icon name="info" :size="15" />
        <span>资料里没有的内容，我会说「没查到」而不是编一个 —— 如果你发现我编了，那是 bug，欢迎反馈。</span>
      </p>
    </section>

    <section class="sec last">
      <h2 class="h"><span v-html="renderInline(t('about.privacy_title', '隐私'))" /></h2>
      <p class="muted">
        <span v-html="renderInline(t('about.privacy_body', '这个公开站**不展示任何群成员的提问记录**。站上能看到的只有游戏资料本身，以及几个不敏感的汇总数字。'))" />
      </p>
    </section>
  </div>
</template>

<style scoped>
.about { padding-top: var(--sp-6); padding-bottom: var(--sp-8); }
.head { margin-bottom: var(--sp-5); }
.title { font-size: var(--fs-3xl); margin-top: var(--sp-2); letter-spacing: var(--tracking-ink); }

/* 一节 = 一个 h2 + 正文。节与节之间只靠留白和一条发丝线分开，
   不再给每一节套一个面板 —— 一整页面板会把"关于"读成仪表盘。 */
.sec { padding: var(--sp-5) 0; border-top: 1px solid var(--hairline); }
.sec.last { padding-bottom: 0; }
.sec:first-of-type { border-top: 0; padding-top: 0; }

.h {
  font-size: var(--fs-xl);
  color: var(--ink);
  letter-spacing: .04em;
  margin: 0 0 var(--sp-3);
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}
.h::before {
  content: '';
  flex: none;
  width: 3px;
  height: 17px;
  border-radius: 1px;
  background: var(--ember);
}
p { font-size: var(--fs-base); line-height: 1.9; }

.group-box {
  margin-top: var(--sp-3);
  padding: var(--sp-4);
  background: var(--stone-void);
  border-radius: var(--r-md);
  box-shadow: var(--bevel-inset);
}
.group-row { display: flex; gap: var(--sp-4); padding: 3px 0; }
.group-label { color: var(--ink-3); font-size: var(--fs-sm); width: 64px; flex: none; }
.group-value { color: var(--ink); font-size: var(--fs-base); }
.group-value.num { color: var(--ember); }
.join-hint { font-size: var(--fs-xs); margin-top: var(--sp-2); line-height: 1.8; }

/* 流程：编号在这里是**信息**（第 2 步依赖第 1 步的结果），不是装饰 */
.steps { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--sp-3); }
.steps li { display: flex; gap: var(--sp-3); align-items: flex-start; font-size: var(--fs-base); color: var(--ink-2); line-height: 1.8; }
.step-n {
  flex: none;
  display: grid;
  place-items: center;
  width: 24px; height: 24px;
  margin-top: 2px;
  border-radius: 50%;
  background: var(--stone-void);
  box-shadow: var(--bevel-inset);
  font-size: var(--fs-xs);
  color: var(--ember);
}
.step-t { min-width: 0; }

.note { display: flex; align-items: flex-start; gap: var(--sp-2); margin-top: var(--sp-4); font-size: var(--fs-sm); }
.note :deep(svg) { margin-top: 4px; color: var(--ink-4); }

@media (max-width: 760px) {
  .title { font-size: var(--fs-2xl); }
}
</style>
