/**
 * 读取缓存 —— 切页不再重新拉一遍数据。
 *
 * <p><b>为什么建在 API 客户端这一层</b>：视图是「进页面 → onMounted → load()」的模子，
 * 把缓存写进三十个视图既改不完也说不清策略；写在 client 里，所有页面同时受益，
 * TTL 表也只有一个文件可审。
 *
 * <p><b>只缓存白名单</b>：见 client.ts 的规则表。默认不缓存，新增接口不会意外被缓存住。
 * 身份、日志、轮询进度、分页明细一律不进缓存 —— 它们「刚发生过什么」比「少发一次请求」重要。
 *
 * <p><b>同一次停留只吃一次缓存</b>：每条缓存在一次页面访问里最多被读一次。
 * 于是「切回来」命中缓存，「点刷新」拿到新数据 —— 不需要给每个面板加 fresh 参数。
 * 页面访问的边界由 `beginVisit()` 标记，两个入口的 router.afterEach 各调一次。
 *
 * <p><b>错误不缓存</b>：请求失败只抛错，不写入；旧值保留，下次仍会重试。
 */
import type { CacheRule } from './rules'

/** 缓存实体。at = 写入时刻，servedAt = 最近一次被读取时所在的页面访问序号 */
interface Entry {
  data: unknown
  at: number
  servedAt: number
}

const store = new Map<string, Entry>()
/** 同一路径的并发请求合并成一个，避免连点或双面板同时挂载时发两次 */
const inflight = new Map<string, Promise<unknown>>()

/**
 * 条目上限。地图点位单个就是几百 KB，长时间浏览不该把它们全堆在内存里。
 * 超了就丢最旧的一条 —— 这一层是"切回来还在"，不是"永久保留"。
 */
const MAX_ENTRIES = 120

function put(key: string, data: unknown): void {
  store.set(key, { data, at: Date.now(), servedAt: visit })
  if (store.size <= MAX_ENTRIES) return
  let oldestKey: string | undefined
  let oldestAt = Number.POSITIVE_INFINITY
  for (const [k, entry] of store) {
    if (entry.at < oldestAt) { oldestAt = entry.at; oldestKey = k }
  }
  if (oldestKey !== undefined) store.delete(oldestKey)
}

/**
 * 页面访问序号。每次路由变化加一。
 *
 * <p>它区分的是「切到这一页」和「在这一页里又调了一次同一个接口」：
 * 前者该吃缓存，后者是用户按了刷新。
 */
let visit = 0

/** 标记一次新的页面访问，由 router.afterEach 调用 */
export function beginVisit(): void {
  visit += 1
}

/**
 * 取数据：新鲜且本次停留没读过 → 直接给；否则发请求。
 *
 * @param key     缓存键，形如 `public:/stats`
 * @param rule    过期时间
 * @param fetcher 真正发请求的函数
 */
export function cachedGet<T>(
  key: string,
  rule: CacheRule,
  fetcher: () => Promise<T>,
): Promise<T> {
  const hit = store.get(key)
  if (hit && hit.servedAt !== visit && Date.now() - hit.at < rule.ttl * 1000) {
    hit.servedAt = visit
    return Promise.resolve(hit.data as T)
  }

  const running = inflight.get(key)
  if (running) return running as Promise<T>

  const task = fetcher()
    .then((data) => {
      put(key, data)
      return data
    })
    .finally(() => { inflight.delete(key) })

  inflight.set(key, task)
  return task
}

/**
 * 清空某一端的读取缓存。所有写操作成功后调用。
 *
 * <p>粒度故意做粗：一次保存后多取一次数据，代价远小于「改了没生效」的困惑。
 * 登录与退出也会走到这里 —— 换账号后不该看到上一个人的数据。
 */
export function invalidate(scope: 'public' | 'admin'): void {
  const prefix = scope + ':'
  for (const key of store.keys()) {
    if (key.startsWith(prefix)) store.delete(key)
  }
}
