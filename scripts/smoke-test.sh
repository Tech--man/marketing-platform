#!/usr/bin/env bash
# ============================================================
# 五条核心链路冒烟测试（全部经网关 8090。C 端演示 token 用于运行时读与交易写；
# 配置类写自 ③ 起只存在于 /api/admin/**，链路 0 的创建/流转因此也用后台 token —— 脚本开头登录一次全脚本共用）
# 前置：./scripts/start-all.sh 已就绪；种子数据已由 docker init.sql 写入
# 用法：GATEWAY_TOKEN=xxx ./scripts/smoke-test.sh
# ============================================================
set -uo pipefail

GW="${GW:-http://127.0.0.1:8090}"
TOKEN="${GATEWAY_TOKEN:-demo-token-123}"
AUTH="Authorization: Bearer $TOKEN"
JSON="Content-Type: application/json"
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
expect "创建草稿活动（$ACT_NO）" '"status":"DRAFT"' "$R"
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
R=$(curl -s -H "$AUTH" "$GW/api/activity/$ACT_NO/gray-hit?userId=70001")
expect "未配灰度规则按全量放行" '"data":true' "$R"
R=$(curl -s -H "$AUTH" "$GW/api/activity/ACT2026001/gray-hit?userId=70001")
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
# 每次冒烟用随机用户，避免历史数据触发单人限领
USER_ID=$((880000 + RANDOM % 10000))
R=$(curl -s -X POST "$GW/api/coupon/grant" -H "$AUTH" -H "$JSON" \
  -d "{\"requestId\":\"$REQ_ID\",\"userId\":$USER_ID,\"templateNo\":\"CT2026001\"}")
expect "领券受理（requestId=${REQ_ID}）" '"code":0' "$R"
# 断言针必须与 poll 针一致：poll 超时后照样把最后一次响应打出来，若这里只查
# "couponCode" 这个键名，PROCESSING（couponCode:null）会蒙过去，接着券码取空、
# 核销报 40000"couponCode 必填"——红在错误的行上，看着像核销坏了。
# 券码固定 CP 前缀，针带到 CP 才算是真 SUCCESS；FULL 首次消费要走 RocketMQ 冷启动，给 30s。
R=$(poll "$GW/api/coupon/grant/result/$REQ_ID" '"couponCode":"CP' 30)
expect "轮询到领券 SUCCESS 并返回券码" '"couponCode":"CP' "$R"
COUPON_CODE=$(echo "$R" | sed -n 's/.*"couponCode":"\([^"]*\)".*/\1/p')
R=$(curl -s -H "$AUTH" "$GW/api/coupon/usable?userId=$USER_ID")
expect "用户可用券列表包含新券" "$COUPON_CODE" "$R"
R=$(curl -s -X POST "$GW/api/coupon/consume" -H "$AUTH" -H "$JSON" \
  -d "{\"couponCode\":\"$COUPON_CODE\",\"userId\":$USER_ID,\"orderNo\":\"SO-$(date +%s)\"}")
expect "核销成功" '"code":0' "$R"
R=$(curl -s -X POST "$GW/api/coupon/grant" -H "$AUTH" -H "$JSON" \
  -d "{\"requestId\":\"$REQ_ID\",\"userId\":$USER_ID,\"templateNo\":\"CT2026001\"}")
expect "同 requestId 重复领券幂等回放原受理凭证（不重复扣库存）" 'ACCEPTED' "$R"

head2 "链路 2：优惠计算（满200减30 与 8.5 折叠加 + 行级分摊）"
# 预热必须用**同一个请求体**：首算会因 JIT / 连接冷启动踩到 calcTimeoutMs 返回
# degraded:true（实测 OrbStack 崩溃重启后的第一轮冒烟就挂在这里）。用一个无规则的
# 探针预热是无效的——它不触发本请求要走的规则匹配路径。
# 只重试预热调用，断言仍打最后一次真实响应；重试耗尽仍降级则由 expect 如实判失败。
CALC_BODY='{
  "userId": 88001, "activityNo": null, "userTags": [],
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
# 随机用户段，避免历史防重购标记干扰
SK_BASE=$(( (RANDOM * 32768 + RANDOM) % 8000000 + 1000000 ))
R=$(curl -s -H "$AUTH" "$GW/api/seckill/activities")
expect "在线秒杀活动 SK2026001" 'SK2026001' "$R"
R=$(curl -s -X POST "$GW/api/seckill/grab" -H "$AUTH" -H "$JSON" \
  -d "{\"activityNo\":\"SK2026001\",\"userId\":$SK_BASE}")
