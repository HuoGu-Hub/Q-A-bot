<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { CarouselPublicItem } from '@shared/api/types'
import Icon from '@shared/ui/Icon.vue'

const props = withDefaults(defineProps<{
  items: CarouselPublicItem[]
  intervalMs?: number
  maxHeight?: string
}>(), { intervalMs: 4000, maxHeight: 'min(46vh, 440px)' })

const MIN_INTERVAL_MS = 1000
const FALLBACK_RATIO = 16 / 9

const root = ref<HTMLElement | null>(null)
const idx = ref(0)
const runSeq = ref(0)

const rev = ref(false)

const dirLock = ref(false)

const interval = computed(() => (props.intervalMs > 0 ? Math.max(MIN_INTERVAL_MS, props.intervalMs) : 0))

const wipeMs = computed(() => Math.min(1000, Math.max(420, Math.round(interval.value * 0.3))))
const count = computed(() => props.items.length)
const multi = computed(() => count.value > 1)

const frameRatio = computed(() => {
  const it = props.items[0]
  if (!it || !it.width || !it.height) return FALLBACK_RATIO
  return it.width / it.height
})

const altOf = (it: CarouselPublicItem) => it.caption || '站点配图'

const IMAGE_WIDTHS = [640, 1280, 1920]

const AMBIENT_WIDTH = 320

function sized(it: CarouselPublicItem, w: number) {
  const url = it.imageUrl
  return url + (url.includes('?') ? '&' : '?') + 'w=' + w
}

function srcsetOf(it: CarouselPublicItem) {
  return IMAGE_WIDTHS.map((w) => sized(it, w) + ' ' + w + 'w').join(', ')
}

const hovered = ref(false)
const onScreen = ref(true)
const userPaused = ref(false)
const pageVisible = ref(true)
const reduceMotion = ref(false)

const loaded = ref<Set<string>>(new Set())

function idOf(it: CarouselPublicItem): string {
  return String(it.id)
}

const wanted = computed(() => {
  const n = count.value
  const s = new Set<string>()
  if (n <= 0) return s
  const add = (i: number) => {
    const it = props.items[(i + n) % n]
    if (it) s.add(idOf(it))
  }
  add(idx.value)
  if (n > 1) {
    add(idx.value + 1)
    add(idx.value - 1)
  }
  return s
})

const arrived = ref<Set<string>>(new Set())

function markArrived(it: CarouselPublicItem) {
  const id = idOf(it)
  if (arrived.value.has(id)) return
  arrived.value = new Set(arrived.value).add(id)
}

const nextReady = computed(() => {
  const n = count.value
  if (n <= 1) return true
  const next = props.items[(idx.value + 1) % n]
  return !next || arrived.value.has(idOf(next))
})

const auto = computed(() => multi.value && interval.value > 0)

const canAuto = computed(() =>
  auto.value && !userPaused.value && !hovered.value && onScreen.value && pageVisible.value
  && nextReady.value)

let timer: number | undefined
let media: MediaQueryList | null = null
let io: IntersectionObserver | null = null

let remaining = 0
let runStart = 0

function stopTimer() {
  if (timer !== undefined) {
    window.clearTimeout(timer)
    timer = undefined
  }
}

function restartTimer() {
  stopTimer()
  remaining = interval.value
  if (!canAuto.value) return
  runStart = Date.now()
  timer = window.setTimeout(tick, remaining)
}

function freezeTimer() {
  if (timer === undefined) return
  stopTimer()
  remaining = Math.max(0, remaining - (Date.now() - runStart))
}

function thawTimer() {
  if (!canAuto.value || timer !== undefined) return
  if (remaining <= 0) {
    remaining = interval.value
    runSeq.value++
  }
  runStart = Date.now()
  timer = window.setTimeout(tick, remaining)
}

function tick() {
  stopTimer()
  remaining = 0
  setIndex(idx.value + 1, false)
}

function setIndex(i: number, backward: boolean) {
  if (count.value <= 0) return
  const next = ((i % count.value) + count.value) % count.value

  if (backward !== rev.value) {
    rev.value = backward
    dirLock.value = true
    nextTick(() => {
      requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          idx.value = next
          runSeq.value++
          restartTimer()
          dirLock.value = false
        })
      })
    })
    return
  }

  idx.value = next
  runSeq.value++
  restartTimer()
}

function jump(i: number) {
  setIndex(i, i < idx.value)
}

function shift(delta: number) {
  setIndex(idx.value + delta, delta < 0)
}

