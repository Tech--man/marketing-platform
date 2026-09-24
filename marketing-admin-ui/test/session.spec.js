import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useSession } from '@/stores/session'
import { SESSION_KEY } from '@/api/client'

/**
 * 会话状态。TTL 必须来自登录响应而不是前端常量：AdminProperties 是 900s，
 * 前端自己写死一个数就会在改配置后出现"界面说还有 5 分钟，后端已经拒了"。
 */
const EXPIRES_KEY = 'mkt.admin.expiresAt'

function mockFetch(...bodies) {
  let i = 0
  return vi.spyOn(global, 'fetch').mockImplementation(async () => {
    const b = bodies[Math.min(i, bodies.length - 1)]
    i += 1
    return { status: 200, headers: { get: () => null }, json: async () => b }
  })
}
const ok = (data) => ({ code: 0, message: 'OK', data, success: true })

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
})
afterEach(() => vi.restoreAllMocks())

describe('session store', () => {
  it('登录把 token 与到期时刻都落到 localStorage（刷新页面不该重新登录）', async () => {
    mockFetch(ok({ token: 'T9', expiresInSeconds: 900, role: 'admin' }))
    const s = useSession()
    await s.login('admin', 'pw')
    expect(localStorage.getItem(SESSION_KEY)).toBe('T9')
    expect(Number(localStorage.getItem(EXPIRES_KEY))).toBeGreaterThan(Date.now())
    expect(s.authed).toBe(true)
  })

  it('倒计时用后端给的秒数，不自己发明 TTL', () => {
    const s = useSession()
    s.expiresAt = Date.now() + 300_000
    expect(s.secondsLeft()).toBeGreaterThanOrEqual(299)
    expect(s.secondsLeft()).toBeLessThanOrEqual(300)
  })

  it('角色决定按钮可用性：operator 能重预热但改不动阈值', () => {
    const s = useSession()
    s.me = { role: 'operator' }
    expect(s.canOperate).toBe(true)
    expect(s.canWrite).toBe(false)
    s.me = { role: 'viewer' }
    expect(s.canOperate).toBe(false)
    expect(s.canWrite).toBe(false)
    s.me = { role: 'admin' }
    expect(s.canWrite).toBe(true)
  })

  it('退出时后端调用失败也必须清本地：留着 token 只会让下一发继续 40102', async () => {
    mockFetch({ code: 50000, message: 'boom', data: null, success: false })
    const s = useSession()
    s.token = 'T'
    await expect(s.logout()).rejects.toBeDefined()
    expect(localStorage.getItem(SESSION_KEY)).toBeNull()
    expect(s.authed).toBe(false)
  })
})
