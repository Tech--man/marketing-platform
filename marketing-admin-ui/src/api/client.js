export const SESSION_KEY = 'mkt.admin.token'

/** 与 marketing-common 的 ErrorCode 对齐（前端只需要这几个会改变界面动作的码） */
export const E = {
  BAD_REQUEST: 40000,
  UNAUTHORIZED: 40100,
  EXPIRED: 40101,
  REVOKED: 40102,
  FORBIDDEN: 40300,
  NOT_FOUND: 40400,
  THROTTLED: 42900,
  BUDGET: 41000,
  STATE: 41001,
  CONFLICT: 41008,
  NOT_BROADCAST: 41009,
  NOT_APPLICABLE: 41010,
}

export class ApiError extends Error {
  constructor(code, message, payload, action, retryAfterSeconds) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.payload = payload
    this.action = action
    this.retryAfterSeconds = retryAfterSeconds
  }
}

/**
 * 鉴权失败的三个码必须走三条路，不能并成一条：
 * - 40101 过期：这次操作没做过，跳登录并记住原页面，登录后回得去；
 * - 40102 已吊销：**那一发可能已经落库了**（别人刚把你的会话踢掉，而你正在保存），
 *   所以既不自动重放也不带 next，回原页会让人以为"再点一次就好"而写第二遍；
 * - 40100 无凭证/验签失败：本地那枚 token 根本不可信，清掉就够。
 */
function authAction(code) {
  if (code === E.EXPIRED) return 'login-redirect'
  if (code === E.REVOKED) return 'login-strict'
  if (code === E.UNAUTHORIZED) return 'clear'
  return null
}

async function request(method, path, body) {
  const headers = { 'Content-Type': 'application/json' }
  const token = localStorage.getItem(SESSION_KEY)
  // 没登录时**不发**空头：空头会被网关判成"凭证无效"，把"你还没登录"说成"你的凭证是假的"
  if (token) headers.Authorization = `Bearer ${token}`
  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  let payload = null
  try {
    payload = await res.json()
  } catch {
    // 网关 502/空响应体：不能当成"没数据"，那是另一种病
  }
  if (payload && payload.success && payload.code === 0) return payload.data

  const code = payload ? payload.code : res.status * 100
  const action = authAction(code)
  if (action) localStorage.removeItem(SESSION_KEY)
  const retryAfter = Number(res.headers && res.headers.get('Retry-After'))
  return Promise.reject(
    new ApiError(
      code,
      (payload && payload.message) || `HTTP ${res.status}`,
      payload,
      action,
      Number.isFinite(retryAfter) ? retryAfter : undefined,
    ),
  )
}

export const api = {
  get: (p) => request('GET', p),
  post: (p, b) => request('POST', p, b ?? {}),
  put: (p, b) => request('PUT', p, b ?? {}),
  del: (p) => request('DELETE', p),
}