function onReduceChange(e: MediaQueryListEvent) {
  reduceMotion.value = e.matches
}
function onVisibilityChange() {
  pageVisible.value = !document.hidden
}

watch(canAuto, (on) => { if (on) thawTimer(); else freezeTimer() })
watch(count, () => { if (idx.value >= count.value) idx.value = 0 })
watch(root, attachObserver)

watch(() => props.items.map(idOf).join('|'), () => {
  const alive = new Set(props.items.map(idOf))
  const prune = (s: Set<string>) => new Set([...s].filter((id) => alive.has(id)))
  loaded.value = prune(loaded.value)
  arrived.value = prune(arrived.value)
})

onMounted(() => {
  media = window.matchMedia('(prefers-reduced-motion: reduce)')
  reduceMotion.value = media.matches
  media.addEventListener('change', onReduceChange)

  pageVisible.value = !document.hidden
  document.addEventListener('visibilitychange', onVisibilityChange)

  attachObserver()
  restartTimer()
})

watch(wanted, (w) => {
  if (!w.size) return
  const next = new Set(loaded.value)
  let changed = false
  for (const id of w) {
    if (!next.has(id)) { next.add(id); changed = true }
  }
  if (changed) loaded.value = next
}, { immediate: true })

watch(nextReady, (ok) => { if (ok && canAuto.value) thawTimer() })

function attachObserver() {
  io?.disconnect()
  io = null
  if (typeof IntersectionObserver === 'undefined') return
  const el = root.value
  if (!el) return
  io = new IntersectionObserver(
    (entries) => { onScreen.value = entries[0]?.isIntersecting ?? true },
    { threshold: 0.2 },
  )
  io.observe(el)
}

onBeforeUnmount(() => {
  stopTimer()
  io?.disconnect()
  io = null
  media?.removeEventListener('change', onReduceChange)
  media = null
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <figure
    v-if="count > 0"
    ref="root"
    class="carousel"
    :class="{ 'no-anim': reduceMotion, paused: !canAuto, rev, 'dir-lock': dirLock }"
    :style="{ '--wipe': wipeMs + 'ms' }"
    role="region"
    aria-label="站点配图"
  >
    <div
      class="stage"
      :style="{ '--ratio': frameRatio, '--max-h': maxHeight }"
      @mouseenter="hovered = true"
      @mouseleave="hovered = false"
    >
      <div class="ambient" aria-hidden="true">
        <span
          v-for="(it, i) in items"
          :key="'halo-' + it.id"
          class="halo"
          :class="{ on: i === idx }"
          :style="loaded.has(idOf(it)) ? { backgroundImage: 'url(' + sized(it, AMBIENT_WIDTH) + ')' } : undefined"
        />
      </div>

      <div class="frame" :style="{ aspectRatio: frameRatio }">
        <component
          :is="it.link ? 'a' : 'div'"
          v-for="(it, i) in items"
          :key="it.id"
          class="slide"
          :class="{ on: i === idx }"
          :href="it.link || undefined"
          :target="it.link ? '_blank' : undefined"
          :rel="it.link ? 'noopener' : undefined"
          :aria-hidden="i !== idx"
          :tabindex="i === idx || !it.link ? undefined : -1"
        >
          <img
            class="img"
            :src="loaded.has(idOf(it)) ? sized(it, 1280) : undefined"
            :srcset="loaded.has(idOf(it)) ? srcsetOf(it) : undefined"
            sizes="(max-width: 1000px) 92vw, 600px"
            :alt="altOf(it)"
            :width="it.width || undefined"
            :height="it.height || undefined"
            :loading="i === idx ? 'eager' : 'lazy'"
            :fetchpriority="i === idx ? 'high' : 'low'"
            decoding="async"
            @load="markArrived(it)"
            @error="markArrived(it)"
          >
          <span v-if="it.caption" class="cap">{{ it.caption }}</span>
        </component>

        <span class="sheen" aria-hidden="true" />
      </div>

      <template v-if="multi">
        <button type="button" class="nav prev" aria-label="上一张" @click="shift(-1)">‹</button>
        <button type="button" class="nav next" aria-label="下一张" @click="shift(1)">›</button>
      </template>

      <button
        v-if="auto"
        type="button"
        class="toggle"
        :aria-pressed="userPaused"
        :aria-label="userPaused ? '继续自动播放' : '暂停自动播放'"
        :title="userPaused ? '继续自动播放' : '暂停自动播放'"
        @click="userPaused = !userPaused"
      >
        <Icon :name="userPaused ? 'play' : 'pause'" :size="15" />
      </button>
    </div>

    <div v-if="multi" class="dots">
      <button
        v-for="(it, i) in items"
        :key="it.id"
        type="button"
        class="dot"
        :class="{ on: i === idx }"
        data-hit="24x28"
        :aria-label="'第 ' + (i + 1) + ' 张'"
        :aria-current="i === idx ? 'true' : undefined"
        @click="jump(i)"
      >
        <span
          v-if="i === idx && auto"
          :key="'fill-' + idx + '-' + runSeq"
          class="fill"
          aria-hidden="true"
          :style="{ '--dot-dur': interval + 'ms' }"
        />
      </button>
    </div>
  </figure>
</template>

<style scoped>
.carousel {
  --wipe: 1000ms;
  --ease-wipe: cubic-bezier(.4, .2, .6, .8);
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

.stage {
  position: relative;
  z-index: 1;
  width: min(100%, calc(var(--max-h, 60vh) * var(--ratio, 1.7778)));
  margin-inline: auto;
}

.ambient {
  position: absolute;
  inset: -8% 0;
  z-index: -1;
  pointer-events: none;
}
.halo {
  position: absolute;
  inset: 0;
  border-radius: 24px;
  background-position: center;
  background-size: cover;
  filter: blur(48px) saturate(1.4);
  opacity: 0;
  transform: scale(.97);
  transition: opacity 420ms var(--ease), transform var(--wipe) var(--ease);
}
.halo.on {
  opacity: .42;
  transform: scale(1.04);
  transition: opacity var(--wipe) var(--ease), transform var(--wipe) var(--ease);
}

.frame {
  position: relative;
  overflow: hidden;
  border-radius: var(--r-lg);
  border: 1px solid rgba(255, 255, 255, .10);
  background: rgba(8, 9, 12, .50);
  backdrop-filter: blur(22px) saturate(1.2);
  -webkit-backdrop-filter: blur(22px) saturate(1.2);
  box-shadow:
    0 28px 64px -20px rgba(0, 0, 0, .80),
    0 0 0 1px rgba(0, 0, 0, .35),
    inset 0 1px 0 rgba(255, 255, 255, .12);
}

.sheen {
  position: absolute;
  inset: 0;
  z-index: 2;
  pointer-events: none;
  border-radius: inherit;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, .12) 0%, rgba(255, 255, 255, 0) 20%),
    linear-gradient(0deg, rgba(0, 0, 0, .32) 0%, rgba(0, 0, 0, 0) 26%);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, .05);
}

