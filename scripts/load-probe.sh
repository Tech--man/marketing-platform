#!/usr/bin/env bash
# ============================================================
# 吞吐探针：量 LITE/FULL 的入口速率与异步消费排空速率
#   用法：./scripts/load-probe.sh [总条数] [入口并发] [重复轮数]
#         ./scripts/load-probe.sh 600 80 3
#   模板号：TPL=CT2026002 ./scripts/load-probe.sh
#
# 三个口径各管什么，以及为什么不能用小样本：
#   · 受理 req/s = 入口侧（含限流）；端到端 msg/s = 受理数 / (发送开始→全部落库)；
#     尾部排空 = 最后一条受理到最后一张券落库的时间，是积压的 latency 表现。
#   · 真正的"消费净速率"只有 LITE 能算：发送结束时的队列深度 / 尾部排空。
#     RocketMQ 的队列深度在 broker 里，客户端读不到，所以那一行改给提示而不是假数。
#   · 排空按 1s 粒度轮询，消费比入口快时分母被采样粒度吃掉（曾把 8 并行消费算成
#     7-14 msg/s 这种假性低值）——所以要给足条数，并把积压大小一起打出来。
#
# 前提：数据层 + 应用侧已启动（LITE 与 FULL 两种通道都能测，排空口径按落库数判定，
#   见 persisted() 注释）。Stream 积压那一列只在 LITE 有值，FULL 恒为 0 属正常。
#   券模板 per_user_limit=1、库存有限，测前先确认余量（必要时 ./scripts/reset-demo-data.sh，
#   ③ 之后它改走后台端点：需要栈在跑 + 种子后台账号，且每次只登一次后台，别和冒烟挤在同一分钟里）
# ============================================================
set -uo pipefail
cd "$(dirname "$0")/.."

GW="${GW:-http://127.0.0.1:8090}"
TOKEN="${GATEWAY_TOKEN:-demo-token-123}"
export GW AUTH="Authorization: Bearer $TOKEN"
export TPL="${TPL:-CT2026001}"
N="${1:-600}"
PAR="${2:-80}"
ROUNDS="${3:-1}"
APP_METRICS="${APP_METRICS:-http://127.0.0.1:8085/actuator/prometheus}"

command -v docker >/dev/null 2>&1 || { echo "需要 docker（用于读 Redis XLEN 与落库计数）" >&2; exit 1; }
pick() { docker ps --format '{{.Names}}' 2>/dev/null | grep -m1 -E "$1" || true; }
REDIS_C="$(pick '^mkt-redis$|^mkt-(preview|dev)-redis$')"
MYSQL_C="$(pick '^mkt-mysql$|^mkt-(preview|dev)-mysql$')"
if [ -z "$REDIS_C" ] || [ -z "$MYSQL_C" ]; then echo "找不到本项目的 Redis / MySQL 容器" >&2; exit 1; fi
KEY=MKT_STREAM_MKT_COUPON_GRANT
# user_coupon 在哪个库：LITE/dev 是单库 marketing，FULL 隔离档是 marketing_coupon
SCHEMA="$(docker exec "$MYSQL_C" mysql -umarketing -pmarketing123 -N -e \
  "SELECT table_schema FROM information_schema.tables WHERE table_name='user_coupon' LIMIT 1;" 2>/dev/null)"
[ -z "$SCHEMA" ] && { echo "在 $MYSQL_C 里找不到 user_coupon 表" >&2; exit 1; }

xl() { docker exec "$REDIS_C" redis-cli XLEN "$KEY" | tr -d '\r\n'; }
# 排空口径用"业务落库数"而不是队列长度：RocketMQ 通道下队列在 broker 里读不到
# （XLEN 恒为 0），只有"受理成功的条数最终变成多少张券"对两种通道同时成立。
persisted() {
  docker exec "$MYSQL_C" mysql -umarketing -pmarketing123 -N -e \
    "SELECT COUNT(*) FROM ${SCHEMA}.user_coupon WHERE request_id LIKE '${RUN}-%';" 2>/dev/null | tr -d '\r\n'
}
now() { python3 -c 'import time;print(f"{time.monotonic():.3f}")'; }
grant() {
  curl -s -o /dev/null -w '%{http_code}\n' -m 20 -X POST "$GW/api/coupon/grant" \
    -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"requestId\":\"$RUN-$1\",\"userId\":$((UBASE+$1)),\"templateNo\":\"$TPL\"}"
}

export -f grant
for r in $(seq 1 "$ROUNDS"); do
  RUN="PROBE$(date +%s)$r"
  # 每轮换一段全新 userId：券模板 per_user_limit=1，复用会被限领拒掉而测不出真实吞吐
  UBASE=$(python3 -c 'import time;print(int(time.time())%9000*1000+100000)')
  export RUN UBASE
  T0=$(now)
  seq 1 "$N" | xargs -P "$PAR" -I@ bash -c 'grant @' > /tmp/probe-codes.$r
  T1=$(now)
  L=$(xl)
  OK=$(grep -c '^200$' /tmp/probe-codes.$r || true)
  for _ in $(seq 1 900); do [ "$(persisted)" -ge "$OK" ] && break; sleep 1; done
  T2=$(now)
  python3 - "$N" "$T0" "$T1" "$L" "$T2" "$r" /tmp/probe-codes.$r <<'PY'
import sys, collections
n = int(sys.argv[1]); t0, t1 = float(sys.argv[2]), float(sys.argv[3]); L = int(sys.argv[4]); t2 = float(sys.argv[5]); rnd = sys.argv[6]
codes = collections.Counter(l.strip() for l in open(sys.argv[7]) if l.strip())
ok = codes.get('200', 0)
send = max(t1 - t0, .001); drain = max(t2 - t1, .001)
print(f"轮 {rnd}: {n} 条 {dict(codes)}（受理 {ok}，其余为限流/业务拒绝）")
print(f"   入口 {send:5.2f}s → 受理 {ok/send:5.0f} req/s ｜ 端到端 {ok/max(t2-t0,.001):5.0f} msg/s"
      f" ｜ 尾部排空 {drain:5.2f}s ｜ 发送结束时 Stream 积压 {L}")
if L:
    # 只有 LITE 读得到队列深度；积压够大时 L/排空 才是真实的消费净速率
    print(f"   净排空 {L/drain:5.0f} msg/s"
          + ("（积压 < 受理数 20%，此值不可信）" if L < ok * 0.2 else ""))
else:
    print("   RocketMQ 通道无队列深度可读：尾部排空 ≤2s 即说明消费跟得上入口，"
          "要看真实消费速率就加大入口并发把积压做出来，或读 broker 侧堆积")
PY
done
