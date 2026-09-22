#!/usr/bin/env bash
# ============================================================
# 三条核心链路冒烟测试（全部经网关 8090，演示级 Bearer Token）
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

# 断言响应包含指定片段
expect() { # desc needle body
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

head2 "链路 0：活动中心（状态机 → 灰度 → 预算）"
# 每次用全新活动编号，创建→流转→预算全程自给自足，不依赖也不消耗共享种子数据
ACT_NO="ACT-SMOKE-$(date +%s)-$RANDOM"
TS_START=$(date -v-1H +%Y-%m-%dT%H:%M:%S 2>/dev/null || date -d '-1 hour' +%Y-%m-%dT%H:%M:%S)
TS_END=$(date -v+2d +%Y-%m-%dT%H:%M:%S 2>/dev/null || date -d '+2 days' +%Y-%m-%dT%H:%M:%S)
R=$(curl -s -X POST "$GW/api/activity" -H "$AUTH" -H "$JSON" \
  -d "{\"activityNo\":\"$ACT_NO\",\"name\":\"冒烟活动\",\"startTime\":\"$TS_START\",\"endTime\":\"$TS_END\",\"budgetAmount\":100.00}")
expect "创建草稿活动（$ACT_NO）" '"status":"DRAFT"' "$R"
R=$(curl -s -H "$AUTH" "$GW/api/activity/$ACT_NO/participatable")
expect "草稿态不可参与" '"data":false' "$R"
R=$(curl -s -X PUT "$GW/api/activity/$ACT_NO/transition?event=APPROVE" -H "$AUTH")
expect "DRAFT 直接 APPROVE 被状态机拒绝（41001）" '"code":41001' "$R"
R=$(curl -s -X PUT "$GW/api/activity/$ACT_NO/transition?event=SUBMIT" -H "$AUTH")
expect "SUBMIT → AUDITING" '"status":"AUDITING"' "$R"
R=$(curl -s -X PUT "$GW/api/activity/$ACT_NO/transition?event=APPROVE" -H "$AUTH")
expect "APPROVE → GRAY" '"status":"GRAY"' "$R"
R=$(curl -s -X PUT "$GW/api/activity/$ACT_NO/transition?event=PROMOTE" -H "$AUTH")
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
R=$(curl -s -X POST "$GW/api/activity" -H "$AUTH" -H "$JSON" \
  -d "{\"activityNo\":\"$ACT_NO\",\"name\":\"重复\",\"startTime\":\"$TS_START\",\"endTime\":\"$TS_END\",\"budgetAmount\":1.00}")
expect "重复活动编号被拒" '"code":41000' "$R"
R=$(curl -s -X PUT "$GW/api/activity/$ACT_NO/transition?event=FINISH" -H "$AUTH")
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
R=$(poll "$GW/api/coupon/grant/result/$REQ_ID" '"couponCode":"' 15)
expect "轮询到领券 SUCCESS 并返回券码" '"couponCode"' "$R"
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
R=$(poll "$GW/api/seckill/grab/result/$TK" 'SUCCESS' 40)
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
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
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

echo
echo "================ 冒烟结果：通过 $PASS / 失败 $FAIL ================"
[ $FAIL -eq 0 ]
