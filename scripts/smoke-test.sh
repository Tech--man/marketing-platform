#!/usr/bin/env bash
# ============================================================
# 九条链路冒烟测试（全部经网关 8090。C 端身份 = 真账号登录拿到的 JWT，用于运行时读与交易写；
# 配置类写自 ③ 起只存在于 /api/admin/**，链路 0 的创建/流转因此也用后台 token —— 脚本开头登录一次全脚本共用）
# 前置：./scripts/start-all.sh 已就绪；种子数据已由 docker init.sql 写入
# 用法：./scripts/smoke-test.sh          （消费者口令可用 CONSUMER_IDENTIFIER/CONSUMER_PASSWORD 覆写）
#
# C 端不再有共享 demo token：脚本开头用种子账号 demo/demo123456 登录一次，全脚本共用那枚
# access token。业务请求体里也不再带 userId —— 归属由那枚 token 决定，这正是本轮要验的事。
# ============================================================
set -uo pipefail

GW="${GW:-http://127.0.0.1:8090}"
JSON="Content-Type: application/json"
CID="${CONSUMER_IDENTIFIER:-demo}"
CPWD="${CONSUMER_PASSWORD:-demo123456}"
LOGIN_BODY=$(curl -s -m 25 -X POST "$GW/api/auth/login" -H "$JSON" \
  -d "{\"identifier\":\"$CID\",\"password\":\"$CPWD\"}")
