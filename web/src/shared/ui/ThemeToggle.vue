<script setup lang="ts">
/**
 * 日间 / 夜间切换。
 *
 * <p>一枚 36×36 的图标按钮，夜间显示太阳、日间显示月亮 —— 按下去切到**另一个**模式，
 * 图标画出的是「切过去会是什么」，不是「现在是什么」。这是开关类控件最常见的歧义，
 * 所以可读名同时写清楚目标：「切换到日间模式」。
 *
 * <p>按钮不吞焦点：焦圈用全站统一的 outline，不自绘。
 */
import Icon from './Icon.vue'
import { useTheme } from '@shared/theme/useTheme'

const { isDay, toggle } = useTheme()
</script>

<template>
  <button
    type="button"
    class="tt"
    :aria-label="isDay ? '切换到夜间模式' : '切换到日间模式'"
    :aria-pressed="isDay"
    :title="isDay ? '日间 · 点按切到夜间' : '夜间 · 点按切到日间'"
    @click="toggle"
  >
    <span class="ic" :class="{ on: isDay }" aria-hidden="true">
      <Icon :name="isDay ? 'moon' : 'sun'" :size="17" />
    </span>
  </button>
</template>

<style scoped>
.tt {
  flex: none;
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 1px solid var(--edge);
  border-radius: var(--r-sm);
  background: var(--stone-400);
  color: var(--ink-2);
  cursor: pointer;
  box-shadow: var(--bevel-shelf);
  transition: background var(--dur-fast) var(--ease),
              border-color var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease);
}
.tt:hover {
  background: var(--stone-500);
  border-color: var(--edge-hover);
  color: var(--ember);
}
.tt:active { background: var(--stone-300); box-shadow: var(--bevel-inset); }

/* 图标换位时转一点点：15 度足够表达「换了个状态」，90 度会像加载动画 */
.ic { display: grid; place-items: center; transition: transform var(--dur) var(--ease); }
.ic.on { transform: rotate(-15deg); }

/* 手机上仍要够得着：44 是拇指下限，但顶栏只有 54 高，所以用内边距撑而不是放大按钮 */
@media (max-width: 760px) {
  .tt { width: 38px; height: 38px; }
}
</style>
