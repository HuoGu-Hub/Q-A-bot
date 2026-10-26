<script setup lang="ts">
/**
 * 开关。
 *
 * 抽它的原因：设置页和资料投递页各写了一套几乎相同的开关，
 * 而且都只在 input:checked 上做样式、**没有 hover 反馈** ——
 * 鼠标移上去不知道能不能点。
 *
 * v2：滑轨放大一圈（46×26），手机上好点；关态用"刻进去的槽"而不是一个描边框。
 */
import { ref } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    disabled?: boolean
    /** 无障碍标签；没有可见 label 时必须给 */
    ariaLabel?: string
  }>(),
  { disabled: false, ariaLabel: '开关' }
)
defineEmits<{ 'update:modelValue': [boolean] }>()

const el = ref<HTMLInputElement | null>(null)

/**
 * 滑块上的点击转发给真正的 checkbox。
 *
 * ⚠️ 根元素从 <label> 换成 <span>，是因为它常被塞进 Field —— 而 Field 的根元素
 * 也是 <label>，标签套标签是无效 HTML，还会让同一个控件挂上两个标签。
 * 换掉之后"点外观即切换"这件事得自己接：真正改变状态的仍然是原生 checkbox，
 * 所以键盘、读屏、表单语义一样都没丢。
 */
function onWrapClick(e: MouseEvent) {
  // 点在真正的 checkbox 上：停止冒泡即可，别 preventDefault，否则会取消原生切换
  if ((e.target as HTMLElement)?.tagName === 'INPUT') return
  // 点在滑块上：必须同时阻止冒泡和 label 的默认激活，再手动切换一次
  e.preventDefault()
  if (props.disabled) return
  el.value?.click()
}
</script>

<template>
  <!-- 阻止冒泡：Field 根元素是 label，点击滑块会触发 label 的默认切换；
       若再手动 input.click() 就会切换两次，看起来完全没反应。 -->
  <span class="switch" :class="{ disabled }" @click.stop="onWrapClick">
    <input
      ref="el"
      type="checkbox"
      :checked="modelValue"
      :disabled="disabled"
      :aria-label="ariaLabel"
      @change="$emit('update:modelValue', ($event.target as HTMLInputElement).checked)"
    />
    <span class="slider" />
  </span>
</template>

<style scoped>
.switch {
  position: relative;
  display: inline-block;
  width: 46px;
  height: 26px;
  cursor: pointer;
  /* 视觉高度 26，可点区域撑到 44 —— 不改变观感，但拇指点得中 */
  padding: 9px 0;
  margin: -9px 0;
  box-sizing: content-box;
}
.switch.disabled { cursor: not-allowed; opacity: .45; }
.switch input { opacity: 0; width: 0; height: 0; }

.slider {
  position: absolute;
  left: 0; right: 0;
  top: 50%;
  transform: translateY(-50%);
  height: 26px;
  background: var(--stone-void);
  border: 1px solid var(--edge);
  border-radius: var(--r-pill);
  box-shadow: var(--bevel-inset);
  transition: background var(--dur) var(--ease), border-color var(--dur) var(--ease),
              box-shadow var(--dur) var(--ease);
}
.slider::before {
  content: "";
  position: absolute;
  height: 18px; width: 18px; left: 3px; top: 3px;
  background: var(--ink-4);
  border-radius: 50%;
  transition: transform var(--dur) var(--ease), background var(--dur) var(--ease);
}

/* 悬浮反馈：能看出"可以点" */
.switch:hover input:not(:disabled) + .slider { border-color: var(--edge-hover); }
.switch input:checked + .slider {
  background: var(--ember-veil);
  border-color: var(--ember);
  box-shadow: var(--bevel-inset), 0 0 12px -2px var(--ember-glow);
}
.switch input:checked + .slider::before { transform: translateX(20px); background: var(--ember); }
.switch:hover input:checked:not(:disabled) + .slider { background: var(--ember-glow); }
.switch input:focus-visible + .slider { outline: 2px solid var(--ember); outline-offset: 3px; }
</style>
