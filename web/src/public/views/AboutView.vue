<script setup lang="ts">
import { computed } from 'vue'
import { renderInline } from '../renderInline'
import { site, t } from '../useSiteText'
import PageHeader from '@shared/ui/PageHeader.vue'

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
    <PageHeader title="关于" />

    <section class="sec">
      <h2 class="h"><span v-html="renderInline(t('about.what_title', '{bot} 是什么'))" /></h2>
      <p class="muted">
        <span v-html="renderInline(t('about.what_body', '一个面向游戏社群的知识库问答机器人。在 QQ 群里 @ 我提问，我会先在本地知识库里检索，再依据检索到的内容回答 —— 而不是凭空编造。'))" />
      </p>
    </section>

    <section class="sec">
      <h2 class="h"><span v-html="renderInline(t('about.source_title', '资料从哪来'))" /></h2>
      <p class="muted">
        <span v-html="renderInline(t('about.source_body', '资料来自本项目导入的知识库文档。内容与素材的版权归各自来源所有，本站仅用于非商业问答。'))" />
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
    </section>

    <section class="sec last">
      <h2 class="h"><span v-html="renderInline(t('about.privacy_title', '隐私'))" /></h2>
      <p class="muted">
        <span v-html="renderInline(t('about.privacy_body', '这个公开站**不展示任何群成员的提问记录**。站上能看到的只有知识库资料本身，以及几个不敏感的汇总数字。'))" />
      </p>
    </section>
  </div>
</template>

<style scoped>
.about { padding-top: var(--sp-5); padding-bottom: var(--sp-8); }

/* 一节 = 一个 h2 + 正文。节与节之间只靠留白和一条发丝线分开，
   不再给每一节套一个面板 —— 一整页面板会把"关于"读成仪表盘。 */
.sec { padding: var(--sp-5) 0; border-top: 1px solid var(--hairline); scroll-margin-top: calc(var(--header-h) + 12px); }
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

@media (max-width: 760px) {
  .sec { padding: var(--sp-4) 0; }
  .h { font-size: var(--fs-lg); }
}
</style>