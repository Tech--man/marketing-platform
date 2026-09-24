import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ConfigView from '@/views/ConfigView.vue'
import { SESSION_KEY } from '@/api/client'
import { useSession } from '@/stores/session'

/**
 * ⑤ 的配置页。三条断言各自钉一种"错了也不会响"：
 * - 来源三态并成一种 → 运营看不出这个值是本档改的、全局改的还是出厂的，
 *   而 ⑤ 的全部形态隔离就建立在这句话上；
 * - 41009 当成成功 → "配置已落库但没广播"这个半状态被界面抹平；
 * - 40000 清空表单 → 允许区间就写在 message 里，清空等于让人凭记忆重填。
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

/** 与后端 ConfigOverviewView 逐字同形：entries 是**按 key 一条**，form 维度在 rows 里 */
const overview = {
  ownForm: 'LITE',
  appliedVersion: 0,
  entries: [
    {
      key: 'gateway.ratelimit.seckill-route.limit',
      service: 'marketing-gateway',
      type: 'INT',
      min: 1,
      max: 200000,
      defaultValue: '200',
      description: '秒杀路由每秒阈值',
      effectiveValue: '5',
      source: 'FORM',
      rows: [{ form: 'LITE', value: '5', version: '3', updatedBy: 'admin', remark: '压测' }],
    },
    {
      key: 'seckill.pay-timeout-seconds',
      service: 'marketing-seckill',
      type: 'INT',
      min: 30,
      max: 3600,
      defaultValue: '300',
      description: '支付超时秒数',
      effectiveValue: '300',
      source: 'GLOBAL',
      rows: [{ form: 'GLOBAL', value: '300', version: '1', updatedBy: 'admin', remark: '' }],
    },
    {
      key: 'discount.calc-timeout-ms',
      service: 'marketing-discount',
      type: 'INT',
      min: 5,
      max: 500,
      defaultValue: '50',
      description: '优惠计算超时',
      effectiveValue: '50',
      source: 'DEFAULT',
      rows: [],
    },
  ],
  orphans: [],
  unreportedServices: ['marketing-standalone'],
  degradedKeys: [],
}

const mountView = () => mount(ConfigView)

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.setItem(SESSION_KEY, 'T')
  // 写入口只对 admin 亮着：不设角色时点到的会是灰化按钮（那是 ③ 的角色矩阵在起作用，
  // 不是本段的被测对象）。非 admin 的那条另有断言，见最后一条。
  useSession().me = { role: 'admin' }
})
afterEach(() => vi.restoreAllMocks())

describe('ConfigView', () => {
  it('三种来源三种标签，谁都不许冒充谁', async () => {
    reply(ok(overview))
    const w = mountView()
    await flushPromises()
    expect(w.get('[data-src="FORM"]').text()).toContain('本档')
    expect(w.get('[data-src="GLOBAL"]').text()).toContain('全局')
    expect(w.get('[data-src="DEFAULT"]').text()).toContain('出厂')
    // 没覆盖的键也要出现（不是只列有行的），否则"改了没生效"与"压根没这个键"分不开
    expect(w.text()).toContain('discount.calc-timeout-ms')
  })

  it('越界值被 40000 拒时：message 原样显示且表单不清空', async () => {
    reply(ok(overview), ko(40000, '值必须在 [1, 200000] 之间，收到 999999999'))
    const w = mountView()
    await flushPromises()
    await w.get('[data-key="gateway.ratelimit.seckill-route.limit"] [data-act="edit"]').trigger('click')
    await w.get('[data-field="value"]').setValue('999999999')
    await w.get('[data-act="save"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('[1, 200000]')
    expect(w.get('[data-field="value"]').element.value).toBe('999999999')
  })

  it('41009（已落库未广播）不能显示成成功，且必须当场给重新广播的出口', async () => {
    // 第 4 发是重广播之后页面自己再 load 一次；不给一份合法的 overview，
    // 界面会在断言之前就先炸成空表，那时红的是错的地方。
    reply(ok(overview), ko(41009, '配置已落库但未广播'), ok({ version: 9 }), ok(overview))
    const w = mountView()
    await flushPromises()
    await w.get('[data-key="gateway.ratelimit.seckill-route.limit"] [data-act="edit"]').trigger('click')
    await w.get('[data-field="value"]').setValue('7')
    await w.get('[data-act="save"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('未广播')
    expect(w.text()).not.toContain('已写入并广播')
    expect(w.find('[data-act="rebroadcast"]').exists()).toBe(true)
    await w.get('[data-act="rebroadcast"]').trigger('click')
    await flushPromises()
    const urls = global.fetch.mock.calls.map((c) => c[0])
    expect(urls).toContain('/api/admin/config/rebroadcast')
    // 半状态消解之后，这个出口就该收起来（不是留在页面上让人重复点）
    expect(w.find('[data-act="rebroadcast"]').exists()).toBe(false)
  })

  it('删行的确认文案说清"退回出厂值"，不是"删成 0"', async () => {
    reply(ok(overview))
    const w = mountView()
    await flushPromises()
    await w.get('[data-key="gateway.ratelimit.seckill-route.limit"] [data-act="delete"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('出厂')
    expect(w.text()).not.toMatch(/删除成功/)
  })

  it('非 admin 角色看不到可点的写按钮（灰化不是安全边界，但至少不骗人）', async () => {
    useSession().me = { role: 'operator' }
    reply(ok(overview))
    const w = mountView()
    await flushPromises()
    const btn = w.get('[data-key="gateway.ratelimit.seckill-route.limit"] [data-act="edit"]')
    expect(btn.attributes('disabled')).toBeDefined()
    expect(btn.attributes('title')).toContain('admin')
    await btn.trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="editor"]').exists()).toBe(false)
  })

  it('form 下拉必须来自后端那一套（GLOBAL/LITE/FULL/DEV），并且总含本档', async () => {
    reply(ok({ ...overview, ownForm: 'DEV' }))
    const w = mountView()
    await flushPromises()
    await w.get('[data-key="gateway.ratelimit.seckill-route.limit"] [data-act="edit"]').trigger('click')
    const forms = w.findAll('[data-field="form"] option').map((o) => o.text())
    expect(forms).toEqual(expect.arrayContaining(['GLOBAL', 'LITE', 'FULL', 'DEV']))
    expect(w.get('[data-testid="own-form"]').text()).toContain('DEV')
  })
})
