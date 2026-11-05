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

const MAX_RETRY_WAIT_MS = 3000

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
    } catch {   }
    if (res.status === 429) {
      const seconds = Math.ceil(retryAfterMs(res.headers.get('Retry-After')) / 1000)
      message = `请求过于频繁，${seconds} 秒后再试`
    }
    throw new ApiError(res.status, message, code, requestId)
  }

  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

const PREFIX: Record<Scope, string> = {
  public: '/api/public/v1',
  admin: '/admin/api',
}

const urlOf = (scope: Scope, path: string) => PREFIX[scope] + path

function read<T>(scope: Scope, path: string): Promise<T> {
  const rule = ruleFor(scope, path)
  const url = urlOf(scope, path)
  if (!rule) return request<T>(url)
  return cachedGet<T>(`${scope}:${path}`, rule, () => request<T>(url))
}

async function write<T>(scope: Scope, path: string, init: RequestInit): Promise<T> {
  const result = await request<T>(urlOf(scope, path), init)
  invalidate(scope)
  return result
}

export const publicApi = {
  get: <T>(path: string) => read<T>('public', path),
  post: <T>(path: string, body?: unknown) =>
    write<T>('public', path, { method: 'POST', body: JSON.stringify(body ?? {}) }),
}

export const adminApi = {
  get: <T>(path: string) => read<T>('admin', path),
  post: <T>(path: string, body?: unknown) =>
    write<T>('admin', path, { method: 'POST', body: JSON.stringify(body ?? {}) }),
  put: <T>(path: string, body?: unknown) =>
    write<T>('admin', path, { method: 'PUT', body: JSON.stringify(body ?? {}) }),
  patch: <T>(path: string, body?: unknown) =>
    write<T>('admin', path, { method: 'PATCH', body: JSON.stringify(body ?? {}) }),
  del: <T>(path: string) => write<T>('admin', path, { method: 'DELETE' }),
  postText: <T>(path: string, text: string) =>
    write<T>('admin', path, {
      method: 'POST',
      headers: { 'content-type': 'text/plain;charset=UTF-8' },
      body: text,
    }),
}

export function describeError(e: unknown): string {
  if (e instanceof ApiError) return e.message
  if (e instanceof Error) return e.message
  return '未知错误'
}
