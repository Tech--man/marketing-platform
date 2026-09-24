# ⑥ FULL 容器档界面实测（2026-09-24 18:11-18:24）

栈：`./scripts/deploy-full.sh`（**不带 `SKIP_BUILD=1`**，镜像含 ⑥ 的 dist）。六个业务容器全在，
索引页里的戳记 `build-ui: rev=1acedf0-dirty at=2026-09-24T09:53:08Z`，
资源 `assets/index-Q1ksYa6B.js` — 与仓库里那份 sha256 相同（`scripts/check-ui-dist.sh` exit 0）。

先跑了一遍冒烟：**96/96 全绿**（含链路 8 的六条），日志 `/tmp/smoke-full-container-180340.log`。
所以这一页只记"只有界面才看得见"的部分。

## 1. `/ui/` 走的是 `lb://marketing-admin` 这条路

`GET :8090/ui/` → 200、`Content-Type: text/html;charset=UTF-8`、`Cache-Control: no-store`。
这一档是 `ui-route` 六条声明里**唯一会走到 `lb://` 的那一条**（其余四档都是 `http://host:port` 直连），
所以 T3 那两套 profile 里 nacos 那套的实测只有这一格能给出。

## 2. 重预热页在 FULL 下**必须有类型清单**（5f149d9 的实测依据）

admin 自己的进程里一个重预热类型都没注册，接口层面直接可见：

```
GET /api/admin/cache/types → {"code":0,"data":[]}
```

修之前这一页在 FULL 是**空下拉 + 一句"本进程没有注册任何重预热类型"**，运维只能干看着。
现在界面渲染出来的清单（无障碍树读到的 `<select>` 选项）：

```
budget（由 marketing-activity 上报）
coupon-stock（由 marketing-coupon 上报）
seckill-stock（由 marketing-seckill 上报）
```

标签里的 target 来自 ④ 面板的 `consistency[].target` —— 这就是"两份来源并集"的样子：
类型名两边一致，但**只有面板那份在 FULL 有内容**。

## 3. `DISPATCHED → DONE` 的回执轮询（跨进程那条）

界面选 `seckill-stock` + key `SK2026001` → 点重预热，页面文本按时间采样：

| t(s) | 界面上出现的 |
|---|---|
| 0.0 | `DISPATCHED type=seckill-stock · key=SK2026001 · id=40 · 已投递给 owning 服务（force=true），用 id=40 查回执` |
| 0.6 / 1.2 | 同上（按钮文案"执行中…"） |
| 1.8 | **完成** |

约 2.4s 内从 `DISPATCHED` 走到 `DONE`，中间那两拍证明界面**没有**把"已投递"当成功、也没有当失败——
这正是 T9 那条判据（`DISPATCHED` 是三态里的第三态）在真进程上的样子。
旁证：`marketing_seckill_reheat_executed{type=seckill-stock}=1`（owning 进程真的执行了，不只有回执行）。

## 4. 一次真实的"发现 → 定位 → 修 → 复核"，全程在界面里

打开大盘时面板报 `coupon-stock 不符条数 = 1`（budget、seckill-stock 都是 0）。
这是**上一轮冒烟之后**留下的，不是本轮脚本自己造的（脚本的链路 7 收尾已回落到 0）。

界面去「券模板」页拿到候选键（只有两行：`CT2026001` 版本 2、`CT2026002` 版本 0），
回「缓存重预热」选 `coupon-stock` + key `CT2026001` 执行，回执：

```
完成：coupon-stock CT2026001 92788 → 92796
DONE type=coupon-stock · key=CT2026001 · id=41 · before=92788 → after=92796 · owning 服务已执行完成
```

**差 8 份券的余量**——Redis 里的缓存比 DB 口径少 8。修完再读大盘：三个类型全 0；
`marketing_coupon_reheat_executed{type=coupon-stock}=1`；审计页 `cache.reheat` 从 96 涨到 97
（这一对数字也是"界面写的动作照样进审计"的旁证，两次操作各 +1）。

这是 ④ 只能"说出来"的那件事第一次有人**在界面里把它修掉**：面板给的是条数，修的地方是重预热页。

## 5. 退出与守卫

`退出` → `localStorage` 两把键（`mkt.admin.token` / `mkt.admin.expiresAt`）清空、URL 回 `/ui/login`；
随后直接打开 `/ui/users` → 跳 `/ui/login?next=/users`（**没发出任何数据请求**，守卫在解析路由时就拦了）。
这一格补上了 LITE 那趟没单独点开的账号页（`6-browser-journey.md` 的边界 ①）。

## 边界（不夸大）

- 页面驱动用的是**页面内 `el.click()`**。浏览器工具的 `click` 在这几页上点了不触发处理器
  （用同一个 uid 再试一次也一样，而工具的 `fill` 确实同步进了 v-model——否则 JS 点击拿不到账号），
  所以"真实鼠标点击能提交"这一层**没有被任一层证明**：vitest 在 jsdom 下也得显式派发 submit
  （jsdom 同样忽略 `type=submit` 的点击）。依据只有 HTML 语义（`form @submit.prevent` + `type="submit"`）。
  记在这里，别当成已验。
- 不符条数=1 的**根因没查**（谁在冒烟之后把 CT2026001 的缓存写成 92788）。⑥ 只负责让人看见并修好；
  查它是 ④/③ 那条线上的活。
- 面板说 `marketing-activity` / `marketing-coupon` / `marketing-admin` **没在跑**，而 `docker ps`
  显示六个容器都在。这是 ④ 的一处判定口径缺陷（详见下），不是界面渲染错——界面忠实画了接口给的 `false`。

## 顺带查出来的一处跨段缺陷（记给 ④/⑤，不在 ⑥ 里改）

`OpsSnapshotService.liveness()` 用 `redis.hasKey(ConfigKeys.schema(process))` 判活，
而 `ConfigSnapshotPoller.publishSchema()` 有一条**故意**的早退：
`registry.all().isEmpty()` 时不写空自述（"没有可改参数的服务不写空自述：那会让 unreported 失去意义"）。
FULL 容器里只有 gateway / discount / seckill 声明了 `ConfigDefinition`
（实测计数 10 / 2 / 3），activity、coupon、admin 是 **0**，
于是 `mkt:cfg:schema:*` 只有三条键（`redis-cli --scan` 实测：seckill / gateway / discount，TTL 152-160s），
那三个**在跑的**进程被面板读成"没在跑"。

LITE 与 dev 看不出来，是因为那两个模块聚在 standalone 里、由 standalone 一起自述。

修法有两条路，都在别的段里：① 判活换成"每进程无条件写的心跳键"，与"有没有可改参数"解耦；
② 面板把"没自述（本进程无可改参数）"与"没在跑"分开展示（`-1`/`null` 的纪律本来就支持这个区分）。
**没动它**：⑥ 的边界是界面，改判活会越段；且这一格现在是"读数可疑"而不是"数据错"，
不影响任何已交付的断言。已记进 TODO。
