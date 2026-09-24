import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import CacheView from '@/views/CacheView.vue'
import UsersView from '@/views/UsersView.vue'
import { useSession } from '@/stores/session'
import { SESSION_KEY } from '@/api/client'

/**
 * ③ 的重预热回执页。这里唯一不能含糊的是 <b>DISPATCHED</b>：
 * 它既不是成功也不是失败，而是"已经交给 owning 服务，回执还没回来"。
 * 把它显示成任何一边都会让人在没生效的时候以为生效了（或反过来去重试一次真写）。
 */

function mock(...bodies) {
  let i = 0
  return vi.spyOn(global, 'fetch').mockImplementation(async () => {
    const b = bodies[Math.min(i, bodies.length - 1)]
    i += 1
    return {
      status: b.success ? 200 : 400,
      headers: { get: () => null },
      json: async () => b,
    }
  })
}
const ok = (data) => ({ code: 0, message: 'OK', data, success: true })
const ko = (code, message) => ({ code, message, data: null, success: false })

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.setItem(SESSION_KEY, 'T')
  useSession().me = { role: 'admin' }
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('CacheView', () => {
  it('LITE 同步分支：DONE 就带着 before/after 与公式说明回来', async () => {
    mock(ok(['budget']), ok({ status: 'DONE', type: 'budget', key: 'ACT1', before: 1, after: 2 }))
    const w = mount(CacheView)
    await flushPromises()
    await w.get('[data-field="key"]').setValue('ACT1')
    await w.get('[data-act="reheat"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('已完成')
    expect(w.text()).toContain('2')
    expect(w.find('[data-act="reheat"]').attributes('disabled')).toBeUndefined()
  })

  it('FULL 分进程：DISPATCHED 显示"已投递、等回执"，轮到 DONE 才改口', async () => {
    mock(
      ok(['budget']),
      ok({ status: 'DISPATCHED', type: 'budget', key: 'ACT1', id: 'r-1', before: -1, after: -1 }),
      ok({ status: 'DISPATCHED', type: 'budget', key: 'ACT1', id: 'r-1', before: -1, after: -1 }),
      ok({ status: 'DONE', type: 'budget', key: 'ACT1', id: 'r-1', before: 1, after: 5100 }),
    )
    vi.useFakeTimers({ shouldAdvanceTime: false })
    const w = mount(CacheView)
    await flushPromises()
    await w.get('[data-field="key"]').setValue('ACT1')
    await w.get('[data-act="reheat"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('已投递')
    expect(w.text()).not.toContain('已完成')
    vi.advanceTimersByTime(4500)
    await flushPromises()
    expect(w.text()).toContain('5100')
    expect(w.text()).toContain('已完成')
    const urls = global.fetch.mock.calls.map((c) => c[0])
    expect(urls.some((u) => u.includes('/cache/reheat/ack?type=budget&id=r-1'))).toBe(true)
  })

  it('轮询超时不许静默：给出"仍待回执"与手动再查一次', async () => {
    mock(
      ok(['budget']),
      ok({ status: 'DISPATCHED', type: 'budget', key: 'ACT1', id: 'r-2', before: -1, after: -1 }),
      ok({ status: 'DISPATCHED', type: 'budget', key: 'ACT1', id: 'r-2', before: -1, after: -1 }),
    )
    vi.useFakeTimers({ shouldAdvanceTime: false })
    const w = mount(CacheView)
    await flushPromises()
    await w.get('[data-field="key"]').setValue('ACT1')
    await w.get('[data-act="reheat"]').trigger('click')
    await flushPromises()
    vi.advanceTimersByTime(31_000)
    await flushPromises()
    expect(w.text()).toContain('仍待回执')
    expect(w.find('[data-act="poll-again"]').exists()).toBe(true)
  })

  it('41010 说清"这一档没有认领该 type 的进程"，不是"请求失败"', async () => {
    mock(
      ok(['budget']),
      ko(41010, '本形态没有注册 budget 的重预热消费者，且集群里没有该 type 的消费组'),
    )
    const w = mount(CacheView)
    await flushPromises()
    await w.get('[data-field="key"]').setValue('ACT1')
    await w.get('[data-act="reheat"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('没有')
    expect(w.text()).toContain('budget')
    expect(w.text()).not.toContain('已完成')
  })
})

describe('UsersView', () => {
  it('停用账号只给 admin，且文案说清"停用即作废其全部会话"', async () => {
    const rows = {
      records: [{ id: 2, username: 'viewer', displayName: '只读', role: 'viewer', status: 'ACTIVE', locked: false }],
      total: 1,
      page: 1,
      size: 20,
    }
    mock(ok(rows))
    const w = mount(UsersView)
    await flushPromises()
    expect(w.text()).toContain('viewer')
    await w.get('[data-act="disable"]').trigger('click')
    await flushPromises()
    // 停用是破坏性动作：先要看见"会连带作废会话"这句话，再确认才会发请求
    expect(w.text()).toContain('会话')
    await w.get('[data-act="confirm"]').trigger('click')
    await flushPromises()
    const call = global.fetch.mock.calls.filter((c) => c[1].method === 'PUT').at(-1)
    expect(call[0]).toBe('/api/admin/users/2/status?status=INACTIVE')
  })

  it('operator 看不到可点的停用按钮（写动作在后端也会 40300）', async () => {
    useSession().me = { role: 'operator' }
    mock(ok({ records: [], total: 0, page: 1, size: 20 }))
    const w = mount(UsersView)
    await flushPromises()
    expect(w.find('[data-act="disable"]').exists()).toBe(false)
  })
})