.slide {
  position: absolute;
  inset: 0;
  display: block;
  opacity: 0;
  -webkit-mask-image: linear-gradient(270deg, #000 0 45%, rgba(0, 0, 0, 0) 58% 100%);
  mask-image: linear-gradient(270deg, #000 0 45%, rgba(0, 0, 0, 0) 58% 100%);
  -webkit-mask-size: 260% 100%;
  mask-size: 260% 100%;
  -webkit-mask-repeat: no-repeat;
  mask-repeat: no-repeat;
  -webkit-mask-position: 0% 0;
  mask-position: 0% 0;
  pointer-events: none;
  will-change: opacity, mask-position;
  transition: opacity 0s var(--wipe),
              -webkit-mask-position 0s var(--wipe),
              mask-position 0s var(--wipe);
}
.slide.on {
  opacity: 1;
  z-index: 1;
  -webkit-mask-position: 100% 0;
  mask-position: 100% 0;
  pointer-events: auto;
  transition: opacity 0s,
              -webkit-mask-position var(--wipe) var(--ease-wipe),
              mask-position var(--wipe) var(--ease-wipe);
}

.carousel.rev .slide {
  -webkit-mask-image: linear-gradient(90deg, #000 0 45%, rgba(0, 0, 0, 0) 58% 100%);
  mask-image: linear-gradient(90deg, #000 0 45%, rgba(0, 0, 0, 0) 58% 100%);
  -webkit-mask-position: 100% 0;
  mask-position: 100% 0;
}
.carousel.rev .slide.on {
  -webkit-mask-position: 0% 0;
  mask-position: 0% 0;
}

.carousel.dir-lock .slide { transition: none; }

.img { width: 100%; height: 100%; object-fit: contain; display: block; }

@keyframes img-settle {
  from { transform: scale(1.035); }
  to { transform: scale(1); }
}
.slide.on .img { animation: img-settle 1250ms var(--ease) both; }

.no-anim .slide {
  -webkit-mask-image: none;
  mask-image: none;
  transition: opacity 0s var(--wipe);
}
.no-anim .slide.on { transition: opacity calc(var(--wipe) * .7) var(--ease-wipe); }
.no-anim .img { animation: none; }
.no-anim .halo { transform: none; transition: opacity calc(var(--wipe) * .7) var(--ease-wipe); }

.cap {
  position: absolute;
  left: 50%;
  bottom: var(--sp-4);
  transform: translateX(-50%);
  max-width: calc(100% - var(--sp-6));
  padding: 6px var(--sp-4);
  font-size: var(--fs-sm);
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  background: rgba(12, 14, 18, .55);
  border: 1px solid rgba(255, 255, 255, .12);
  border-radius: var(--r-pill);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  pointer-events: none;
}

.toggle {
  position: absolute;
  top: var(--sp-3);
  right: var(--sp-3);
  z-index: 3;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  padding: 0;
  font: inherit;
  color: var(--ink);
  background: rgba(12, 14, 18, .48);
  border: 1px solid rgba(255, 255, 255, .14);
  border-radius: var(--r-pill);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  cursor: pointer;
  opacity: .5;
  transition: opacity var(--dur) var(--ease), background var(--dur-fast) var(--ease),
              border-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.stage:hover .toggle, .toggle:focus-visible { opacity: 1; }
.toggle:hover {
  background: rgba(242, 171, 85, .20);
  border-color: var(--ember);
  color: var(--ember-hot);
}

.nav {
  position: absolute;
  top: 50%;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  font: inherit;
  font-size: var(--fs-xl);
  line-height: 1;
  color: var(--ink);
  background: rgba(12, 14, 18, .42);
  border: 1px solid rgba(255, 255, 255, .14);
  border-radius: var(--r-pill);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  cursor: pointer;
  opacity: 0;
  transform: translateY(-50%) scale(.92);
  transition: opacity var(--dur) var(--ease), transform var(--dur) var(--ease),
              background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease),
              color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
}
.prev { left: var(--sp-4); }
.next { right: var(--sp-4); }
.stage:hover .nav, .nav:focus-visible { opacity: 1; transform: translateY(-50%) scale(1); }
.nav:hover {
  background: rgba(232, 160, 76, .18);
  border-color: var(--flame);
  color: var(--flame-bright);
  box-shadow: 0 0 18px var(--flame-glow);
}
.nav:active { transform: translateY(-50%) scale(.94); }

.dots {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--sp-3);
}
.dot {
  position: relative;
  width: 8px;
  height: 8px;
  padding: 0;
  border-radius: var(--r-pill);
  border: 1px solid var(--line-strong);
  background: var(--line-strong);
  overflow: hidden;
  cursor: pointer;
  transition: width var(--dur) var(--ease), background var(--dur-fast) var(--ease),
              border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
}
.dot::after { content: ''; position: absolute; inset: -10px -8px; }
.dot:hover { border-color: var(--flame); background: var(--flame-veil); }
.dot.on {
  width: 34px;
  border-color: rgba(255, 255, 255, .16);
  background: rgba(255, 255, 255, .10);
  box-shadow: 0 0 12px var(--flame-glow);
}
.fill {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--flame-deep), var(--flame-bright));
  transform-origin: left center;
  transform: scaleX(0);
  animation-name: dotfill;
  animation-duration: var(--dot-dur, 4000ms) !important;
  animation-timing-function: linear;
  animation-fill-mode: forwards;
}
@keyframes dotfill { from { transform: scaleX(0); } to { transform: scaleX(1); } }
.paused .fill { animation-play-state: paused; }

@media (hover: none) {
  .nav { opacity: .95; transform: translateY(-50%) scale(1); }
  .nav { width: 44px; height: 44px; }
  .toggle { opacity: .95; }
}

@media (max-width: 640px) {
  .nav { width: 40px; height: 40px; font-size: var(--fs-lg); }
  .prev { left: var(--sp-2); }
  .next { right: var(--sp-2); }
  .cap { font-size: var(--fs-xs); padding: 4px var(--sp-3); bottom: var(--sp-2); }
  .halo { filter: blur(26px) saturate(1.3); border-radius: 14px; }
  .dot.on { width: 26px; }
  .ambient { inset: -7% 0; }
}
</style>
