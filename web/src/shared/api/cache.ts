import type { CacheRule } from './rules'

interface Entry {
  data: unknown
  at: number
  servedAt: number
}

const store = new Map<string, Entry>()
const inflight = new Map<string, Promise<unknown>>()

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

let visit = 0

export function beginVisit(): void {
  visit += 1
}

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

export function invalidate(scope: 'public' | 'admin'): void {
  const prefix = scope + ':'
  for (const key of store.keys()) {
    if (key.startsWith(prefix)) store.delete(key)
  }
}
