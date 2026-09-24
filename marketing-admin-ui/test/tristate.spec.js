import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import TriState from '@/components/TriState.vue'

/**
 * ④ 的纪律在界面上的投影：-1 是"判定不了"、applicable=false 是"本形态不适用"，
 * 两者都**不许**显示成 0。
 *
 * <p>这条看起来像样式测试，其实是数据正确性测试：大盘把"读不到"画成 0，
 * 运维就会在最该报警的时候看到一片绿——后端为这件事写了单测与冒烟断言，
 * 前端如果把它归一化回 0，等于把整条链路的努力在最后一步抹掉。</p>
 */
describe('TriState', () => {
  it('-1 显示"判定不了"，且屏幕上不出现一个可以被误读成零的数字', () => {
    const w = mount(TriState, { props: { value: -1, applicable: true } })
    expect(w.text()).toContain('判定不了')
    expect(w.text()).not.toMatch(/(^|[^\w.])-?\d/)
  })

  it('本形态不适用显示"不适用"，而不是空白', () => {
    const w = mount(TriState, { props: { value: -1, applicable: false } })
    expect(w.text()).toContain('不适用')
    expect(w.text()).not.toContain('判定不了')
  })

  it('真 0 就是 0：不许和"判定不了"共用一种画法', () => {
    const w = mount(TriState, { props: { value: 0, applicable: true } })
    expect(w.text()).toBe('0')
    expect(w.attributes('data-kind')).toBe('zero')
  })

  it('正数标出来（不符项/积压是要被看见的那一类）', () => {
    const w = mount(TriState, { props: { value: 3, applicable: true } })
    expect(w.text()).toBe('3')
    expect(w.attributes('data-kind')).toBe('bad')
  })

  it('带 note 时把原因显示出来，不让人对着一个"不适用"猜', () => {
    const w = mount(TriState, {
      props: { value: -1, applicable: false, note: '本形态走 RocketMQ，队列深度在 broker 里' },
    })
    expect(w.attributes('title')).toContain('RocketMQ')
  })
})
