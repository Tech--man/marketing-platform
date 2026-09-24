import AppLayout from '@/AppLayout.vue'
import LoginView from '@/views/LoginView.vue'
import OpsView from '@/views/OpsView.vue'
import ConfigView from '@/views/ConfigView.vue'
import ActivitiesView from '@/views/ActivitiesView.vue'
import CouponsView from '@/views/CouponsView.vue'
import RulesView from '@/views/RulesView.vue'
import SeckillView from '@/views/SeckillView.vue'
import CacheView from '@/views/CacheView.vue'
import UsersView from '@/views/UsersView.vue'
import SessionsView from '@/views/SessionsView.vue'
import AuditsView from '@/views/AuditsView.vue'

/**
 * 路由表（⑥ 的页面清单见 spec §6）。做成函数是为了**测试能建一个独立实例**：
 * 导航与路由的配对回归（test/routes.spec.js）需要在没有浏览器历史的环境里
 * `resolve()` 每个侧栏链接，共用单例就做不到这一点。
 *
 * <p>基座用 history，所以 {@code /ui/audits} 直接刷新也要能开——回退由 admin 侧的
 * {@code UiWebMvcConfig} 负责，网关只按 {@code /ui/**} 转发，不懂前端路由。</p>
 */
export function buildRoutes() {
  return [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    {
      path: '/',
      component: AppLayout,
      children: [
        // 大盘必须有自己的一条真实路径：曾经它只注册在空路径上而导航写的是 /ops，
        // 点「运维大盘」匹配不到任何路由 → 整页空白、连侧栏一起没了（浏览器旅程抓到）。
        { path: '', redirect: { name: 'ops' } },
        { path: 'ops', name: 'ops', component: OpsView },
        { path: 'config', name: 'config', component: ConfigView },
        { path: 'activities', name: 'activities', component: ActivitiesView },
        { path: 'coupons', name: 'coupons', component: CouponsView },
        { path: 'rules', name: 'rules', component: RulesView },
        { path: 'seckill', name: 'seckill', component: SeckillView },
        {
          path: 'cache',
          name: 'cache',
          component: CacheView,
          meta: { roles: ['admin', 'operator'] },
        },
        { path: 'users', name: 'users', component: UsersView },
        { path: 'sessions', name: 'sessions', component: SessionsView },
        { path: 'audits', name: 'audits', component: AuditsView },
      ],
    },
  ]
}
