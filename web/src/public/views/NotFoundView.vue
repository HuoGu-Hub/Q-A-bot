<script setup lang="ts">
import { t } from '../useSiteText'
import { renderInline } from '../renderInline'
import Icon from '@shared/ui/Icon.vue'
</script>

<template>
  <div class="page nf">
    <span class="glyph" aria-hidden="true"><Icon name="flame" :size="26" /></span>
    <h1 class="t"><span v-html="renderInline(t('notfound.title', '迷失在雾里了'))" /></h1>
    <p class="muted"><span v-html="renderInline(t('notfound.desc', '这个页面不存在。也许你该回到有火光的地方。'))" /></p>
    <RouterLink class="go" to="/">
      <Icon name="home" :size="16" /> 回到首页
    </RouterLink>
  </div>
</template>

<style scoped>
.nf {
  max-width: 520px;
  padding-top: var(--sp-8);
  padding-bottom: var(--sp-8);
  text-align: center;
}
.glyph {
  display: grid;
  place-items: center;
  width: 60px; height: 60px;
  margin: 0 auto var(--sp-5);
  border-radius: 50%;
  background: var(--stone-void);
  box-shadow: var(--bevel-inset);
  color: var(--ember);
  filter: drop-shadow(0 0 16px rgba(242, 171, 85, .22));
}
.t { font-size: var(--fs-2xl); margin-bottom: var(--sp-2); }
.go {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-5);
  padding: 10px 20px;
  min-height: 44px;
  border-radius: var(--r-sm);
  text-decoration: none;
  background: var(--stone-400);
  border: 1px solid var(--edge);
  color: var(--ink);
  font-size: var(--fs-base);
}
.go:hover { background: var(--stone-500); border-color: var(--edge-hover); color: var(--ink); text-decoration: none; }
</style>