TOKEN=$(echo "$LOGIN_BODY" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
# refresh 也留一份：链路 9 要验的是"轮换过的旧 refresh 再出现 = 整条会话作废"，
# 那是账号体系里唯一一条"错了不会有人立刻发现"的语义，只能留到最后一条链路做。
REFRESH=$(echo "$LOGIN_BODY" | sed -n 's/.*"refreshToken":"\([^"]*\)".*/\1/p')
# 取不到 token 必须立刻停：后面 60+ 条断言全都会拿到 40100，
# 那会表现为"C 端整片坏"，而真因只是这一步的登录没成
if [ -z "$TOKEN" ]; then
  echo "❌ C 端登录失败（$GW/api/auth/login，identifier=${CID}）—— 冒烟无法继续"; exit 1
fi
[ -n "$REFRESH" ] || echo "⚠️ 登录响应里没有 refreshToken：链路 9 的轮换断言会红" >&2
AUTH="Authorization: Bearer $TOKEN"

# 需要一个"全新的人"的链路，注册一个临时账号换它的 token。
# 为什么必须这样：券模板 per_user_limit=1、秒杀有防重购标记，而归属现在由登录身份决定 ——
# 旧的写法是每次随机一个 userId 塞进请求体，那等于让脚本自己给自己发身份。
# 复用 demo 那枚 token 的话，链路 1/3 从第二轮起恒红（实测：41000 已超过单人限领 /
# 41000 您已参与过该场秒杀），那不是链路坏了，是同一个人在反复吃同一份额度。
# 注意 account 侧注册有每 IP 限速（默认 10 次/小时），全脚本共注册 4 个，连跑两次
# 冒烟之间要留出窗口，否则这里先撞 42900。
new_consumer() { #  echoes "<identifier>\t<accessToken>\t<uid>"（失败时 token 段为空并打印原因到 stderr）
  local id body t uid
  id="smoke-$1-$(date +%s)-$RANDOM"
  body=$(curl -s -m 25 -X POST "$GW/api/auth/register" -H "$JSON" \
    -d "{\"identifier\":\"$id\",\"password\":\"Smoke123456\",\"nickname\":\"$2\"}")
  t=$(echo "$body" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
  uid=$(echo "$body" | sed -n 's/.*"uid":\([0-9]*\).*/\1/p')
  if [ -z "$t" ]; then
    # 把失败原因原样带出来：42900（每 IP 注册限速，默认 10 次/小时）和"账号服务没起来"
    # 长得一样（都是空 token），但处置完全不同 —— 前者等一小时，后者查进程
    echo "  (注册 $2 失败: $(echo "$body" | head -c 160))" >&2
  fi
  # 标识符要一起带回去：链路 9 的"改密作废全部会话"必须落在一个**自己的**账号上，
  # 改 demo 的口令会污染种子（下一轮登录直接红）。子 shell 里的赋值取不出来，
  # 所以走 stdout 传，由 require_consumer 拆开后写进 $1_ID。
  # uid 也要带回去，理由见 wipe_new_identity。
  printf '%s\t%s\t%s\n' "$id" "$t" "$uid"
}
# "全新的人"必须真的是全新的。限领与"每人一场"的判定在 **Redis**，而键里带的是 userId：
# 两套库布局（单库 marketing / 每服务一库 marketing_account）共用同一个 Redis 时，
# 各自的 auto_increment 会撞号 —— 本轮实测：单库那套里 70002-70008 已被上午的探针占过，
# 每服务一库档的 marketing_account 又从 70002 开始，于是刚注册的账号一出生就"已经领过一张"，
# 链路 1 与链路 6 六条断言一起红在 41000 上（红得很像"限领逻辑坏了"，其实两个人共用了一个号码）。
# 只清这个刚出生的 uid 自己的键，不扫别人的。
wipe_new_identity() { # $1=uid
  local uid=$1 k
  [ -n "$uid" ] || return 0
  for k in $(redis_admin --scan --pattern "coupon:user:*:$uid" 2>/dev/null) \
            $(redis_admin --scan --pattern "seckill:bought:*:$uid" 2>/dev/null); do
    redis_admin DEL "$k" >/dev/null 2>&1 || true
  done
}
require_consumer() { # $1=token 变量名 $2=用途 $3=昵称；顺带把 ${1}_ID 设成注册用的标识符
  local line id t uid
  line=$(new_consumer "$2" "$3")
  id=$(printf '%s' "$line" | cut -f1)
  t=$(printf '%s' "$line" | cut -f2)
  uid=$(printf '%s' "$line" | cut -f3)
  if [ -z "$t" ]; then
    echo "❌ 注册临时账号失败（$3）—— 后续断言全部不可信"; exit 1
  fi
  wipe_new_identity "$uid"
  printf -v "$1" 'Authorization: Bearer %s' "$t"
  printf -v "${1}_ID" '%s' "$id"
}
PASS=0; FAIL=0

stock_raw() { curl -s -H "$AUTH" "$GW/api/seckill/stock/SK2026001" | sed -n 's/.*"data":\[\([^]]*\)\].*/\1/p'; }
ok()   { echo "  ✅ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ $1"; echo "     响应: $2"; FAIL=$((FAIL+1)); }
head2() { echo; echo "== $1"; }
# 审计表当前最大 id：本轮前后的差值才是"这一轮真的写了行"的证据。
# 只按 action 查会把上一轮/别的形态留下的行算成本轮成果（踩过一次，别再来一次）。
audit_max_id() {
  curl -s -m 10 -H "$ADM $ADMIN_TOKEN" "$GW/api/admin/audits?page=1&size=1" \
    | sed -n 's/.*"records":\[{"id":\([0-9]*\).*/\1/p'
}

# 链路 5 会改在线配置与灰度列。跑挂了也不许把 3/s 的阈值留给下一轮形态——
# 那会让下一次冒烟在链路 3 上莫名其妙地红，而排查方向被指向异步链路。
# 清理必须在登出**之前**做：EXIT trap 触发时 token 往往已经被吊销，删除会静默 40102，
# 留下一行没人读的极端阈值（实测这样漏过一次 FULL=199999）。所以正常路径由收尾段显式
# 调用本函数，trap 只兜"中途退出"这条异常路径，CLEANED 标记保证不重复跑。
CFG_WRITES=()
CLEANED=0
config_cleanup() {
  [ "$CLEANED" = "1" ] && return 0
  CLEANED=1
  for w in "${CFG_WRITES[@]:-}"; do
    [ -z "$w" ] && continue
    curl -s -m 10 -X DELETE -H "$ADM $ADMIN_TOKEN" \
      "$GW/api/admin/config?cfgKey=${w%%|*}&form=${w##*|}" >/dev/null
  done
  # 删完再重广播一次：库里没了但快照还留着旧值，就是"恢复出厂不生效"的那个洞
  curl -s -m 10 -X POST -H "$ADM $ADMIN_TOKEN" "$GW/api/admin/config/rebroadcast" >/dev/null
}
# 连发 n 发秒杀查询，返回被限流（429）的次数
throttle_hits() {
  local n=$1 hits=0 code
  for _ in $(seq 1 "$n"); do
    code=$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTH" "$GW/api/seckill/activities")
    [ "$code" = "429" ] && hits=$((hits+1))
  done
  echo "$hits"
}
# 在线配置收敛上限：轮询默认 5s，这里给到 8 秒
wait_cfg() { sleep 8; }
redis_admin() { docker exec mkt-redis redis-cli "$@"; }
mysql_admin() { docker exec -i mkt-mysql mysql --default-character-set=utf8mb4 \
  -umarketing -pmarketing123 "$@"; }

# 断言响应包含指定片段
# 空针必须判失败：grep -q "" 恒真，会静默造出"绿"——实测券码取空时，
# "可用券列表包含新券"就是这样假绿的，真问题拖到下一行核销才暴露。
expect() { # desc needle body
  if [ -z "$2" ]; then bad "$1（断言针为空：脚本上一步取值失败）" "$3"; return; fi
  if echo "$3" | grep -q "$2"; then ok "$1"; else bad "$1" "$3"; fi
}

poll() { # url needle max_seconds
  local url=$1 needle=$2 n=$3 body=""
  for i in $(seq 1 "$n"); do
    body=$(curl -s -H "$AUTH" "$url")
    echo "$body" | grep -q "$needle" && { echo "$body"; return 0; }
    sleep 1
  done
  echo "$body"; return 1
}

# 重预热并等它真的落地。LITE 的 owning 服务就在本进程里，同步返回 DONE；
# FULL 是投给 owning 服务（DISPATCHED），回执最快也要一个消费轮询才回来。
# 不等这一手就去读面板，会把"跨进程投递要几秒"误判成"③ 修不动 / ④ 看不见"
# ——FULL 档实测就是这样红的，而两处读数其实都对。判据取自响应自己的 status，
# 不看形态也不猜环境变量。
reheat_wait() { # type key → 打印最终回执
  local r rid
  r=$(curl -s -m 10 -X POST -H "$AAUTH" \
      "$GW/api/admin/cache/reheat?type=$1&key=$2&force=true")
  echo "$r" | grep -q '"status":"DONE"' && { echo "$r"; return 0; }
  rid=$(echo "$r" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
  # 拿不到 id 就是压根没投递成功（41010 那一类），再轮询 30 秒也只是浪费时间
  [ -z "$rid" ] && { echo "$r"; return 1; }
  for _ in $(seq 1 15); do
    r=$(curl -s -m 10 -H "$AAUTH" "$GW/api/admin/cache/reheat/ack?type=$1&id=$rid")
    echo "$r" | grep -q '"status":"DONE"' && { echo "$r"; return 0; }
    sleep 2
  done
  echo "$r"; return 1
}

# 从最后一次抓回的面板里取某个类型的不符条数（读不出返回空，调用方必须判空——
# 空串参与数值比较会当成 0，那就又是一条静默的绿）。
ops_mismatch() { # type
  python3 -c "
import json
d = json.load(open('$OPS'))['data']['consistency']
print(next((r['mismatch'] for r in d if r['type'] == '$1'), ''))" 2>/dev/null
}

ADM="Authorization: Bearer"
# ③：后台登录上移到全脚本开头。链路 0 的"创建活动/状态流转"本来就是配置类写，
# 现在搬到 /api/admin/activities 之后，链路 0 也需要后台 token 了。
# 后台口令来自种子账号（README 公示的 dev 口令）。登录口有每 IP 限速（默认 10 次/分钟，
# 含成功尝试），全脚本一共 4 次登录（admin / 不存在的账号 / viewer / operator）；
# 连跑两次冒烟之间隔 60s 以上，否则这里会先撞 42900。
ADMIN_TOKEN=$(curl -s -m 25 -X POST "$GW/api/admin/auth/login" -H "$JSON" \
  -d '{"username":"admin","password":"rootdev123"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$ADMIN_TOKEN" ] && ok "后台登录成功并拿到 token" || bad "后台登录失败" ""
AAUTH="$ADM $ADMIN_TOKEN"

head2 "链路 0：活动中心（状态机 → 灰度 → 预算）：写走后台路径，读走 C 端"
# 每次用全新活动编号，创建→流转→预算全程自给自足，不依赖也不消耗共享种子数据
ACT_NO="ACT-SMOKE-$(date +%s)-$RANDOM"
TS_START=$(date -v-1H +%Y-%m-%dT%H:%M:%S 2>/dev/null || date -d '-1 hour' +%Y-%m-%dT%H:%M:%S)
TS_END=$(date -v+2d +%Y-%m-%dT%H:%M:%S 2>/dev/null || date -d '+2 days' +%Y-%m-%dT%H:%M:%S)
# ③：创建/流转是配置类写，只存在于 /api/admin/activities（owning 进程发布，网关转给 activity）
R=$(curl -s -m 15 -X POST -H "$AAUTH" -H "$JSON" "$GW/api/admin/activities" \
  -d "{\"activityNo\":\"$ACT_NO\",\"name\":\"冒烟活动\",\"startTime\":\"$TS_START\",\"endTime\":\"$TS_END\",\"budgetAmount\":100.00}")
expect "创建草稿活动（${ACT_NO}）" '"status":"DRAFT"' "$R"
R=$(curl -s -H "$AUTH" "$GW/api/activity/$ACT_NO/participatable")
expect "草稿态不可参与" '"data":false' "$R"
R=$(curl -s -m 15 -X POST -H "$AAUTH" "$GW/api/admin/activities/$ACT_NO/transition?event=APPROVE")
expect "DRAFT 直接 APPROVE 被状态机拒绝（41001）" '"code":41001' "$R"
R=$(curl -s -m 15 -X POST -H "$AAUTH" "$GW/api/admin/activities/$ACT_NO/transition?event=SUBMIT")
expect "SUBMIT → AUDITING" '"status":"AUDITING"' "$R"
R=$(curl -s -m 15 -X POST -H "$AAUTH" "$GW/api/admin/activities/$ACT_NO/transition?event=APPROVE")
expect "APPROVE → GRAY" '"status":"GRAY"' "$R"
R=$(curl -s -m 15 -X POST -H "$AAUTH" "$GW/api/admin/activities/$ACT_NO/transition?event=PROMOTE")
expect "PROMOTE → ONLINE" '"status":"ONLINE"' "$R"
R=$(curl -s -H "$AUTH" "$GW/api/activity/$ACT_NO/participatable")
expect "上线后可参与" '"data":true' "$R"
R=$(curl -s -H "$AUTH" "$GW/api/activity/$ACT_NO/gray-hit")
expect "未配灰度规则按全量放行" '"data":true' "$R"
R=$(curl -s -H "$AUTH" "$GW/api/activity/ACT2026001/gray-hit")
expect "已配灰度活动（ACT2026001 percent=100）命中" '"data":true' "$R"
R=$(curl -s -X POST "$GW/api/activity/$ACT_NO/budget/deduct" -H "$AUTH" -H "$JSON" \
  -d "{\"amountCents\":3000,\"bizKey\":\"$ACT_NO-B1\"}")
expect "预算扣减 30 元成功" '"code":0' "$R"
R=$(curl -s -H "$AUTH" "$GW/api/activity/$ACT_NO/budget/remain")
expect "剩余预算 7000 分" '"data":7000' "$R"
R=$(curl -s -X POST "$GW/api/activity/$ACT_NO/budget/deduct" -H "$AUTH" -H "$JSON" \
  -d "{\"amountCents\":3000,\"bizKey\":\"$ACT_NO-B1\"}")
expect "同 bizKey 重复扣减幂等返回" '"code":0' "$R"
# 幂等不再是"给你一个成功"就算了：data 必须说清这次到底扣没扣钱（原来是 void + code=0，
# 调用方分不清真扣与回放）
expect "重复扣减在 data 里标成 REPLAYED" '"data":"REPLAYED"' "$R"
# 唯一索引作用域是 (活动, bizKey)：同一个 bizKey 用在另一个活动上必须真扣，
# 早先全局唯一会把第二次静默吞掉（既不扣款也返回成功）。
R=$(curl -s -X POST "$GW/api/activity/ACT2026001/budget/deduct" -H "$AUTH" -H "$JSON" \
  -d "{\"amountCents\":100,\"bizKey\":\"$ACT_NO-B1\"}")
expect "同 bizKey 换活动仍真扣（DEDUCTED）" '"data":"DEDUCTED"' "$R"
R=$(curl -s -H "$AUTH" "$GW/api/activity/$ACT_NO/budget/remain")
expect "重复扣减未二次扣款（仍 7000 分）" '"data":7000' "$R"
R=$(curl -s -X POST "$GW/api/activity/$ACT_NO/budget/deduct" -H "$AUTH" -H "$JSON" \
  -d "{\"amountCents\":8000,\"bizKey\":\"$ACT_NO-B2\"}")
expect "超余额扣减被拒（41003 预算不足）" '"code":41003' "$R"
# 下面两条是算术守卫：被拒的那笔必须"什么都没留下"（既不留流水行也不动余额），
# 两笔成功的扣减必须让余额精确递减 3000+2000 —— 多扣（一笔扣两遍）与少扣（扣了没记账）都会破它。
R=$(curl -s -H "$AUTH" "$GW/api/activity/$ACT_NO/budget/remain")
expect "被拒扣减不留痕（余额仍 7000 分）" '"data":7000' "$R"
R=$(curl -s -X POST "$GW/api/activity/$ACT_NO/budget/deduct" -H "$AUTH" -H "$JSON" \
  -d "{\"amountCents\":2000,\"bizKey\":\"$ACT_NO-B3\"}")
expect "第二笔 bizKey 扣减 20 元成功" '"code":0' "$R"
R=$(curl -s -H "$AUTH" "$GW/api/activity/$ACT_NO/budget/remain")
expect "两笔扣减后余额精确递减到 5000 分" '"data":5000' "$R"
R=$(curl -s -m 15 -X POST -H "$AAUTH" -H "$JSON" "$GW/api/admin/activities" \
  -d "{\"activityNo\":\"$ACT_NO\",\"name\":\"重复\",\"startTime\":\"$TS_START\",\"endTime\":\"$TS_END\",\"budgetAmount\":1.00}")
expect "重复活动编号被拒" '"code":41000' "$R"
R=$(curl -s -m 15 -X POST -H "$AAUTH" "$GW/api/admin/activities/$ACT_NO/transition?event=FINISH")
expect "FINISH 进入终态" '"status":"FINISHED"' "$R"
R=$(curl -s -H "$AUTH" "$GW/api/activity/$ACT_NO/participatable")
expect "终态后不可参与" '"data":false' "$R"

head2 "链路 1：领券（网关 → 风控 → Lua 预扣 → MQ → 幂等落库 → 轮询 → 核销）"
REQ_ID="SMOKE-$(date +%s)-$RANDOM"
# 每次冒烟换一个全新登录身份，避免历史数据触发单人限领（见 new_consumer 的注释）
require_consumer AUTH coupon-j1 领券用户
R=$(curl -s -X POST "$GW/api/coupon/grant" -H "$AUTH" -H "$JSON" \
  -d "{\"requestId\":\"$REQ_ID\",\"templateNo\":\"CT2026001\"}")
expect "领券受理（requestId=${REQ_ID}）" '"code":0' "$R"
# 断言针必须与 poll 针一致：poll 超时后照样把最后一次响应打出来，若这里只查
# "couponCode" 这个键名，PROCESSING（couponCode:null）会蒙过去，接着券码取空、
# 核销报 40000"couponCode 必填"——红在错误的行上，看着像核销坏了。
# 券码固定 CP 前缀，针带到 CP 才算是真 SUCCESS；FULL 首次消费要走 RocketMQ 冷启动，给 30s。
R=$(poll "$GW/api/coupon/grant/result/$REQ_ID" '"couponCode":"CP' 30)
expect "轮询到领券 SUCCESS 并返回券码" '"couponCode":"CP' "$R"
COUPON_CODE=$(echo "$R" | sed -n 's/.*"couponCode":"\([^"]*\)".*/\1/p')
R=$(curl -s -H "$AUTH" "$GW/api/coupon/usable")
expect "用户可用券列表包含新券" "$COUPON_CODE" "$R"
R=$(curl -s -X POST "$GW/api/coupon/consume" -H "$AUTH" -H "$JSON" \
  -d "{\"couponCode\":\"$COUPON_CODE\",\"orderNo\":\"SO-$(date +%s)\"}")
expect "核销成功" '"code":0' "$R"
R=$(curl -s -X POST "$GW/api/coupon/grant" -H "$AUTH" -H "$JSON" \
  -d "{\"requestId\":\"$REQ_ID\",\"templateNo\":\"CT2026001\"}")
expect "同 requestId 重复领券幂等回放原受理凭证（不重复扣库存）" 'ACCEPTED' "$R"

head2 "链路 2：优惠计算（满200减30 与 8.5 折叠加 + 行级分摊）"
# 预热必须用**同一个请求体**：首算会因 JIT / 连接冷启动踩到 calcTimeoutMs 返回
# degraded:true（实测 OrbStack 崩溃重启后的第一轮冒烟就挂在这里）。用一个无规则的
# 探针预热是无效的——它不触发本请求要走的规则匹配路径。
# 只重试预热调用，断言仍打最后一次真实响应；重试耗尽仍降级则由 expect 如实判失败。
CALC_BODY='{
  "activityNo": null, "userTags": [],
  "items": [
    {"lineId":"L1","skuId":9001,"itemId":7001,"tags":["DIGITAL"],"unitPrice":199.90,"quantity":1},
    {"lineId":"L2","skuId":9002,"itemId":7002,"tags":["CLOTHES"],"unitPrice":100.00,"quantity":1}
  ]}'
R=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  R=$(curl -s -X POST "$GW/api/discount/calculate" -H "$AUTH" -H "$JSON" -d "$CALC_BODY")
  printf '%s' "$R" | grep -q '"degraded":false' && break
  sleep 1
done
expect "总价 299.90 > 满减门槛 200，命中优惠" '"degraded":false' "$R"
expect "返回行级分摊" '"shares"' "$R"

head2 "链路 3：秒杀（预热 → 抢购 → MQ 建单 → 轮询 → 支付）"
# 抢购用的是链路 1 起就换上的那个全新身份：秒杀有"每人一场"的防重购标记，
# 而复用 demo 账号从第二轮起会恒吃 41000（旧写法是每轮随机一个 userId 绕开，
# 现在自报 userId 不改变得了，随机性只能落在身份本身）
R=$(curl -s -H "$AUTH" "$GW/api/seckill/activities")
expect "在线秒杀活动 SK2026001" 'SK2026001' "$R"
R=$(curl -s -X POST "$GW/api/seckill/grab" -H "$AUTH" -H "$JSON" \
  -d "{\"activityNo\":\"SK2026001\"}")
expect "抢购受理返回排队 token" '"code":0' "$R"
TK=$(echo "$R" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
R=$(poll "$GW/api/seckill/grab/result/$TK" 'SUCCESS:' 40)
expect "轮询到下单 SUCCESS" 'SUCCESS:' "$R"
ORDER_NO=$(echo "$R" | sed -n 's/.*SUCCESS:\([^",]*\).*/\1/p')
R=$(curl -s -X POST "$GW/api/seckill/pay/$ORDER_NO" -H "$AUTH")
expect "模拟支付回调成功" '"status":"PAID"' "$R"
R=$(curl -s -X POST "$GW/api/seckill/grab" -H "$AUTH" -H "$JSON" \
  -d "{\"activityNo\":\"SK2026001\"}")
expect "同用户重复抢购被拒绝" '"code":[1-9]' "$R"

head2 "链路 3+：并发防超卖（按当前余量自适应并发）"
# 库存基线从接口取，不写死：压测/复跑会消耗共享种子库存，写死会让冒烟不可重复
SK_ACT=$(curl -s -H "$AUTH" "$GW/api/seckill/activities")
TOTAL=$(echo "$SK_ACT" | python3 -c "import sys,json;print([a['totalStock'] for a in json.load(sys.stdin)['data'] if a['activityNo']=='SK2026001'][0])" 2>/dev/null || echo "?")
SOLD0=$(echo "$SK_ACT" | python3 -c "import sys,json;print([a['soldStock'] for a in json.load(sys.stdin)['data'] if a['activityNo']=='SK2026001'][0])" 2>/dev/null || echo "?")
REMAIN0=$(python3 -c "print(sum(int(x) for x in '$(stock_raw)' .split(',') if x.strip()))" 2>/dev/null || echo "?")
if [ "$TOTAL" = "?" ] || [ "$REMAIN0" = "?" ]; then
  bad "读取秒杀库存基线失败（接口或数据异常）" "$SK_ACT"
  CONC=0
else
  CONC=$(( REMAIN0 < 60 ? REMAIN0 : 60 ))
  [ "$CONC" -gt 0 ] && ok "库存基线可读（total=$TOTAL sold=$SOLD0 余量=${REMAIN0}，本轮并发 ${CONC}）" \
    || bad "余量为 0，无法做并发防超卖验证；先执行 ./scripts/reset-demo-data.sh" "remain=$REMAIN0"
fi
TMP=$(mktemp -d); trap 'rm -rf "$TMP"; config_cleanup' EXIT
for i in $(seq 1 "$CONC"); do
  ( curl -s -X POST "$GW/api/seckill/grab" -H "$AUTH" -H "$JSON" \
      -d "{\"activityNo\":\"SK2026001\"}" > "$TMP/$i.resp" ) &
done
wait
ACCEPTED=$(grep -l '"code":0' "$TMP"/*.resp 2>/dev/null | wc -l | tr -d ' ')
REJECTED=$(ls "$TMP"/*.resp 2>/dev/null | wc -l | tr -d ' '); REJECTED=$((REJECTED-ACCEPTED))
echo "  受理 $ACCEPTED / 拒绝 ${REJECTED}（售罄或限流）"
[ "$CONC" = "$((ACCEPTED + REJECTED))" ] \
  && ok "受理+拒绝守恒等于并发数（${CONC}）" || bad "并发请求丢失应答" "accepted=$ACCEPTED rejected=$REJECTED conc=$CONC"
# 多轮扫描未终态 token（broker 在 Rosetta 模拟下投递可能有分钟级抖动）
OK_ALL=1
PENDING=()
for i in $(seq 1 60); do
  T=$(sed -n 's/.*"token":"\([^"]*\)".*/\1/p' "$TMP/$i.resp" 2>/dev/null)
  [ -n "$T" ] && PENDING+=("$i:$T")
done
for _round in $(seq 1 8); do
  [ ${#PENDING[@]} -eq 0 ] && break
  sleep 10
  STILL=()
  for item in "${PENDING[@]}"; do
    i=${item%%:*}; T=${item#*:}
    RES=$(curl -s -H "$AUTH" "$GW/api/seckill/grab/result/$T")
    if echo "$RES" | grep -qE 'SUCCESS:|FAIL:'; then
      echo "$RES" | grep -q 'SUCCESS:' || { OK_ALL=0; echo "  用户 #$i token=$T 下单失败: $RES"; }
    else
      STILL+=("$item")
    fi
  done
  PENDING=("${STILL[@]:-}")
done
for item in "${PENDING[@]:-}"; do
  [ -z "$item" ] && continue
  i=${item%%:*}
  OK_ALL=0
  echo "  用户 #$i 超时未成交(token=${item#*:})"
  # 失败时把原始应答与再查一次的结果一起打出来：多数"未成交"其实是取 token 或轮询侧的问题
  echo "     受理应答: $(cat "$TMP/$i.resp" 2>/dev/null | head -c 200)"
  echo "     复查结果: $(curl -s -H "$AUTH" "$GW/api/seckill/grab/result/${item#*:}" | head -c 200)"
done
[ $OK_ALL -eq 1 ] && ok "全部受理请求最终下单成功（异步链路闭环）" || bad "存在受理未成交" ""
REMAIN=$(python3 -c "print(sum(int(x) for x in '$(stock_raw)'.split(',') if x.strip()))" 2>/dev/null || echo "?")
[ "$REMAIN" != "?" ] && [ "$REMAIN" -ge 0 ] \
  && ok "Redis 分桶无超卖（余量 $REMAIN ≥ 0）" || bad "库存异常" "$REMAIN"
# 账实一致：DB 已售增量必须等于最终成交数（桶扣了但没建单、或建单了但没扣桶都会被抓出来）
SOLD1=$(curl -s -H "$AUTH" "$GW/api/seckill/activities" \
  | python3 -c "import sys,json;print([a['soldStock'] for a in json.load(sys.stdin)['data'] if a['activityNo']=='SK2026001'][0])" 2>/dev/null || echo "?")
SUCC=$(grep -ho '"token":"[^"]*"' "$TMP"/*.resp 2>/dev/null | wc -l | tr -d ' ')
# 恒等式而非增量：超时取消 Job 会在窗口内递减 sold_stock，用"增量==受理数"会偶发假失败；
# 而 分桶余量 + DB 已售 == 总库存 对取消抖动免疫，且超卖/漏扣/桶与库不一致都会破坏它。
if [ "$SOLD1" != "?" ] && [ "$REMAIN" != "?" ] && [ "$TOTAL" != "?" ]; then
  echo "  DB sold_stock: $SOLD0 → ${SOLD1}；Redis 余量 ${REMAIN}；总库存 $TOTAL"
  [ "$((REMAIN + SOLD1))" -eq "$TOTAL" ] \
    && ok "账实一致：分桶余量 + DB 已售 == 总库存（$REMAIN + $SOLD1 == ${TOTAL}）" \
    || bad "库存账实不符（超卖 / 漏扣 / 回补异常）" "remain=$REMAIN sold=$SOLD1 total=$TOTAL"
else
  bad "读取库存基线或结算数失败" "sold=$SOLD1 remain=$REMAIN total=$TOTAL"
fi

head2 "链路 4：管理后台（两套凭证不互通 → 角色 → 分页 → 重预热生效 → 留痕）"
# 账号不存在与口令错必须同码同文，否则登录口就是用户名枚举接口
R_GHOST=$(curl -s -m 15 -X POST "$GW/api/admin/auth/login" -H "$JSON" \
  -d '{"username":"no-such-admin","password":"whatever123"}')
expect "不存在的账号与口令错同码" '"code":40100' "$R_GHOST"

# 无凭证 / C 端共享 token 都进不了后台：两套凭证不互通是这次后台的地基
expect "无凭证访问后台被拒" '"code":40100' "$(curl -s -m 10 "$GW/api/admin/users")"
expect "C 端 token 打后台被拒" '"code":40100' "$(curl -s -m 10 -H "$AUTH" "$GW/api/admin/users")"
# P1-7（2026-10-01 审计）：401/403/429 的真实 HTTP 状态也要钉住——body.code 契约前端照旧
# 只认它，但监控/熔断/LB 健康判定看的是状态码。归属（v3 复审 N-21 修正）：这条钉的是
# **网关侧**拒绝（无凭证在网关 AdminAuthFilter 就被 401，到不了 MVC advice）；进程内
# advice 的状态映射由 ActivityControllerTest 的 MockMvc 断言钉住——渲染管道盖回 200
# 那类回归（Boot 3.4 升级窗口实测踩过）的第一现场在那边，排障别找错地方。
expect "无凭证打后台回真实 HTTP 401（不是 200+40100）" '^401$' \
  "$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$GW/api/admin/users")"

# 只读角色的写动作在网关就被挡（40300），读仍然放行
VIEWER_TOKEN=$(curl -s -m 25 -X POST "$GW/api/admin/auth/login" -H "$JSON" \
  -d '{"username":"viewer","password":"demo123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
expect "只读角色调写接口 40300" '"code":40300' \
  "$(curl -s -m 10 -X POST -H "$ADM $VIEWER_TOKEN" "$GW/api/admin/cache/reheat?type=budget&key=$ACT_NO")"
expect "只读角色调读接口放行" '"code":0' \
  "$(curl -s -m 10 -H "$ADM $VIEWER_TOKEN" "$GW/api/admin/users?page=1&size=1")"

# 分页契约：total 与当页条数一起回，且 size 被夹住（不断言 total 的具体值：
# 种子账号数会变，写死 3 会在下次加账号时无辜变红）
R=$(curl -s -m 10 -H "$AAUTH" "$GW/api/admin/users?page=1&size=2")
expect "分页响应带 total" '"total":' "$R"
[ "$(echo "$R" | grep -o '"id":' | wc -l | tr -d ' ')" -le 2 ] \
  && ok "当页条数未超请求的 size" || bad "分页未生效（返回超过 size 条）" "$R"

# 地雷 A 的回归锚：改 DB 不重预热就必须看不见，重预热后才生效。
# 生效路径分形态（母版 §6.3）：
#   LITE  —— reheater 与后台同进程，同步刷完直接带回 after；
#   FULL  —— admin 进程里没有 reheater，③ T8 起改为投给 owning 服务并取回执。
#   投递本身不等于成功，所以必须把回执轮询到 DONE、再回 C 端核对余额才算数；
#   只有"集群里连消费组都没有"才允许回 41010（那才是真的没人能做）。
# 判据用 /cache/types（本 JVM 注册了哪些 reheater），不猜环境变量。
REMAIN0=$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/$ACT_NO/budget/remain" | sed -n 's/.*"data":\([0-9-]*\).*/\1/p')
AUDIT_BEFORE=$(audit_max_id)
HAS_BUDGET_REHEATER=$(curl -s -m 10 -H "$AAUTH" "$GW/api/admin/cache/types" \
  | grep -c '"budget"' || true)
if docker exec mkt-mysql mysql -umarketing -pmarketing123 -e \
     "UPDATE ${MYSQL_DB:-marketing}.activity SET budget_amount = budget_amount + 1 WHERE activity_no='$ACT_NO'" >/dev/null 2>&1; then
  RAISED=$((REMAIN0 + 100))   # +1 元 == +100 分
  SAME=$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/$ACT_NO/budget/remain" | sed -n 's/.*"data":\([0-9-]*\).*/\1/p')
  [ "$SAME" = "$REMAIN0" ] \
    && ok "只改 DB 时预扣缓存不动（地雷 A 的现状被钉住）" || bad "缓存自己变了，断言失效" "$SAME"
  R=$(curl -s -m 10 -X POST -H "$AAUTH" "$GW/api/admin/cache/reheat?type=budget&key=$ACT_NO&force=true")
  if [ "$HAS_BUDGET_REHEATER" -ge 1 ]; then
    expect "重预热按公式抬到新口径" "\"after\":$RAISED" "$R"
  else
    expect "FULL 分进程下重预热被投递给 owning 服务" '"status":"DISPATCHED"' "$R"
  fi
  if [ "$HAS_BUDGET_REHEATER" -lt 1 ]; then
    RID=$(echo "$R" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
    ACK=""
    for _ in $(seq 1 15); do
      ACK=$(curl -s -m 10 -H "$AAUTH" "$GW/api/admin/cache/reheat/ack?type=budget&id=$RID")
      echo "$ACK" | grep -q '"status":"DONE"' && break
      sleep 2
    done
    # 只认 DONE：DISPATCHED 停在半路（owning 服务没起来）在这里就是红的
    expect "owning 服务执行完并把 after 写回回执" "\"after\":$RAISED" "$ACK"
  fi
  NOW=$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/$ACT_NO/budget/remain" | sed -n 's/.*"data":\([0-9-]*\).*/\1/p')
  [ "$NOW" = "$RAISED" ] && ok "重预热后 C 端余额等于新口径（$RAISED 分）" || bad "重预热未生效" "$NOW"
else
  bad "无法直连 mkt-mysql 抬预算（重预热断言没跑）" "docker exec 失败"
fi

# 留痕：只认"本轮新增的那一行"（审计表跨形态共用一份，按 action 查会把上一轮的行也算进来）
R=$(curl -s -m 10 -H "$AAUTH" "$GW/api/admin/audits?action=cache.reheat&page=1&size=1")
AUDIT_AFTER=$(audit_max_id)
if [ "${AUDIT_AFTER:-0}" -gt "${AUDIT_BEFORE:-0}" ]; then
  expect "本轮动作写入审计" '"action":"cache.reheat"' "$R"
else
  bad "审计未新增（重预热被拒时也应有拒绝痕迹？当前设计只在成功后落）" "before=$AUDIT_BEFORE after=$AUDIT_AFTER"
fi

# 登出不在这里做：链路 5 要复用同一枚 token。登录口有每 IP 10 次/分钟（含成功尝试），
# 再登一次就会把"因为限速所以测不了"变成最坏的一种红。收尾段统一登出。

head2 "链路 5：在线配置下发（不重启改阈值 → 快照丢失退 yml → 灰度真值在 DB）"
SECKILL_LIMIT="gateway.ratelimit.seckill-route.limit"
CFGJSON="Content-Type: application/json"

# 0) 本进程解析到的形态：两档共用同一份 MySQL 时，它是唯一可信的"我在哪一档"
CFG_JSON=$(curl -s -m 10 -H "$AAUTH" "$GW/api/admin/config")
OWN_FORM=$(echo "$CFG_JSON" | sed -n 's/.*"ownForm":"\([^"]*\)".*/\1/p')
OTHER_FORM="LITE"; [ "$OWN_FORM" = "LITE" ] && OTHER_FORM="FULL"
[ -n "$OWN_FORM" ] && ok "配置页报出自己的形态 form=$OWN_FORM" \
  || bad "ownForm 缺失（DEPLOY_FORM 没落到这一档？）" "$CFG_JSON"

# 1) 改限流阈值不重启：GLOBAL 收到 3/s，连发 6 发必须看到 429
#    body 一律用单引号：escaped-double-quote 的 JSON 嵌在 "$(...)" 里会被外壳把引号吃掉，
#    实测这样发出去的 body 会变成裸字符串，服务端回 50000 看着像"接口坏了"。
R=$(curl -s -m 15 -X PUT -H "$AAUTH" -H "$CFGJSON" "$GW/api/admin/config" \
  -d '{"cfgKey":"gateway.ratelimit.seckill-route.limit","form":"GLOBAL","value":"3","remark":"smoke"}')
expect "写 GLOBAL 阈值成功且生效值=3" '"effectiveValue":"3"' "$R"
CFG_WRITES+=("$SECKILL_LIMIT|GLOBAL")
wait_cfg; sleep 2
HITS=$(throttle_hits 6)
[ "$HITS" -ge 1 ] && ok "阈值 3/s 不重启生效（6 发中 $HITS 发 429）" \
  || bad "阈值未生效（期望至少 1 发 429）" "hits=$HITS"

# 2) 非法输入在写侧就被拒，库里与 Redis 都不许被碰
#    断言同时看 code 与 message：只看 40000 会让"请求体没解析成功"蒙混过关
R=$(curl -s -m 10 -X PUT -H "$AAUTH" -H "$CFGJSON" "$GW/api/admin/config" \
  -d '{"cfgKey":"gateway.ratelimit.seckill-route.limit","form":"GLOBAL","value":"0"}')
expect "越界值被拒（40000）" '"code":40000' "$R"
expect "越界值的报错点出允许区间" '允许区间' "$R"
R=$(curl -s -m 10 -X PUT -H "$AAUTH" -H "$CFGJSON" "$GW/api/admin/config" \
  -d '{"cfgKey":"nope.key","form":"GLOBAL","value":"1"}')
expect "未声明的键被拒（40000）" '"code":40000' "$R"
expect "未声明键的报错说明为什么不能改" '未被任何在线服务声明' "$R"
# operator 在网关是"可写运维角色"，但阈值不是运维动作：这条钉的是 endpoint 级细筛
OP_TOKEN=$(curl -s -m 25 -X POST "$GW/api/admin/auth/login" -H "$CFGJSON" \
  -d '{"username":"operator","password":"demo123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
R=$(curl -s -m 10 -X PUT -H "Authorization: Bearer $OP_TOKEN" -H "$CFGJSON" "$GW/api/admin/config" \
  -d '{"cfgKey":"gateway.ratelimit.seckill-route.limit","form":"GLOBAL","value":"9"}')
expect "operator 改阈值被拒（40300）" '"code":40300' "$R"
expect "被拒的报错点名需要的角色" '需要角色' "$R"

# 3) 分形态不串：给另一档写一个极端值，本档生效值必须不动
curl -s -m 15 -X PUT -H "$AAUTH" -H "$CFGJSON" "$GW/api/admin/config" \
  -d '{"cfgKey":"gateway.ratelimit.seckill-route.limit","form":"'"$OTHER_FORM"'","value":"199999"}' >/dev/null
CFG_WRITES+=("$SECKILL_LIMIT|$OTHER_FORM")
wait_cfg
EFFECTIVE=$(curl -s -m 10 -H "$AAUTH" "$GW/api/admin/config" \
  | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print([x['effectiveValue'] for x in d['entries'] if x['key']=='$SECKILL_LIMIT'][0])" \
  2>/dev/null || echo "?")
[ "$EFFECTIVE" = "3" ] && ok "另一档（${OTHER_FORM}）写 199999 不污染本档 form=$OWN_FORM" \
  || bad "跨形态覆盖串了：本档生效值=${EFFECTIVE}（期望 3）" "other=$OTHER_FORM"

# 4) 快照丢失 → 退回本进程 yml 出厂值，网关继续服务（既不过限也不拒绝服务）
redis_admin DEL mkt:cfg:snapshot:GLOBAL mkt:cfg:version:GLOBAL \
  mkt:cfg:snapshot:LITE mkt:cfg:version:LITE \
  mkt:cfg:snapshot:FULL mkt:cfg:version:FULL \
  mkt:cfg:snapshot:DEV mkt:cfg:version:DEV >/dev/null
wait_cfg; sleep 2
HITS=$(throttle_hits 6)
[ "$HITS" = "0" ] && ok "快照被删后退回 yml 出厂值（6 发全通过）" \
  || bad "快照丢失后仍在限流或已不可服务" "hits=$HITS"

# 5) "重新广播"修好已落库未广播的窗口
expect "重新广播返回成功码" '"code":0' \
  "$(curl -s -m 10 -X POST -H "$AAUTH" "$GW/api/admin/config/rebroadcast")"
wait_cfg; sleep 2
HITS=$(throttle_hits 6)
[ "$HITS" -ge 1 ] && ok "重广播后在线值重新生效（$HITS 发 429）" \
  || bad "重广播没有恢复在线阈值" "hits=$HITS"

# 6) 恢复出厂 = 删行（不是写回原值）
expect "删除覆盖返回成功" '"code":0' "$(curl -s -m 10 -X DELETE -H "$AAUTH" \
  "$GW/api/admin/config?cfgKey=$SECKILL_LIMIT&form=GLOBAL")"
wait_cfg; sleep 2
HITS=$(throttle_hits 6)
[ "$HITS" = "0" ] && ok "删行后回到本档出厂阈值" \
  || bad "删行没有恢复出厂" "hits=$HITS"

# 7) 灰度：真值在 DB 列，改阈值后 ≤8s 生效；删光 Redis 键也不会变成意外全量
#
# 这一组原来是"一个尾号命中的 userId + 一个尾号不命中的 userId"两条对照。
# 身份改成从 token 取之后那种写法在结构上就不成立了 —— 同一枚 token 打两次，
# 不可能一次 true 一次 false。改成按"阈值"取对照而不是按"人"取对照：
# 人固定，阈值动。这样既保住了"DB 列是真值来源"这条主断言，也不再需要挑选身份。
if mysql_admin -e "UPDATE ${MYSQL_DB:-marketing}.activity SET gray_percent=5 \
     WHERE activity_no='$ACT_NO'" >/dev/null 2>&1; then
  wait_cfg
  G5=$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/$ACT_NO/gray-hit")
  G5B=$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/$ACT_NO/gray-hit")
  if [ "$G5" = "$G5B" ] && echo "$G5" | grep -q '"data":'; then
    ok "灰度 5% 下同一身份的判定是确定的（两次一致，不是每次掷骰子）"
  else
    bad "同一身份的灰度判定不稳定" "first=$G5 second=$G5B"
  fi
  mysql_admin -e "UPDATE ${MYSQL_DB:-marketing}.activity SET gray_percent=100 \
    WHERE activity_no='$ACT_NO'" >/dev/null 2>&1
  wait_cfg
  expect "灰度 100% 时本人放行" '"data":true' \
    "$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/$ACT_NO/gray-hit")"
  mysql_admin -e "UPDATE ${MYSQL_DB:-marketing}.activity SET gray_percent=0 \
    WHERE activity_no='$ACT_NO'" >/dev/null 2>&1
  wait_cfg
  expect "灰度 0% 时本人被拒" '"data":false' \
    "$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/$ACT_NO/gray-hit")"
  redis_admin DEL mkt:cfg:snapshot:$OWN_FORM mkt:cfg:version:$OWN_FORM \
    mkt:cfg:snapshot:GLOBAL mkt:cfg:version:GLOBAL >/dev/null
  wait_cfg
  expect "删光 Redis 键后灰度仍按 DB 列判（0%，不是全量放行）" '"data":false' \
    "$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/$ACT_NO/gray-hit")"
  mysql_admin -e "UPDATE ${MYSQL_DB:-marketing}.activity SET gray_percent=NULL \
    WHERE activity_no='$ACT_NO'" >/dev/null 2>&1
else
  bad "无法直连 mkt-mysql 改灰度（链路 5 的灰度断言没跑）" "docker exec 失败"
fi
# ACT2026001 的种子灰度必须还在：链路 0 那两条断言靠的是 DB 列而不是 yml
expect "种子活动 ACT2026001 的灰度仍是 100%" '"data":true' \
  "$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/ACT2026001/gray-hit")"

head2 "链路 6：写入口收口（配置类写只剩 /api/admin/**，绕过网关等于没有凭证）"
# ① 旧的 C 端配置类写路径必须"真的没了"：命中 40400 而不是被静默转发到某个还在听的进程
R=$(curl -s -m 10 -X POST -H "$AUTH" -H "$JSON" -d '{"activityNo":"ACT-SMOKE-GONE"}' "$GW/api/activity")
expect "C 端 POST /api/activity 已收口（40400 资源不存在）" '"code":40400' "$R"
R=$(curl -s -m 10 -X PUT -H "$AUTH" -H "$JSON" \
  -d '{"budgetAmount":1.00,"version":1}' "$GW/api/activity/ACT2026001/budget")
expect "C 端改预算路径已收口（40400）" '"code":40400' "$R"

# ② 收口不能误伤交易写：C 端领券仍要照常受理。
# 这一轮必须是**新注册的账号**：券模板 per_user_limit=1，复用脚本开头那枚 demo token
# 会让第二轮起恒吃 41000「已超过单人限领数量」—— 那是状态残留，不是收口把路径写坏了。
# 上一版靠"每轮随机一个 userId"绕开；userId 现在由登录身份决定、自报不改变得了，
# 所以随机性只能上移到账号本身。
REQ6="SMOKE6-$(date +%s)-$RANDOM"
require_consumer AUTH6 coupon-j6 冒烟用户
R=$(curl -s -m 15 -X POST "$GW/api/coupon/grant" -H "$AUTH6" -H "$JSON" \
  -d "{\"requestId\":\"$REQ6\",\"templateNo\":\"CT2026001\"}")
expect "C 端交易写路径未被收口误伤（领券受理）" '"code":0' "$R"
# 两套凭证不互通是双向的：后台 token 打 C 端交易路径同样被拒。
# 只在"打后台被拒"这一侧设防的话，收口就等于给后台凭证开了一条 C 端写入口。
R=$(curl -s -m 15 -X POST "$GW/api/coupon/grant" -H "$AAUTH" -H "$JSON" \
  -d "{\"requestId\":\"$REQ6-B\",\"templateNo\":\"CT2026001\"}")
expect "后台 token 打 C 端交易路径被拒（40100）" '"code":40100' "$R"

# ③ 部署级回归锚（③ 段内 spec §3.2）：只带裸 X-Admin-* 头、**绕过网关**直连业务进程，
# 必须 40100。业务端口绑的是 *:808x（FULL 进程形态）而 standalone:8085 也发布了（LITE），
# 所以"头能伪造"不是假设，是这台机器上的事实。
FORGE_PATH="/api/admin/activities/$ACT_NO/budget"
forge_to() { # $@ = 额外身份参数（不带就是裸头）；返回首个可直连进程的响应
  local body port c base
  # 候选端口写成可覆写的：默认值是"本机进程形态"的拓扑（activity:8081 / standalone:8085），
  # 而冒烟经常要在自定义端口上验一套刚改完的代码 —— 那时 8085 上是另一份旧构建，
  # 这条断言测的就不是本轮改动了。
  for base in ${FORGE_BASES:-http://127.0.0.1:8081 http://127.0.0.1:8085}; do
    body=$(curl -s -m 5 -X PUT "$@" -H "X-Admin-Role: admin" -H "X-Admin-Name: attacker" -H "$JSON" \
      -d '{"budgetAmount":1.00,"version":1}' "$base$FORGE_PATH" 2>/dev/null || true)
    echo "$body" | grep -q '"code"' && { echo "$body"; return; }
  done
  # FULL 容器形态不发布应用端口（多副本设计），只能进容器打它自己的 8081
  c=$(docker ps --format '{{.Names}}' 2>/dev/null | grep -m1 -E '(^|-)marketing-activity-[0-9]+$' || true)
  [ -n "$c" ] || return
  docker exec "$c" curl -s -m 5 -X PUT "$@" -H "X-Admin-Role: admin" -H "X-Admin-Name: attacker" \
    -H "Content-Type: application/json" -d '{"budgetAmount":1.00,"version":1}' \
    "http://127.0.0.1:8081$FORGE_PATH" 2>/dev/null || true
}
FORGED=$(forge_to)
if [ -z "$FORGED" ]; then
  bad "没找到可直连的业务进程（裸头 40100 这条没跑成）" "候选 8081/8085 与容器内 curl 都无 JSON 响应"
else
  expect "只带裸 X-Admin-* 头直连业务端口被拒（伪造头不等于凭证）" '"code":40100' "$FORGED"
fi
# 配对断言：同一个直连口子换成正牌 X-Admin-Token 必须过身份这一关。
# version 故意写 999，让它停在 41008 而不是真的改共享种子数据。
# 少了这条，上面的 40100 也可能只是"端口不通/路由不存在"的另一种写法。
FORGED_OK=$(forge_to -H "X-Admin-Token: $ADMIN_TOKEN")
expect "同一发直连请求带合法 token 时身份放行（拒的是凭证不是路由）" '"code":41008' "$FORGED_OK"

# 账号体系的对偶断言：C 端也一样，裸 X-User-* 头不等于身份。
# 少这条的话，"业务服务只认签名 token"这件事就只在后台那一侧被钉住，
# 而 C 端这次改动恰恰是把 userId 从请求体里拿掉 —— 最容易被"改成读头"糊过去的位置。
# 探针必须自己挑一个"真能直连"的进程：早先的写法是取候选清单第一项（8081），
# 而 LITE 形态只有 standalone:8085 在听 → curl 空响应 → 红的是探针不是安全边界（本轮实测踩过）。
forged_user_probe() { # $1 = 额外身份头（空 = 只带裸头）
  # 候选端口是**券服务**的：8082（FULL 进程形态）/ 8085（LITE 与 dev 的聚合进程）。
  # 这里不能沿用后台那条断言的 8081 优先 —— 打的是 /api/coupon/usable，
  # 而 8081 是 activity：它答 40400"资源不存在"，也是一段带 "code" 的 JSON，
  # 于是两条断言一起红在错误的进程上（本轮 FULL 进程形态实测踩过）。
  # 接受条件也收紧成"0 或 401xx"：404 说明这里根本没这条路由，换下一个候选。
  local base c body
  for base in ${FORGE_USER_BASES:-http://127.0.0.1:8082 http://127.0.0.1:8085}; do
    body=$(curl -s -m 5 ${1:+-H "$1"} -H "X-User-Id: 70001" "$base/api/coupon/usable" 2>/dev/null || true)
    echo "$body" | grep -qE '"code":(0|401[0-9][0-9])' && { echo "$body"; return; }
  done
  # FULL 容器形态不发布应用端口，只能进 coupon 容器打它自己的 8082
  c=$(docker ps --format '{{.Names}}' 2>/dev/null | grep -m1 -E '(^|-)marketing-coupon-[0-9]+$' || true)
  [ -n "$c" ] || return
  docker exec "$c" curl -s -m 5 ${1:+-H "$1"} -H "X-User-Id: 70001" \
    "http://127.0.0.1:8082/api/coupon/usable" 2>/dev/null || true
}
FORGED_USER=$(forged_user_probe)
expect "只带裸 X-User-Id 直连业务端口被拒（C 端与后台同一条理由）" '"code":40100' "$FORGED_USER"
# 配对断言，与后台那条同一纪律：同一个口换成正牌签名 token 必须过身份这一关。
# 少了它，上面的 40100 也可能只是"端口不通/路由不存在"的另一种写法。
FORGED_USER_OK=$(forged_user_probe "Authorization: Bearer $TOKEN")
expect "同一发直连请求带签名 token 时身份放行（拒的是凭证不是路由）" '"code":0' "$FORGED_USER_OK"
# 旧的共享 demo token 必须彻底失效：它当年是"C 端万事通行"的那把钥匙，
# 留着任何一种认它的路径，账号体系就等于被旁路
LEGACY=$(curl -s -m 8 -H "Authorization: Bearer demo-token-123" "$GW/api/coupon/usable")
expect "旧的 C 端共享 demo token 不再通 anywhere（40100）" '"code":40100' "$LEGACY"
# 交易请求体不再接受调用方自报的 userId：先领成功、再换新 requestId 领就该撞 41000。
# 必须用一个**全新的**账号打这一对（链路 6 那个账号已经吃掉一张了，复用它会
# 让"首领成功"恒红）：账号自带额度，断言才不依赖任何前序状态。
require_consumer AUTH7 coupon-j7 归属探针
R=$(curl -s -m 15 -X POST "$GW/api/coupon/grant" -H "$AUTH7" -H "$JSON" \
  -d "{\"requestId\":\"SMOKE-DUP1-$(date +%s)-$RANDOM\",\"templateNo\":\"CT2026001\"}")
expect "新账号首领限领=1 的模板成功（受理）" '"code":0' "$R"
R=$(curl -s -m 15 -X POST "$GW/api/coupon/grant" -H "$AUTH7" -H "$JSON" \
  -d "{\"requestId\":\"SMOKE-DUP2-$(date +%s)-$RANDOM\",\"templateNo\":\"CT2026001\"}")
expect "同一身份换 requestId 再领被按本人拦下（41000，证明归属跟着 token 走）" '"code":41000' "$R"

# ④ discount 唯一的 GET 收进了后台前缀：冒烟此前一次都没打过它，
# 结果这条路径的红只能被部署探针抓到（修正 #31）
expect "后台规则列表可读（③ 之后 discount 唯一的 GET）" '"code":0' \
  "$(curl -s -m 10 -H "$AAUTH" "$GW/api/admin/discount/rules?page=1&size=3")"

head2 "链路 7：运维只读聚合（同式对拍积压 → 不可见不许填 0 → 恒等式能看见也能修好）"
OPS=/tmp/mkt-smoke-ops.json
R=$(curl -s -m 25 -H "$AAUTH" "$GW/api/admin/ops" -o "$OPS" -w '%{http_code}')
[ "$R" = "200" ] && ok "运维总览可读了（200）" || bad "运维总览 HTTP $R" "$(head -c 200 "$OPS")"
# ① 面板必须自报"这次读的是谁"：local 与 proxy 在 LITE 下是并存的（网关永远是独立进程）
expect "总览自报指标源与逐 target 路径" '"source":"\(local\|proxy\)"' "$(cat "$OPS")"
# ② 同式对拍：④ 报的未排空条数必须等于脚本自己按同一口径直连 MySQL 查出来的和。
#    断言它等于 0 是假信号（跑起来就有 in-flight），断言"两处相等"才是真信号。
#    库名来自 information_schema（与 ④ 同一发现路径），逐库拼限定名求和。
TOTAL=0; SCHEMA_HITS=0
for sch in $(mysql_admin -N -e "
  SELECT DISTINCT table_schema FROM information_schema.tables
   WHERE LOWER(table_name)='local_message'
     AND LOWER(table_schema) NOT IN ('information_schema','mysql','performance_schema','sys')" \
     2>/dev/null | tr -d '\r'); do
  n=$(mysql_admin -N -e "SELECT COUNT(*) FROM \`${sch}\`.local_message WHERE status IN ('PENDING','SENT')" \
        2>/dev/null | tr -d '\r')
  TOTAL=$((TOTAL + ${n:-0})); SCHEMA_HITS=$((SCHEMA_HITS + 1))
done
if [ "$SCHEMA_HITS" = "0" ]; then
  bad "对拍没跑成：一条 SQL 都没查到（④ 的积压读数没被验过）" "schemas=0"
else
  OPS_PENDING=$(python3 -c "import json;print(json.load(open('$OPS'))['data']['backlog']['totalPendingSent'])" 2>/dev/null || echo "?")
  [ "$OPS_PENDING" = "$TOTAL" ] \
    && ok "④ 的未排空合计与直连 SQL 同式相等（${TOTAL}）" \
    || bad "两处读数不一致：④=$OPS_PENDING 直查=$TOTAL" "见 $OPS"
fi
# ③ 通道差异按形态分岔（判据读面板自己说的，不猜环境变量）：
#    聚合档（LITE/dev，消息走 Redis Stream）有值；分进程档（FULL）显式 NOT_APPLICABLE
if [ "$HAS_BUDGET_REHEATER" -ge 1 ]; then
  expect "聚合档下 Stream 通道深度可读（不是空数组）" '"key":"MKT_STREAM_' "$(cat "$OPS")"
else
  expect "分进程档下 Stream 深度显式标不可见，不填 0" 'RocketMQ' "$(cat "$OPS")"
fi
# ④ 恒等式：④ 只读，所以先把缓存改错（不碰 DB），看它能不能说出来；再用 ③ 的重预热修回去。
#    这一条同时验了"发现"与"修"两端，也钉住 ④ 不许自己动手改数据。
#    改错必须挑 ④ 真在读的那 5 条：判定是 `ORDER BY id LIMIT 5` 的抽样，链路 0 新建的
#    $ACT_NO id 最大、根本不在样本里（实测这样红过一次——面板是对的，断言在验一个没人看的活动）。
SAMPLE_NO=$(mysql_admin -N -e "SELECT activity_no FROM ${MYSQL_DB:-marketing}.activity ORDER BY id LIMIT 1" \
             2>/dev/null | tr -d '\r' | head -1)
if [ -z "$SAMPLE_NO" ]; then
  bad "取不到抽样活动号，恒等式这一条没跑成" "SAMPLE_NO 为空"
else
  # 动手前先把自己要碰的那条预热成一致：换库布局时抽样里本来就带着上一布局的缓存漂移
  # （每服务一库档实测开局 budget=2 / coupon=1 / seckill=1，④ 报得没错，那是布局切换的账，
  # 不是本次 SET 的账）。只归零本条，其余不符项由下面的基线吸收，断言才不被环境噪声左右。
  if reheat_wait budget "$SAMPLE_NO" >/dev/null; then
    curl -s -m 25 -H "$AAUTH" "$GW/api/admin/ops" -o "$OPS"
    BASE=$(ops_mismatch budget)
    if [ -z "$BASE" ]; then
      bad "面板读不出 budget 的不符条数（后面两条无从比对）" "$(head -c 200 "$OPS")"
    else
      ok "面板基线可读：本档 budget 抽样内有 $BASE 条不符"
      # 999999999999 分 = 百亿级，任何真实预算都不可能等于它，
      # 所以条数一旦上涨，涨的那一条只可能是这个值造成的。
      redis_admin SET "activity:budget:$SAMPLE_NO" 999999999999 >/dev/null
      sleep 2; curl -s -m 25 -H "$AAUTH" "$GW/api/admin/ops" -o "$OPS"
      BROKE=$(ops_mismatch budget)
      if [ -n "$BROKE" ] && [ "$BROKE" -gt "$BASE" ]; then
        ok "缓存被改错后面板的不符条数涨了（$BASE → ${BROKE}，不是安静地显示 0）"
      else
        bad "改错缓存后面板没反应：期望大于 ${BASE}，实际 $BROKE" "$(head -c 300 "$OPS")"
      fi
      # 再要求它回到基线：只认"回落到不超过基线"，因为别的抽样项可能被定时任务改动，
      # 钉死等于 BASE 会把环境噪声变成红。
      RH=$(reheat_wait budget "$SAMPLE_NO")
      if echo "$RH" | grep -q '"status":"DONE"'; then
        curl -s -m 25 -H "$AAUTH" "$GW/api/admin/ops" -o "$OPS"
        FIXED=$(ops_mismatch budget)
        if [ -n "$FIXED" ] && [ "$FIXED" -le "$BASE" ]; then
          ok "重预热后面板回落到基线（$BROKE → ${FIXED}，④ 只读、③ 才修，这条线是通的）"
        else
          bad "重预热落了但面板没回落：期望 ≤${BASE}，实际 $FIXED" "$(head -c 300 "$OPS")"
        fi
      else
        bad "重预热 30s 内没落地，面板这一条无从判（先查 owning 服务的消费组在不在）" "$RH"
      fi
    fi
  else
    bad "预热基线失败：本条 budget 缓存没法归零，恒等式这一条没跑成" ""
  fi
fi
# ⑤ 链路 5 制造过 429，这里要求那份拒绝数真的能从网关进程读到（母版事实 #2 的正面证据）
expect "网关的限流拒绝数带 route 维度进了面板" 'gateway.rate.limit.rejected' "$(cat "$OPS")"

head2 "链路 8：后台界面（加了界面 ≠ 加了口子；缓存头错了会让人跑到旧索引上）"
# 静态资源本身不含数据，所以这三条各管一种"错了也不响"的事故：
#   ① 打不开 = ui-route/白名单没配对；② 索引可缓存 = 升级后旧索引配新指纹，白屏；
#   ③ 后台数据接口仍然要凭证 = 加界面没顺手开一个读数据的旁路。
UIIDX=/tmp/mkt-smoke-ui.html
R=$(curl -s -m 15 -o "$UIIDX" -w '%{http_code}' "$GW/ui/")
[ "$R" = "200" ] && ok "后台首页可打开（200，不带任何 token）" \
  || bad "后台首页 HTTP ${R}：ui-route 或白名单没配对" "$(head -c 200 "$UIIDX")"
expect "索引页 no-store（缓存了它就会拿旧索引去要新指纹）" \
  'cache-control: no-store' "$(curl -sI -m 15 "$GW/ui/" | tr -d '\r' | tr 'A-Z' 'a-z')"
# 深链必须回退到索引页：history 路由直接刷新 /ui/audits 不能 404
expect "深链回退到索引页（history 路由直接刷新才打得开）" \
  '<div id="app">' "$(curl -s -m 15 "$GW/ui/audits")"
ASSET=$(grep -o '/ui/assets/[^"]*\.js' "$UIIDX" | head -1)
if [ -z "$ASSET" ]; then
  bad "索引页里没有指纹脚本引用（界面是空壳，或 vite 的 base 漂了）" ""
else
  expect "指纹资源给一年 immutable" 'cache-control: public, max-age=31536000, immutable' \
    "$(curl -sI -m 15 "$GW$ASSET" | tr -d '\r' | tr 'A-Z' 'a-z')"
fi
expect "缺文件就是 404，不许回退成一份 HTML（否则浏览器只报模块加载失败）" \
  '^HTTP/[0-9.]* 404' "$(curl -sI -m 15 --path-as-is "$GW/ui/definitely-not-here.js" | tr -d '\r')"
expect "界面不给数据开旁路：无凭证打后台读接口仍是 40100" '"code":40100' \
  "$(curl -s -m 15 "$GW/api/admin/users")"

head2 "链路 9：消费者身份语义（旧 refresh 重放 = 整条会话作废；登出与改密当场生效）"
# 这一段刻意留在最后：它会把主 TOKEN 那条会话烧掉，而前面八条链路全在用 ${AUTH}。
# 之所以必须有这一段：这些语义单靠单测盖不住——旧实现里"按 refresh 摘要找受害会话"
# 在轮换之后必然查不到（rotate 是同一行就地换摘要），于是重放只被拒、会话不被吊销，
# 而当时的 mock 恰好 stub 成"查得到"，两边各自绿着把洞盖住。端到端要断的是
# "连刚换到的新 access 都进不来"，那是唯一有分辨力的形状。
me_with() { curl -s -m 10 -H "$1" "$GW/api/auth/me"; }
# ⚠️ 请求体一律先收成变量再交给 curl。写成 `-d "{\"k\":\"$V\"}"` 嵌在
# `"$(curl ...)"` 里（也就是 expect 的第三个参数位置）时，那两层引号会被解析掉，
# 服务侧收到的是残缺 JSON → 40000"请求体不是可解析的 JSON"。本轮实测：同一条命令
# 拆成变量就好，且失败时每个请求还炸出两条解析错误。顶层赋值（R=$(curl -d "{...}")）
# 不受影响 —— 这就是仓里其他链路一直写得对、而这一段新代码写错的原因。
DEMO_AUTH="Authorization: Bearer $TOKEN"      # 注意不能用 ${AUTH}：链路 1 起它已被 require_consumer 覆写
expect "当前身份可读，归属取自签名 token（不是请求体自报）" '"identifier":"demo' "$(me_with "$DEMO_AUTH")"
R_BODY="{\"refreshToken\":\"$REFRESH\"}"
ROT=$(curl -s -m 15 -X POST "$GW/api/auth/refresh" -H "$JSON" -d "$R_BODY")
expect "refresh 换到一对新凭证" '"refreshToken"' "$ROT"
NEW_ACCESS=$(echo "$ROT" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
expect "轮换后的新 access 可用" '"code":0' "$(me_with "Authorization: Bearer $NEW_ACCESS")"
ROT_REPLAY=$(curl -s -m 15 -X POST "$GW/api/auth/refresh" -H "$JSON" -d "$R_BODY")
expect "旧 refresh 第二次出现被拒（40100）" '"code":40100' "$ROT_REPLAY"
# 上面那条 40100 分不出"只拒了这一次刷新"和"吊销了整条会话"，所以两条都要断：
expect "重放把整条会话吊销：刚换到的新 access 当场失效（40102）" '"code":40102' \
  "$(me_with "Authorization: Bearer $NEW_ACCESS")"
expect "同一条会话上轮换前的旧 access 一起失效（jti 在轮换中不变）" '"code":40102' \
  "$(me_with "$DEMO_AUTH")"

# 登出：另开一条会话（链路 9 一共只用 3 次登录，登录口是每 IP 5 次/分钟）
L_BODY="{\"identifier\":\"$CID\",\"password\":\"$CPWD\"}"
T2=$(curl -s -m 25 -X POST "$GW/api/auth/login" -H "$JSON" -d "$L_BODY" \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
expect "重新登录后又有了一条有效会话" '"code":0' "$(me_with "Authorization: Bearer $T2")"
curl -s -m 10 -X POST -H "Authorization: Bearer $T2" "$GW/api/auth/logout" >/dev/null
expect "登出后那枚 access 立即失效（40102，不是等 15 分钟自然过期）" '"code":40102' \
  "$(me_with "Authorization: Bearer $T2")"

# 改密：落在链路 6 那个探针账号上。不能拿 demo 做——改种子口令会让下一轮登录直接红。
NEWPW="Smoke-Rot-$(date +%s)"
CP_BODY="{\"oldPassword\":\"Smoke123456\",\"newPassword\":\"$NEWPW\"}"
NEWPW_BODY="{\"identifier\":\"$AUTH7_ID\",\"password\":\"$NEWPW\"}"
OLDPW_BODY="{\"identifier\":\"$AUTH7_ID\",\"password\":\"Smoke123456\"}"
CP_RES=$(curl -s -m 10 -X PUT "$GW/api/auth/password" -H "$AUTH7" -H "$JSON" -d "$CP_BODY")
expect "改密请求被接受" '"code":0' "$CP_RES"
expect "改密作废该账号全部会话：手里那枚 access 当场 40102" '"code":40102' "$(me_with "$AUTH7")"
LOGIN_NEW=$(curl -s -m 25 -X POST "$GW/api/auth/login" -H "$JSON" -d "$NEWPW_BODY")
expect "新口令能登录（改的是哈希，不只是把人踢下线）" '"accessToken"' "$LOGIN_NEW"
LOGIN_OLD=$(curl -s -m 25 -X POST "$GW/api/auth/login" -H "$JSON" -d "$OLDPW_BODY")
expect "旧口令不再能登录，且对外仍是同一句话" '账号或口令不正确' "$LOGIN_OLD"

head2 "收尾：清理在线配置、登出与会话吊销（链路 5 之后才做，全脚本只登录这几次）"
# 清理放在登出之前：登出之后 ADMIN_TOKEN 就作废了，trap 里再删只会静默失败
config_cleanup
LEFT=$(curl -s -m 10 -H "$AAUTH" "$GW/api/admin/config" \
  | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print(sum(len(e['rows']) for e in d['entries']))" \
  2>/dev/null || echo "?")
[ "$LEFT" = "0" ] && ok "本轮写过的真值行已全部清掉（不留 3/s 或 199999 给下一档）" \
  || bad "admin_config 里还留着 $LEFT 行本轮写的覆盖" "left=$LEFT"
# ③：业务侧的写（改预算/改库存/上下线）不直连 admin 的表，走 mkt:audit:pending 投递、
# 由 admin 每 5s drain。跑完这条流必须归零：不归零说明要么没投递、要么没消费——
# 两种都是静默的（业务写仍然 200），所以只能在这里钉。
for _ in $(seq 1 8); do
  [ "$(redis_admin XLEN mkt:audit:pending)" = "0" ] && break
  sleep 2
done
expect "业务侧审计已全部 drain 落表（pending 流归零）" '^0$' "$(redis_admin XLEN mkt:audit:pending)"
# 落了表还不够，得是"本轮这条活动"落了表：只按 action 查会把上一轮的算成本轮成果
expect "本轮活动写动作确实进了审计表" "\"resourceId\":\"$ACT_NO\"" \
  "$(curl -s -m 10 -H "$AAUTH" "$GW/api/admin/audits?action=activity.transition&resourceId=$ACT_NO&page=1&size=1")"

curl -s -m 10 -X POST -H "$AAUTH" "$GW/api/admin/auth/logout" >/dev/null
expect "登出后会话立即失效" '"code":40102' "$(curl -s -m 10 -H "$AAUTH" "$GW/api/admin/users")"

# W5.4（2026-09-30 第二轮复审）：清掉本轮的 smoke-* 演示账号及其业务行。
# 此前每轮冒烟永久留下 4 个 consumer_user + 其券/单/会话行，跨形态共享的 MySQL 卷
# 越积越多（注册限速 10 次/小时还会先撞上）；按标识符前缀清本轮一切痕迹。
# 顺序：先删引用方（user_coupon/seckill_order/consumer_session/event）再删账号行。
SMOKE_UIDS=$(mysql_admin -N -B -e \
  "SELECT GROUP_CONCAT(id) FROM ${ACCOUNT_DB:-marketing}.consumer_user WHERE identifier LIKE 'smoke-%'" \
  2>/dev/null | tr -d '[:space:]')
if [ -n "${SMOKE_UIDS:-}" ]; then
  for stmt in \
    "DELETE FROM ${MYSQL_DB:-marketing}.user_coupon WHERE user_id IN ($SMOKE_UIDS)" \
    "DELETE FROM ${MYSQL_DB:-marketing}.seckill_order WHERE user_id IN ($SMOKE_UIDS)" \
    "DELETE FROM ${ACCOUNT_DB:-marketing}.consumer_session WHERE user_id IN ($SMOKE_UIDS)" \
    "DELETE FROM ${ACCOUNT_DB:-marketing}.consumer_event_log WHERE user_id IN ($SMOKE_UIDS)" \
    "DELETE FROM ${ACCOUNT_DB:-marketing}.consumer_user WHERE id IN ($SMOKE_UIDS)"; do
    mysql_admin -e "$stmt" >/dev/null 2>&1 \
      && ok "冒烟痕迹已清（$stmt 已执行）" \
      || bad "冒烟清理失败（账号堆积将加速注册限速撞顶）" "$stmt"
  done
else
  ok "没有 smoke-% 残留账号（本轮注册可能失败或已被清）"
fi

echo
echo "================ 冒烟结果：通过 $PASS / 失败 $FAIL ================"
[ $FAIL -eq 0 ]
