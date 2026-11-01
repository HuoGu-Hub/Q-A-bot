/**
 * API 客户端。
 *
 * 两端共用，但**严格分开**：
 *   publicApi  → /api/public/v1/**   （公开只读，不带凭据）
 *   adminApi   → /admin/api/**    （带 cookie，401 时跳登录）
 *
 * 分开的意义不只是路径好看：公开站**永远不该**碰到管理接口，
 * 这样即使前端出 bug，也不会误调带权限的接口。
 *
 * ── 读取缓存 ──
 * GET 走缓存层（见 cache.ts 与 rules.ts），切页回看不再重新拉一遍数据；
 * 写操作成功后整端失效，保证"改完就能看到"。身份、日志、进度类不登记，
 * 默认每次请求 —— 白名单之外的东西行为不变。
 */

import { cachedGet, invalidate } from './cache'
import { ruleFor, type Scope } from './rules'

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public code?: string,
    public requestId?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/**
 * 429 最多等这么久再重试一次。超过就交给页面提示"稍后再试"。
 *
 * <p>为什么只等一次、且有个上限：限流窗口通常是几十秒，等到位会把页面挂死；
 * 而一次短暂的抖动（连点、双面板同时挂载）等一秒就能过去，值回票价。
 */
const MAX_RETRY_WAIT_MS = 3000

/** 解析 Retry-After：既可能是秒数，也可能是 HTTP 日期 */
function retryAfterMs(header: string | null): number {
  if (!header) return 1000
  const seconds = Number(header)
  if (Number.isFinite(seconds)) return Math.max(0, seconds * 1000)
  const at = Date.parse(header)
  return Number.isNaN(at) ? 1000 : Math.max(0, at - Date.now())
}

const wait = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

async function send(url: string, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(url, {
      credentials: 'same-origin',
      headers: { 'content-type': 'application/json' },
      ...init,
    })
  } catch {
    throw new ApiError(0, '无法连接服务端')
  }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  let res = await send(url, init)

  if (res.status === 429) {
    const ms = retryAfterMs(res.headers.get('Retry-After'))
    if (ms <= MAX_RETRY_WAIT_MS) {
      await wait(ms)
      res = await send(url, init)
    }
  }

  if (!res.ok) {
    let message = `HTTP ${res.status}`
    let code: string | undefined
    let requestId = res.headers.get('X-Request-Id') ?? undefined
    try {
      const body = await res.json()
      message = body?.detail ?? body?.message ?? body?.error ?? message
      code = body?.code ?? undefined
      requestId = body?.requestId ?? requestId
    } catch { /* 非 JSON 响应，用默认消息 */ }
    if (res.status === 429) {
      const seconds = Math.ceil(retryAfterMs(res.headers.get('Retry-After')) / 1000)
      message = `请求过于频繁，${seconds} 秒后再试`
    }
    throw new ApiError(res.status, message, code, requestId)
  }

  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

/** 两端的路径前缀。缓存键、失效前缀都按这个分组 */
const PREFIX: Record<Scope, string> = {
  public: '/api/public/v1',
  admin: '/admin/api',
}

const urlOf = (scope: Scope, path: string) => PREFIX[scope] + path

/**
 * 读接口。白名单命中的走缓存，其余原样请求。
 *
 * <p>缓存键带上查询串：`?map=Embervale` 与 `?map=Other` 是两份数据，
 * 而规则表只按路径匹配，所以换图不会串。
 */
function read<T>(scope: Scope, path: string): Promise<T> {
  const rule = ruleFor(scope, path)
  const url = urlOf(scope, path)
  if (!rule) return request<T>(url)
  return cachedGet<T>(`${scope}:${path}`, rule, () => request<T>(url))
}

/**
 * 写接口。成功后清空该端读取缓存 —— 看不到自己刚保存的结果是最难解释的 bug。
 */
async function write<T>(scope: Scope, path: string, init: RequestInit): Promise<T> {
  const result = await request<T>(urlOf(scope, path), init)
  invalidate(scope)
  return result
}

/**
 * 公开接口：不带凭据，不跟随管理会话。
 *
 * post 用于**投票**和（后续的）降级请求 —— 都是"写入"，但不需要登录。
 * 服务端靠 IP 限流 + clientId 防误刷，不依赖会话。
 */
export const publicApi = {
  get: <T>(path: string) => read<T>('public', path),
  post: <T>(path: string, body?: unknown) =>
    write<T>('public', path, { method: 'POST', body: JSON.stringify(body ?? {}) }),
}

/**
 * 管理接口：401 交给调用方处理（通常会跳登录页）。
 *
 * put / del 是地图页加进来的：点位要能改与删，只用 post 表达不了。
 * 保持同一套错误处理，所以走的还是上面那个 request。
 */
export const adminApi = {
  get: <T>(path: string) => read<T>('admin', path),
  post: <T>(path: string, body?: unknown) =>
    write<T>('admin', path, { method: 'POST', body: JSON.stringify(body ?? {}) }),
  put: <T>(path: string, body?: unknown) =>
    write<T>('admin', path, { method: 'PUT', body: JSON.stringify(body ?? {}) }),
  patch: <T>(path: string, body?: unknown) =>
    write<T>('admin', path, { method: 'PATCH', body: JSON.stringify(body ?? {}) }),
  del: <T>(path: string) => write<T>('admin', path, { method: 'DELETE' }),
  /** 聊天文件导入走 text/plain，不能套 JSON 的 content-type */
  postText: <T>(path: string, text: string) =>
    write<T>('admin', path, {
      method: 'POST',
      headers: { 'content-type': 'text/plain;charset=UTF-8' },
      body: text,
    }),
}

/** 把后端抛的错误转成人话 */
export function describeError(e: unknown): string {
  if (e instanceof ApiError) return e.message
  if (e instanceof Error) return e.message
  return '未知错误'
}
