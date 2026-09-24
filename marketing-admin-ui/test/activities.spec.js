import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ActivitiesView from '@/views/ActivitiesView.vue'
import { useSession } from '@/stores/session'
import { SESSION_KEY } from '@/api/client'

/**
 * 活动页。三件事只有前端能负责：乐观锁的 version 必须原样带回去、
 * 撞号（41008）要给出路而不是当"保存失败"、状态机的按钮按当前状态裁剪
 * 但**非法流转的后端 message 仍要原样显示**（裁剪是省事，不是校验）。
 */

function reply(...bodies) {
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

const draft = {
  activityNo: 'ACT-A',
  name: '秋季大促',
  status: 'DRAFT',
  budgetAmount: 1000,
  usedAmount: 0,
  grayPercent: null,
  grayWhitelist: null,
  version: 4,
  startTime: '2026-09-01T00:00:00',
  endTime: '2026-09-30T00:00:00',
}
const online = { ...draft, activityNo: 'ACT-B', status: 'ONLINE', version: 9, grayPercent: 100 }
const page = (records) => ({ records, total: records.length, page: 1, size: 20 })

const mountView = () => mount(ActivitiesView)
const bodyOf = (call) => JSON.parse(call[1].body)
const urlOf = (call) => call[0]
/**
 * 保存成功后页面会自己重读列表，所以"最后一发"是 GET 而不是那一发 PUT。
 * 取 PUT 要按 method 找——不然断言会打在错误的请求上，红得让人去查错的代码。
 */
const putCall = () => global.fetch.mock.calls.filter((c) => c[1].method === 'PUT').at(-1)

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.setItem(SESSION_KEY, 'T')
  useSession().me = { role: 'admin' }
})
afterEach(() => vi.restoreAllMocks())

describe('ActivitiesView', () => {
  it('改预算必须把 version 原样带回（不带 = 放弃乐观锁，后写覆盖先写）', async () => {
    reply(ok(page([draft])), ok({ ...draft, budgetAmount: 1001, version: 5 }))
    const w = mountView()
    await flushPromises()
    await w.get('[data-act="edit-budget"]').trigger('click')
    await w.get('[data-field="budgetAmount"]').setValue('1001')
    await w.get('[data-act="save-budget"]').trigger('click')
    await flushPromises()
    const call = putCall()
    expect(urlOf(call)).toBe('/api/admin/activities/ACT-A/budget')
    expect(bodyOf(call).version).toBe(4)
    expect(bodyOf(call).budgetAmount).toBe(1001)
  })

  it('改灰度也一样带 version，并把"等下一次回源"写明白（它不刷缓存）', async () => {
    reply(ok(page([draft])), ok({ ...draft, grayPercent: 5, version: 5 }))
    const w = mountView()
    await flushPromises()
    await w.get('[data-act="edit-gray"]').trigger('click')
    await w.get('[data-field="grayPercent"]').setValue('5')
    await w.get('[data-act="save-gray"]').trigger('click')
    await flushPromises()
    const call = putCall()
    expect(urlOf(call)).toBe('/api/admin/activities/ACT-A/gray')
    expect(bodyOf(call).version).toBe(4)
    expect(w.text()).toMatch(/回源|5s|秒/)
  })

  it('41008 撞号：给 ConflictBar 和"重新加载"，不能只弹 toast 让人盲改', async () => {
    reply(ok(page([draft])), ko(41008, '版本冲突：他人已更新该活动'))
    const w = mountView()
    await flushPromises()
    await w.get('[data-act="edit-budget"]').trigger('click')
    await w.get('[data-field="budgetAmount"]').setValue('7')
    await w.get('[data-act="save-budget"]').trigger('click')
    await flushPromises()
    const bar = w.get('[data-testid="conflict-bar"]')
    expect(bar.text()).toContain('他人已更新')
    expect(w.find('[data-act="reload-conflict"]').exists()).toBe(true)
    // 表单不能被清空：人要比对"我刚才填的"和"库里现在的"
    expect(w.get('[data-field="budgetAmount"]').element.value).toBe('7')
    await w.get('[data-act="reload-conflict"]').trigger('click')
    await flushPromises()
    expect(global.fetch.mock.calls.at(-1)[0]).toContain('/api/admin/activities?page=')
  })

  it('状态机按钮按当前状态裁剪，但后端的 41001 原文仍然要显示', async () => {
    reply(ok(page([draft, online])), ko(41001, 'DRAFT 不能直接到 ONLINE'))
    const w = mountView()
    await flushPromises()
    const rows = w.findAll('[data-row]')
    expect(rows).toHaveLength(2)
    const draftEvents = rows[0].findAll('[data-event]').map((b) => b.attributes('data-event'))
    const onlineEvents = rows[1].findAll('[data-event]').map((b) => b.attributes('data-event'))
    expect(draftEvents).toEqual(['SUBMIT'])
    expect(onlineEvents).toEqual(expect.arrayContaining(['OFFLINE', 'FINISH']))
    expect(onlineEvents).not.toContain('SUBMIT')
    await rows[0].get('[data-event="SUBMIT"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('DRAFT 不能直接到 ONLINE')
  })

  it('非 ONLINE 之外改预算也要写清"预算改动会同事务重预热预扣缓存"', async () => {
    reply(ok(page([draft])))
    const w = mountView()
    await flushPromises()
    await w.get('[data-act="edit-budget"]').trigger('click')
    expect(w.text()).toContain('重预热')
  })
})
