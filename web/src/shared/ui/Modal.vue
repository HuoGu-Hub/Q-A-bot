<script setup lang="ts">
/**
 * 居中弹窗。
 *
 * 项目里原先只有术语页那个「抽屉」（Teleport 到 body 的侧栏），需要真正的弹窗时
 * 没有共享件 —— 各页自己写遮罩必然会写出三套略有差异的样式。这个就是那一件。
 *
 * 与抽屉保持同一套约定：
 *   - **Teleport 到 body**：固定定位的祖先只要带 transform / overflow，弹窗会被裁在面板里
 *   - `role="dialog"` + `aria-modal`，Esc 关闭，点遮罩关闭
 *   - 打开时把焦点移进弹窗，关闭时还给原来那个元素（键盘用户不会掉回页面顶部）
 *   - 打开期间锁 body 滚动
 *
 * 内容长度由调用方决定，超出高度时**只有内容区滚动**，标题栏与关闭按钮始终在。
 */
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  open: boolean
  /** 标题栏文字；空则只显示关闭按钮 */
  title?: string
  /** 弹窗宽度，默认 560px（窄屏自动收成 100%） */
  width?: string
}>(), { title: '', width: '560px' })

const emit = defineEmits<{ close: [] }>()

const box = ref<HTMLElement | null>(null)
/** 打开前焦点在哪，关闭后还回去 */
let lastActive: HTMLElement | null = null

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.stopPropagation()
    emit('close')
  }
}

function release() {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKey)
}

watch(() => props.open, async (open) => {
  if (open) {
    lastActive = document.activeElement instanceof HTMLElement ? document.activeElement : null
    document.body.style.overflow = 'hidden'
    window.addEventListener('keydown', onKey)
    await nextTick()
    box.value?.focus()
  } else {
    release()
    lastActive?.focus?.()
  }
})

// 组件被卸载时 el 已经没了，但监听器和 body 样式还挂着 —— 必须一起收掉
onBeforeUnmount(release)
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="layer">
      <!-- 遮罩：点它等于关闭。放在弹窗下面（DOM 顺序在前），不挡弹窗自己的点击 -->
      <div class="mask" @click="emit('close')" />
      <div
        ref="box"
        class="box"
        role="dialog"
        aria-modal="true"
        :aria-label="title || undefined"
        tabindex="-1"
        :style="{ width }"
      >
        <header class="head">
          <span class="title">{{ title }}</span>
          <button type="button" class="x" aria-label="关闭" @click="emit('close')">✕</button>
        </header>
        <div class="body">
          <slot />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.layer {
  position: fixed;
  inset: 0;
  z-index: var(--z-drawer);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-4);
}
.mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, .5);
  backdrop-filter: blur(1px);
}
/* 弹窗本体：抬到 --surface-raised（--surface-panel 与页面底几乎同色，会糊在一起），
   靠 shadow-lift 说明它比页面高一层，不再多描一圈重边。 */
.box {
  position: relative;
  max-width: 100%;
  max-height: min(82vh, 720px);
  display: flex;
  flex-direction: column;
  background: var(--surface-raised);
  border: 1px solid var(--edge);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-lift);
  outline: none;
}
.head {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  border-bottom: 1px solid var(--hairline);
}
.title { font-size: var(--fs-section); font-weight: var(--fw-semi); }
.x {
  flex: none;
  margin-left: auto;
  background: none;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  color: var(--ink-dim);
  font-size: var(--fs-sm);
  line-height: 1;
  padding: 4px 7px;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease);
}
.x:hover { background: var(--bg-raised); border-color: var(--edge-hover); color: var(--ink); }
.body { padding: var(--sp-4); overflow: auto; }
</style>
