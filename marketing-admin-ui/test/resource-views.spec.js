import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import CouponsView from '@/views/CouponsView.vue'
import RulesView from '@/views/RulesView.vue'
import SeckillView from '@/views/SeckillView.vue'
import { useSession } from '@/stores/session'
import { SESSION_KEY } from '@/api/client'

/**
 * 券模板 / 优惠规则 / 秒杀活动三页。它们**看着像同构、端点形状其实各不相同**
 * （`/api/admin/coupon/templates`、`/api/admin/discount/rules`、
 * `/api/admin/seckill/activities`），所以这里逐页断言真实 URL 与 body，
 * 而不是抽一个"约定式拼装"——约定式拼错的代价是点保存打到别的进程上。
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
const page = (records) => ({ records, total: records.length, page: 1, size: 20 })
const calls = () => global.fetch.mock.calls
const putBody = () => JSON.parse(calls().filter((c) => c[1].method === 'PUT').at(-1)[1].body)
const putUrl = () => calls().filter((c) => c[1].method === 'PUT').at(-1)[0]

const template = {
  templateNo: 'CT2026001',
  activityNo: 'ACT2026001',
  name: '满 100 减 20',
  couponType: 'FULL_REDUCE',
  faceValue: 20,
  thresholdAmount: 100,
  totalStock: 100,
  perUserLimit: 1,
  validDays: 7,
  status: 'ACTIVE',
  version: 3,
}
const rule = {
  ruleNo: 'R-FULL-1',
  name: '满 100 减 20',
  activityNo: 'ACT2026001',
  ruleType: 'FULL_REDUCE',
  mutexGroup: 'G1',
  priority: 10,
  status: 'ENABLED',
  version: 2,
  ruleJson: '{"threshold":100,"discountValue":20}',
}
const seckill = (status) => ({
  activityNo: 'SK2026001',
  itemId: 1001,
  itemName: '示例商品',
  seckillPrice: 9.9,
  totalStock: 5000,
  soldStock: 12,
  buckets: 16,
  status,
  version: 5,
})

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.setItem(SESSION_KEY, 'T')
  useSession().me = { role: 'admin' }
})
afterEach(() => vi.restoreAllMocks())

describe('CouponsView', () => {
  it('改库存打到自己的端点并带 version；低于已发数被 40000 拒时不清空表单', async () => {
    reply(ok(page([template])), ko(40000, '库存不能低于已发放数量 80'))
    const w = mount(CouponsView)
    await flushPromises()
    expect(calls()[0][0]).toBe('/api/admin/coupon/templates?page=1&size=20')
    await w.get('[data-act="edit-stock"]').trigger('click')
    await w.get('[data-field="totalStock"]').setValue('10')
    await w.get('[data-act="save"]').trigger('click')
    await flushPromises()
    expect(putUrl()).toBe('/api/admin/coupon/templates/CT2026001/stock')
    expect(putBody()).toEqual({ totalStock: 10, version: 3 })
    expect(w.text()).toContain('已发放数量 80')
    expect(w.get('[data-field="totalStock"]').element.value).toBe('10')
  })

  it('上下线是一条独立的 PUT，status 只认 ACTIVE/INACTIVE', async () => {
    reply(ok(page([template])), ok({ ...template, status: 'INACTIVE' }))
    const w = mount(CouponsView)
    await flushPromises()
    await w.get('[data-act="toggle-status"]').trigger('click')
    await flushPromises()
    await w.get('[data-act="confirm-toggle"]').trigger('click') // W4：先落确认抽屉
    await flushPromises()
    expect(putUrl()).toBe('/api/admin/coupon/templates/CT2026001/status')
    expect(putBody()).toEqual({ status: 'INACTIVE', version: 3 })
  })
})

describe('RulesView', () => {
  it('新建与启停是同一条 upsert（POST /rules），不是两个端点', async () => {
    reply(ok(page([rule])), ok({ ...rule, status: 'DISABLED' }))
    const w = mount(RulesView)
    await flushPromises()
    await w.get('[data-act="toggle-status"]').trigger('click')
    await flushPromises()
    const post = calls().filter((c) => c[1].method === 'POST').at(-1)
    expect(post[0]).toBe('/api/admin/discount/rules')
    expect(JSON.parse(post[1].body).status).toBe('DISABLED')
    expect(JSON.parse(post[1].body).ruleNo).toBe('R-FULL-1')
  })

  it('P1：启停带行上 version（不带的话后端 VersionGuard 恒 41008，规则改不动）', async () => {
    reply(ok(page([rule])), ok({ ...rule, status: 'DISABLED' }))
    const w = mount(RulesView)
    await flushPromises()
    await w.get('[data-act="toggle-status"]').trigger('click')
    await flushPromises()
    const body = JSON.parse(calls().filter((c) => c[1].method === 'POST').at(-1)[1].body)
    expect(body.version).toBe(2)
  })

  it('编辑已有规则时把 ruleJson 一起带回去（upsert 是整条覆盖，不带就等于把 DSL 清空）', async () => {
    reply(ok(page([rule])), ok(rule))
    const w = mount(RulesView)
    await flushPromises()
    await w.get('[data-act="edit"]').trigger('click')
    await w.get('[data-field="priority"]').setValue('20')
    await w.get('[data-act="save"]').trigger('click')
    await flushPromises()
    const body = JSON.parse(calls().filter((c) => c[1].method === 'POST').at(-1)[1].body)
    expect(body.priority).toBe(20)
    expect(body.threshold).toBe(100)
    expect(body.discountValue).toBe(20)
  })

  it('P1：编辑保存同样带行上 version（fixture 的 version=2）', async () => {
    reply(ok(page([rule])), ok(rule))
    const w = mount(RulesView)
    await flushPromises()
    await w.get('[data-act="edit"]').trigger('click')
    await w.get('[data-act="save"]').trigger('click')
    await flushPromises()
    const body = JSON.parse(calls().filter((c) => c[1].method === 'POST').at(-1)[1].body)
    expect(body.version).toBe(2)
  })
})

describe('SeckillView', () => {
  it('非 ONLINE 改库存必须写"不会重建分桶"，ONLINE 则要写"同事务按 total-sold 重建"', async () => {
    reply(ok(page([seckill('OFFLINE')])))
    const w = mount(SeckillView)
    await flushPromises()
    await w.get('[data-act="edit-stock"]').trigger('click')
    expect(w.text()).toContain('不会重建分桶')
    expect(w.text()).not.toContain('同事务')
  })

  it('ONLINE 的改库存打到自己的端点并带 version', async () => {
    reply(ok(page([seckill('ONLINE')])), ok(seckill('ONLINE')))
    const w = mount(SeckillView)
    await flushPromises()
    await w.get('[data-act="edit-stock"]').trigger('click')
    expect(w.text()).toContain('同事务')
    await w.get('[data-field="totalStock"]').setValue('6000')
    await w.get('[data-act="save"]').trigger('click')
    await flushPromises()
    expect(putUrl()).toBe('/api/admin/seckill/activities/SK2026001/stock')
    expect(putBody()).toEqual({ totalStock: 6000, version: 5 })
  })

  it('上下线只认 ONLINE/OFFLINE，且开闸是独立动作', async () => {
    reply(ok(page([seckill('OFFLINE')])), ok(seckill('ONLINE')))
    const w = mount(SeckillView)
    await flushPromises()
    await w.get('[data-act="toggle-status"]').trigger('click')
    await flushPromises()
    await w.get('[data-act="confirm-toggle"]').trigger('click') // W4：先落确认抽屉
    await flushPromises()
    expect(putUrl()).toBe('/api/admin/seckill/activities/SK2026001/status')
    expect(putBody()).toEqual({ status: 'ONLINE', version: 5 })
  })
})
