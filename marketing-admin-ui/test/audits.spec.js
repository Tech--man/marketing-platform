import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AuditsView from '@/views/AuditsView.vue'
import { useSession } from '@/stores/session'
import { SESSION_KEY } from '@/api/client'

/**
 * 审计页。两件事必须钉住：
 * ① 查询参数名与后端逐字一致（改了名不会报错，只会**静默丢掉过滤条件**，
 *    然后人拿着一份"看起来查过"的全量结果下结论）；
 * ② `requestSummary` 原样显示——它已经在后端脱敏过（③ 做过两种形态、两种命名的盖字），
 *    前端再"解读一次"等于把脱敏绕过或把敏感字段猜出来。
 */

function mock(...bodies) {
  let i = 0
  return vi.spyOn(global, 'fetch').mockImplementation(async () => {
    const b = bodies[Math.min(i, bodies.length - 1)]
    i += 1
    return { status: 200, headers: { get: () => null }, json: async () => b }
  })
}
const ok = (data) => ({ code: 0, message: 'OK', data, success: true })
const row = {
  id: 901,
  actorId: 1,
  actorName: 'admin',
  role: 'admin',
  action: 'activity.transition',
  resourceType: 'activity',
  resourceId: 'ACT-SMOKE-1',
  method: 'POST',
  path: '/api/admin/activities/ACT-SMOKE-1/transition',
  requestSummary: 'event=SUBMIT, password=<REDACTED>',
  resultCode: 0,
  ip: '127.0.0.1',
  costMs: 12,
}

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.setItem(SESSION_KEY, 'T')
  useSession().me = { role: 'admin' }
})
afterEach(() => vi.restoreAllMocks())

describe('AuditsView', () => {
  it('四个过滤参数按后端的名字发出去，不做任何"顺手改名"', async () => {
    mock(ok({ records: [row], total: 1, page: 1, size: 20 }))
    const w = mount(AuditsView)
    await flushPromises()
    await w.get('[data-field="action"]').setValue('activity.transition')
    await w.get('[data-field="resourceId"]').setValue('ACT-SMOKE-1')
    await flushPromises()
    const url = global.fetch.mock.calls.at(-1)[0]
    expect(url).toContain('action=activity.transition')
    expect(url).toContain('resourceId=ACT-SMOKE-1')
    expect(url).not.toMatch(/resource_id|actionType|resource-id/)
  })

  it('显示 total，翻下一页只多发一发请求且带着 page=2', async () => {
    mock(ok({ records: [row], total: 41, page: 1, size: 20 }))
    const w = mount(AuditsView)
    await flushPromises()
    const before = global.fetch.mock.calls.length
    expect(w.text()).toContain('41')
    await w.get('[data-act="next"]').trigger('click')
    await flushPromises()
    expect(global.fetch.mock.calls.length).toBe(before + 1)
    expect(global.fetch.mock.calls.at(-1)[0]).toContain('page=2')
  })

  it('requestSummary 原样显示（脱敏是后端做的事，前端不再解读）', async () => {
    mock(ok({ records: [row], total: 1, page: 1, size: 20 }))
    const w = mount(AuditsView)
    await flushPromises()
    expect(w.text()).toContain('password=<REDACTED>')
  })
})
