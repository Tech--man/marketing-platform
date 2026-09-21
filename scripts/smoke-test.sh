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
# 预热一次：首算可能因 JIT/连接冷启动超时降级，不作为断言
curl -s -o /dev/null -X POST "$GW/api/discount/calculate" -H "$AUTH" -H "$JSON" -d '{"userId":1,"activityNo":null,"userTags":[],"items":[{"lineId":"L1","skuId":1,"itemId":1,"tags":[],"unitPrice":1.00,"quantity":1}]}'
R=$(curl -s -X POST "$GW/api/discount/calculate" -H "$AUTH" -H "$JSON" -d '{
  "userId": 88001, "activityNo": null, "userTags": [],
  "items": [
    {"lineId":"L1","skuId":9001,"itemId":7001,"tags":["DIGITAL"],"unitPrice":199.90,"quantity":1},
    {"lineId":"L2","skuId":9002,"itemId":7002,"tags":["CLOTHES"],"unitPrice":100.00,"quantity":1}
  ]}')
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

head2 "链路 3+：并发防超卖（60 个不同用户并发抢购）"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
for i in $(seq 1 60); do
  ( curl -s -X POST "$GW/api/seckill/grab" -H "$AUTH" -H "$JSON" \
      -d "{\"activityNo\":\"SK2026001\",\"userId\":$((SK_BASE + 100 + i))}" > "$TMP/$i.resp" ) &
done
wait
ACCEPTED=$(grep -l '"code":0' "$TMP"/*.resp 2>/dev/null | wc -l | tr -d ' ')
REJECTED=$(ls "$TMP"/*.resp | wc -l | tr -d ' '); REJECTED=$((REJECTED-ACCEPTED))
echo "  受理 $ACCEPTED / 拒绝 ${REJECTED}（售罄或限流）"
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
  OK_ALL=0; echo "  用户 #${item%%:*} 超时未成交(token=${item#*:})"
done
[ $OK_ALL -eq 1 ] && ok "全部受理请求最终下单成功（异步链路闭环）" || bad "存在受理未成交" ""
STOCK=$(curl -s -H "$AUTH" "$GW/api/seckill/stock/SK2026001" | sed -n 's/.*"data":\[\([^]]*\)\].*/\1/p')
REMAIN=$(python3 -c "print(sum([int(x) for x in '$STOCK'.split(',') if x.strip()]))" 2>/dev/null || echo "?")
echo "  Redis 分桶剩余合计: ${REMAIN}（初始 200，已扣 $(python3 -c "print(200-($REMAIN))" 2>/dev/null || echo '?')）"
[ "$REMAIN" != "?" ] && [ "$REMAIN" -ge 0 ] \
  && ok "库存无超卖（剩余 >= 0）" || bad "库存异常" "$STOCK"

echo
echo "================ 冒烟结果：通过 $PASS / 失败 $FAIL ================"
[ $FAIL -eq 0 ]
