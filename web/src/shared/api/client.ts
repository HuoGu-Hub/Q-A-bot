/**
 * API 客户端。
 *
 * 两端共用，但**严格分开**：
 *   publicApi  → /api/public/**   （公开只读，不带凭据）
 *   adminApi   → /admin/api/**    （带 cookie，401 时跳登录）
 *
 * 分开的意义不只是路径好看：公开站**永远不该**碰到管理接口，
 * 这样即使前端出 bug，也不会误调带权限的接口。
 */

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(url, {
      credentials: 'same-origin',
      headers: { 'content-type': 'application/json' },
      ...init,
    })
  } catch {
    throw new ApiError(0, '无法连接服务端')
  }

  if (!res.ok) {
    let message = `HTTP ${res.status}`
    try {
      const body = await res.json()
      if (body?.error) message = body.error
    } catch { /* 非 JSON 响应，用默认消息 */ }
    throw new ApiError(res.status, message)
  }

  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

/**
 * 公开接口：不带凭据，不跟随管理会话。
 *
 * post 用于**投票**和（后续的）降级请求 —— 都是"写入"，但不需要登录。
 * 服务端靠 IP 限流 + clientId 防误刷，不依赖会话。
 */
export const publicApi = {
  get: <T>(path: string) => request<T>(`/api/public${path}`),
  post: <T>(path: string, body?: unknown) =>
    request<T>(`/api/public${path}`, { method: 'POST', body: JSON.stringify(body ?? {}) }),
}

/** 管理接口：401 交给调用方处理（通常会跳登录页） */
export const adminApi = {
  get: <T>(path: string) => request<T>(`/admin/api${path}`),
  post: <T>(path: string, body?: unknown) =>
    request<T>(`/admin/api${path}`, { method: 'POST', body: JSON.stringify(body ?? {}) }),
}

/** 把后端抛的错误转成人话 */
export function describeError(e: unknown): string {
  if (e instanceof ApiError) return e.message
  if (e instanceof Error) return e.message
  return '未知错误'
}
