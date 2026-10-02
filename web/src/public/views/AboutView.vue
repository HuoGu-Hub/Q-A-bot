<script setup lang="ts">
import { computed } from 'vue'
import { renderInline } from '../renderInline'
import { site, t } from '../useSiteText'
import Panel from '@shared/ui/Panel.vue'

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
  <div class="page about">
    <section class="section-title">关于</section>

    <Panel corners>
      <h2 class="h"><span v-html="renderInline(t('about.what_title', '飘雪喵是什么'))" /></h2>
      <!-- 默认值 = 原来硬编码的正文：后端文案拿不到时页面照常有字 -->
      <p class="muted">
        <span v-html="renderInline(t('about.what_body', '一个《雾锁王国》（Enshrouded）的问答助手。在 QQ 群里 @ 我提问，我会先在本地资料库里检索，再依据检索到的内容回答 —— 而不是凭空编。'))" />
      </p>

      <h2 class="h"><span v-html="renderInline(t('about.source_title', '资料从哪来'))" /></h2>
      <p class="muted">
        <span v-html="renderInline(t('about.source_body', '全部来自 [官方 Enshrouded Wiki](https://enshrouded.wiki.gg/wiki/Main_Page)，采用 [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/) 许可。游戏内容与素材的版权归 [Keen Games](https://www.keengames.com/) 所有。本站非商业用途。'))" />
      </p>

      <template v-if="site && (site.groupName || site.groupDesc)">
        <h2 class="h">加入我们</h2>
        <p v-if="site.groupDesc" class="muted">{{ site.groupDesc }}</p>
        <div v-if="site.groupName || site.groupNumber" class="group-box">
          <div class="group-row">
            <span class="group-label">群名称</span>
            <span class="group-value">{{ site.groupName || "—" }}</span>
          </div>
          <div v-if="site.groupNumber" class="group-row">
            <span class="group-label">群号</span>
            <span class="group-value num">{{ site.groupNumber }}</span>
          </div>
          <p v-if="site.joinHint" class="faint join-hint">{{ site.joinHint }}</p>
        </div>
      </template>

      <h2 class="h"><span v-html="renderInline(t('about.how_title', '回答是怎么产生的'))" /></h2>
      <ol class="steps muted">
        <li v-for="(step, i) in howSteps" :key="i">{{ step }}</li>
      </ol>
      <p class="muted">
        资料里没有的内容，我会说「没查到」而不是编一个 —— 如果你发现我编了，那是 bug，欢迎反馈。
      </p>

      <h2 class="h"><span v-html="renderInline(t('about.privacy_title', '隐私'))" /></h2>
      <p class="muted">
        <span v-html="renderInline(t('about.privacy_body', '这个公开站**不展示任何群成员的提问记录**。站上能看到的只有游戏资料本身，以及几个不敏感的汇总数字。'))" />
      </p>
    </Panel>
  </div>
</template>

<style scoped>
.about { padding-top: var(--sp-6); padding-bottom: var(--sp-7); max-width: 820px; }
.h {
  font-size: var(--fs-md);
  color: var(--flame);
  margin: var(--sp-5) 0 var(--sp-2);
}
.h:first-child { margin-top: 0; }
p { font-size: var(--fs-sm); }
p:last-child { margin-bottom: 0; }
.steps { font-size: var(--fs-sm); padding-left: 1.3em; margin: 0 0 var(--sp-3); }
.steps li { margin-bottom: 4px; }
.group-box {
  margin-top: var(--sp-3);
  padding: var(--sp-4);
  background: var(--bg-sunken);
  border: 1px solid var(--line);
  border-left: 2px solid var(--flame);
  border-radius: var(--r-sm);
}
.group-row { display: flex; gap: var(--sp-4); padding: 3px 0; }
.group-label { color: var(--mist); font-size: var(--fs-sm); width: 64px; flex: none; }
.group-value { color: var(--ink); font-size: var(--fs-sm); }
.group-value.num { font-family: var(--font-mono); color: var(--flame-bright); }
.join-hint { font-size: var(--fs-xs); margin-top: var(--sp-2); line-height: 1.8; }
</style>
