import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api, ApiError, E, SESSION_KEY } from '@/api/client'

/**
 * ⑥ 的 api 客户端。四件事必须钉住，因为它们每一个都对应一种"错了也不会有人发现"：
 * 凭证注入错了 → 页面能开但每发都是 40100；三码不分处 → 被吊销的会话会被前端悄悄重放；
 * 业务码不看 → 40000 的"允许区间"被吞成一句"保存失败"；HTTP 与业务码混谈 → 502 显示成"没数据"。
 */

function reply(body, status = 200, headers = {}) {
  return vi.spyOn(global, 'fetch').mockResolvedValue({
    status,
    headers: { get: (k) => headers[k] ?? null },
    json: async () => body,
  })
}

const ok = (data) => ({ code: 0, message: 'OK', data, success: true })
const ko = (code, message) => ({ code, message, data: null, success: false })

beforeEach(() => localStorage.clear())
afterEach(() => vi.restoreAllMocks())

describe('api client', () => {
  it('带上 admin token：Authorization 是网关唯一识别路径', async () => {
    localStorage.setItem(SESSION_KEY, 'T1')
    reply(ok({ ok: 1 }))
    await expect(api.get('/api/admin/auth/me')).resolves.toEqual({ ok: 1 })
    const [url, opt] = global.fetch.mock.calls[0]
    expect(url).toBe('/api/admin/auth/me')
    expect(opt.headers.Authorization).toBe('Bearer T1')
  })

  it('没登录时不发 Authorization 空头（空头会被判成凭证无效而不是匿名）', async () => {
    reply(ok({}))
    await api.get('/ui/ping')
    const headers = global.fetch.mock.calls[0][1].headers
    expect(headers.Authorization).toBeUndefined()
  })

  it('业务码非 0 抛 ApiError 并带上整份响应：40000 的 message 里写着允许区间', async () => {
    reply(ko(40000, '阈值必须在 [1, 200000] 之间'))
    const err = await api.put('/api/admin/config', {}).then(() => null, (e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.code).toBe(40000)
    expect(err.message).toContain('[1, 200000]')
    expect(err.payload.success).toBe(false)
  })

  it('三个 401 类码走三条路：过期可回原页、吊销不回原页、无凭证只清 token', async () => {
    const actions = []
    for (const code of [E.EXPIRED, E.REVOKED, E.UNAUTHORIZED]) {
      localStorage.setItem(SESSION_KEY, 'T')
      reply(ko(code, 'x'))
      await api.get('/x').catch((e) => actions.push(e.action))
      // 三种码都必须把 token 清掉：留着它只会让每一发重复同样的失败
      expect(localStorage.getItem(SESSION_KEY)).toBeNull()
    }
    expect(actions).toEqual(['login-redirect', 'login-strict', 'clear'])
  })

  it('被吊销的会话绝不自动重放：那一发可能已经落库了', async () => {
    const spy = reply(ko(E.REVOKED, '会话已作废'))
    localStorage.setItem(SESSION_KEY, 'T')
    await api.post('/api/admin/activities/A1/budget', {}).catch(() => {})
    expect(spy).toHaveBeenCalledTimes(1)
  })

  it('42900 把 Retry-After 带出来，界面才能显示"还有几秒可再试"', async () => {
    reply(ko(E.THROTTLED, '请求过于频繁'), 429, { 'Retry-After': '31' })
    const err = await api.get('/x').then(() => null, (e) => e)
    expect(err.code).toBe(42900)
    expect(err.retryAfterSeconds).toBe(31)
  })

  it('网关 502（响应体不是 JSON）报 HTTP 状态而不是"没数据"', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue({
      status: 502,
      headers: { get: () => null },
      json: async () => { throw new Error('not json') },
    })
    const err = await api.get('/x').then(() => null, (e) => e)
    expect(err.code).toBe(50200)
    expect(err.message).toContain('502')
  })
})