expect "抢购受理返回排队 token" '"code":0' "$R"
TK=$(echo "$R" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
R=$(poll "$GW/api/seckill/grab/result/$TK" 'SUCCESS:' 40)
expect "轮询到下单 SUCCESS" 'SUCCESS:' "$R"
ORDER_NO=$(echo "$R" | sed -n 's/.*SUCCESS:\([^",]*\).*/\1/p')
R=$(curl -s -X POST "$GW/api/seckill/pay/$ORDER_NO" -H "$AUTH")
expect "模拟支付回调成功" '"status":"PAID"' "$R"
R=$(curl -s -X POST "$GW/api/seckill/grab" -H "$AUTH" -H "$JSON" \
  -d "{\"activityNo\":\"SK2026001\",\"userId\":$SK_BASE}")
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
  [ "$CONC" -gt 0 ] && ok "库存基线可读（total=$TOTAL sold=$SOLD0 余量=$REMAIN0，本轮并发 $CONC）" \
    || bad "余量为 0，无法做并发防超卖验证；先执行 ./scripts/reset-demo-data.sh" "remain=$REMAIN0"
fi
TMP=$(mktemp -d); trap 'rm -rf "$TMP"; config_cleanup' EXIT
for i in $(seq 1 "$CONC"); do
  ( curl -s -X POST "$GW/api/seckill/grab" -H "$AUTH" -H "$JSON" \
      -d "{\"activityNo\":\"SK2026001\",\"userId\":$((SK_BASE + 100 + i))}" > "$TMP/$i.resp" ) &
done
wait
ACCEPTED=$(grep -l '"code":0' "$TMP"/*.resp 2>/dev/null | wc -l | tr -d ' ')
REJECTED=$(ls "$TMP"/*.resp 2>/dev/null | wc -l | tr -d ' '); REJECTED=$((REJECTED-ACCEPTED))
echo "  受理 $ACCEPTED / 拒绝 ${REJECTED}（售罄或限流）"
[ "$CONC" = "$((ACCEPTED + REJECTED))" ] \
  && ok "受理+拒绝守恒等于并发数（$CONC）" || bad "并发请求丢失应答" "accepted=$ACCEPTED rejected=$REJECTED conc=$CONC"
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
  echo "  DB sold_stock: $SOLD0 → $SOLD1；Redis 余量 $REMAIN；总库存 $TOTAL"
  [ "$((REMAIN + SOLD1))" -eq "$TOTAL" ] \
    && ok "账实一致：分桶余量 + DB 已售 == 总库存（$REMAIN + $SOLD1 == $TOTAL）" \
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
# 生效路径分形态（这是本轮明确划出的边界，不是漏测）：
#   LITE  —— reheater 与后台同进程，能真的刷；
#   FULL  —— admin 进程里没有 reheater，必须"显式报错"而不是静默返回成功。
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
    NOW=$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/$ACT_NO/budget/remain" | sed -n 's/.*"data":\([0-9-]*\).*/\1/p')
    [ "$NOW" = "$RAISED" ] && ok "重预热后 C 端余额等于新口径（$RAISED 分）" || bad "重预热未生效" "$NOW"
  else
    expect "FULL 分进程下重预热显式报错（不静默返回成功）" '"code":41010' "$R"
    expect "报错里点名 owning 服务与待办形态" '③' "$R"
  fi
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
[ "$EFFECTIVE" = "3" ] && ok "另一档（$OTHER_FORM）写 199999 不污染本档 form=$OWN_FORM" \
  || bad "跨形态覆盖串了：本档生效值=$EFFECTIVE（期望 3）" "other=$OTHER_FORM"

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

# 7) 灰度：真值在 DB 列，改 5% 后 ≤8s 生效；删光 Redis 键也不会变成意外全量
if mysql_admin -e "UPDATE ${MYSQL_DB:-marketing}.activity SET gray_percent=5 \
     WHERE activity_no='$ACT_NO'" >/dev/null 2>&1; then
  wait_cfg
  expect "灰度 5% 时尾号命中的用户放行" '"data":true' \
    "$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/$ACT_NO/gray-hit?userId=70001")"
  expect "灰度 5% 时尾号不命中的用户被拒" '"data":false' \
    "$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/$ACT_NO/gray-hit?userId=70050")"
  redis_admin DEL mkt:cfg:snapshot:$OWN_FORM mkt:cfg:version:$OWN_FORM \
    mkt:cfg:snapshot:GLOBAL mkt:cfg:version:GLOBAL >/dev/null
  wait_cfg
  expect "删光 Redis 键后灰度仍是 5%（不是全量放行）" '"data":false' \
    "$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/$ACT_NO/gray-hit?userId=70050")"
  mysql_admin -e "UPDATE ${MYSQL_DB:-marketing}.activity SET gray_percent=NULL \
    WHERE activity_no='$ACT_NO'" >/dev/null 2>&1
else
  bad "无法直连 mkt-mysql 改灰度（链路 5 的灰度断言没跑）" "docker exec 失败"
fi
# ACT2026001 的种子灰度必须还在：链路 0 那两条断言靠的是 DB 列而不是 yml
expect "种子活动 ACT2026001 的灰度仍是 100%" '"data":true' \
  "$(curl -s -m 10 -H "$AUTH" "$GW/api/activity/ACT2026001/gray-hit?userId=70001")"

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

echo
echo "================ 冒烟结果：通过 $PASS / 失败 $FAIL ================"
[ $FAIL -eq 0 ]
