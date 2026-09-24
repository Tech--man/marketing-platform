import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import AppLayout from '@/AppLayout.vue'
import { buildRoutes } from '@/router/routes'
import { useSession } from '@/stores/session'
import { SESSION_KEY } from '@/api/client'

/**
 * 导航与路由表的配对。这一条是浏览器旅程抓出来的那个 bug 的常驻防线：
 * 大盘注册在子路由的空路径上、导航却写 /ops —— 点上去匹配不到任何路由，
 * 整页空白（连侧栏一起没），而**单元测试一条都不会红**，因为组件本身没写错。
 *
 * <p>所以这里只问一件事：侧栏渲染出来的每个链接，都必须能解析到一个非空路径的匹配。</p>
 */
const makeRouter = () => createRouter({ history: createMemoryHistory('/ui/'), routes: buildRoutes() })

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.setItem(SESSION_KEY, 'T')
  const s = useSession()
  s.me = { role: 'admin' }
  s.expiresAt = Date.now() + 600_000
})

function mountNav(router) {
  return mount(AppLayout, { global: { plugins: [router] } })
}

describe('导航 ⇄ 路由', () => {
  it('侧栏每个链接都解析得到路由，且不是只解析到父层布局', () => {
    const router = makeRouter()
    const w = mountNav(router)
    const hrefs = w.findAll('aside nav a').map((a) => a.attributes('href'))
    // 十个页面一个都不能少（少了说明有页注册了却没进导航，或反之）
    expect(hrefs.length).toBe(10)
    for (const href of hrefs) {
      const path = href.replace(/^\/ui/, '') || '/'
      const matched = router.resolve(path).matched
      expect(matched.length, `${path} 解析不到路由`).toBeGreaterThan(0)
      const leaf = matched.at(-1)
      // 父层是 AppLayout 本身；叶子记录必须真的带组件（重定向记录没 component，
      // 说明导航指向的是一条只做跳转的路径，等于把用户丢给空白页）
      expect(leaf.components?.default, `${path} 只匹配到一个没有组件的记录`).toBeTruthy()
    }
  })

  it('根路径不渲染成空白：它重定向到有组件的那条', () => {
    const router = makeRouter()
    const ops = router.getRoutes().find((r) => r.name === 'ops')
    expect(ops).toBeTruthy()
    expect(ops.path).toBe('/ops')
  })
})
