# ⑥ 浏览器核对：运维大盘（LITE，2026-09-24 07:45）

截图口不可用（in-app browser 没有可见 surface：`NATIVE_BROWSER_VIEWPORT_UNAVAILABLE`），
所以这里留的是**结构化快照文本**与 curl 真值的逐项对照——对"界面说的与接口说的是同一件事"
这个问题，它比 PNG 更可 diff。

## 走通的路径

`http://127.0.0.1:8090/ui/` → 守卫跳 `/ui/login?next=/`（未登录）→ 填 admin/rootdev123 →
`POST /api/admin/auth/login` + `GET /auth/me` → 落到大盘，页脚显示 `admin · 剩余 872s`，
导航只列出已注册的路由（此刻只有「运维大盘」）。

## 逐项对照（页面 ← → `GET /api/admin/ops`）

| 项 | 页面 | curl 真值 |
|---|---|---|
| `mode` | `local+proxy` | `local+proxy` |
| `ownForm` | `LITE` | `LITE` |
| targets | `marketing-gateway proxy OK` / `self local OK` | 同（样本数随时间不同） |
| 网关抓取地址 | `http://mkt-preview-gateway:8090/actuator/prometheus` | 同（④ 修正 #14 的容器别名） |
| Stream 通道 | 6 条全在，len/PEL = 0 | 6 条 `applicable=true` |
| **缓存与账** | `coupon-stock 1` / `budget 0` / `seckill-stock 0` | `coupon-stock 1` … 逐字一致 |
| 进程存活 | 四个业务模块 + admin = **不适用**，gateway/standalone = 在跑 | `null` × 5，`true` × 2 |
| 定时任务持锁 | `coupon-expire 有 54s`、`seckill-timeout 有 1s`、`local-message-retry 有 5s` | 同 |
| notes | 4 条原文列出（含"-1 一律表示判定不了，不等于 0"） | 同 |
| 审计量 | 940 行，top actions 8 条 | 同 |

## 这一趟真正证明的事

`coupon-stock = 1` **不是测试造出来的**：它是这台机器上跑过五形态来回之后，
Redis 里留下的真实漂移（④ 上线当天就抓到过两条同类）。大盘在一片 0 里显出这个 1，
而页面上没有任何按钮能"顺手把它改回 0"——④ 只读、修要去「缓存重预热」页（T9 落地），
这条边界在界面上也守住了。
