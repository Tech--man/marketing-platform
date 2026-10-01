# 扩展点与目录结构

> 本文拆自 README 的对应章节（2026-10-01 拆分）。文中"见第 X 节"的互引仍按原 README 编号：一/二节与三节速览在 README；三节详解在 docs/deployment.md；四节在 docs/core-flows.md；五节在 docs/api.md；六节在 docs/testing.md；七/八节在 docs/structure.md。

## 七、扩展点（占位 → 生产的升级路径）

| 占位 | 生产替换 |
|---|---|
| `AllowAllRiskCheckService` | 风控中心 RPC + 设备指纹 + 黑名单布隆过滤器（接口不变） |
| 网关演示 Token 鉴权 | OAuth2/JWT 网关鉴权 + 用户维度限流键 |
| `@Scheduled` 超时回补/消息补偿 | 已做多实例去重（Redis 周期租约，见 `RedisLeaseLock`）；按 user_id 分片仍是 XXL-Job / SchedulerX 的事 |
| 本地消息表最终一致 | 强一致场景接 Seata AT（订单/预算服务已按 bizKey 幂等设计） |
| volatile 规则快照 | 规模增长后演进 ES 标签检索 / 多维索引 |
| MQ 削峰计数 | Flink 实时 ROI 大盘 + ClickHouse 明细 |
| Nacos 默认关闭（local 静态路由） | `--spring.profiles.active=nacos` 一键开启注册发现与配置中心 |

## 八、目录结构

```
marketing-platform/
├── pom.xml                     # 父 POM（版本矩阵统一管理；两份前端不进 reactor）
├── marketing-common/           # 幂等/本地消息/Lua/Result/异常 + 两套签名 token（自动装配）
├── marketing-open-api/         # 风控/分销/ROI 领域接口 + 占位实现
├── marketing-gateway/          # 8090 路由 / C 端与后台两套鉴权 / 限流
├── marketing-activity/         # 8081 状态机/预算/灰度
├── marketing-coupon/           # 8082 券中心
├── marketing-discount/         # 8083 优惠计算引擎
├── marketing-seckill/          # 8084 秒杀中心
├── marketing-account/          # 8087 消费者账号：注册/登录/刷新/登出/改密/会话/身份事件
├── marketing-admin/            # 8086 后台：账号/会话/审计/运维入口 + 两份静态产物 /ui /h5
├── marketing-standalone/       # 8085 LITE 与 dev 的聚合进程（四业务 + 后台 + 账号同 JVM）
├── marketing-admin-ui/         # ⑥ 后台 SPA 源码（Vue3 + vite，不进 maven 生命周期）
├── marketing-h5-ui/            # C 端 H5 源码（同一套产物纪律：dist 入仓到 static/h5）
├── docker/
│   ├── docker-compose.data.yml     # 常驻数据层（mysql + redis AOF），三套形态共用
│   ├── docker-compose.preview.yml  # LITE 服役档全栈（standalone + gateway 两容器）
│   ├── docker-compose.prod.yml     # FULL 中间件：RocketMQ/Nacos/Prometheus
│   ├── docker-compose.full-app.yml # FULL 应用侧：一容器一服务，可 --scale
│   ├── mysql/init-lite/            # 单库 DDL + 种子（数据层默认，自动执行）
│   ├── mysql/init/                 # 六库布局（配合 MYSQL_DB_PER_SERVICE=1）
│   ├── mysql/migrate/              # 现存卷的增量迁移（有守卫，重复执行 no-op）
│   ├── rocketmq/                   # 两套 broker conf：进程形态广播 127.0.0.1，容器形态广播 host.docker.internal
│   └── prometheus/prometheus.yml
└── scripts/
    ├── common.sh               # 公共前置：JDK 17 锁定 + 健康等待 + 端口归属自检
    ├── start-dev.sh / stop-dev.sh             # dev 开发档（2 个本机 JVM）
    ├── deploy-preview.sh / stop-preview.sh    # LITE 服役档（全栈容器）
    ├── start-all.sh / stop-all.sh             # FULL 扩容档·本机进程（7 个 JVM，含 account）
    ├── deploy-full.sh                         # FULL 扩容档·容器化（可 --scale 多副本）
    ├── smoke-test.sh                          # 九条链路端到端冒烟（三套形态通用）
    ├── load-probe.sh                          # 容量夹具：按业务落库数算端到端，不看队列长度
    ├── reset-demo-data.sh                     # 演示容量复位
    └── build-ui.sh / check-ui-dist.sh         # ⑥ 后台产物的唯一重建入口 + jar/仓库 sha256 对拍
        build-h5.sh / check-h5-dist.sh         # C 端 H5 同一条纪律的两个脚本
```

种子数据：活动 `ACT2026001`、券模板 `CT2026001`(5元无门槛)/`CT2026002`(满100减20)、
秒杀 `SK2026001`(200 件/16 桶)、规则 `PR2026001~003`(满减/折扣/阶梯)。
