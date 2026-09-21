#!/usr/bin/env bash
# ============================================================
# 吞吐探针：量 LITE/FULL 的入口速率与异步消费排空速率
#   用法：./scripts/load-probe.sh [总条数] [入口并发] [重复轮数]
#         ./scripts/load-probe.sh 600 80 3
#   模板号：TPL=CT2026002 ./scripts/load-probe.sh
#
# 为什么把"发送"和"排空"分开测（以及为什么不能用小样本）：
#   消费净速率 = 发送结束时的积压 / 排空耗时。若入口比消费快，积压很小，
#   分母被 1s 级采样粒度支配，读数会假性偏低（实测把 8 并行消费算成 7-14 msg/s）。
#   所以要么给足条数让积压必然形成，要么只看端到端。本脚本两个都给。
#
# 前提：数据层 + 应用侧已启动。"消费净速率"依赖读 Redis Stream 长度，
#   因此仅在 LITE（redis-stream 通道）下有意义；FULL 形态该 key 恒为 0，只看端到端一行。
#   券模板 per_user_limit=1、库存有限，测前先确认余量（必要时 ./scripts/reset-demo-data.sh）
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

command -v docker >/dev/null 2>&1 || { echo "需要 docker（用于读 Redis XLEN）" >&2; exit 1; }
REDIS_C="$(docker ps --format '{{.Names}}' | grep -m1 -E '^mkt-redis$|^mkt-(preview|dev)-redis$' || true)"
[ -z "$REDIS_C" ] && { echo "找不到本项目 Redis 容器" >&2; exit 1; }
KEY=MKT_STREAM_MKT_COUPON_GRANT

xl() { docker exec "$REDIS_C" redis-cli XLEN "$KEY" | tr -d '\r\n'; }
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
  for _ in $(seq 1 900); do [ "$(xl)" = "0" ] && break; sleep 1; done
  T2=$(now)
  python3 - "$N" "$T0" "$T1" "$L" "$T2" "$r" /tmp/probe-codes.$r <<'PY'
import sys, collections
n = int(sys.argv[1]); t0, t1 = float(sys.argv[2]), float(sys.argv[3]); L = int(sys.argv[4]); t2 = float(sys.argv[5]); rnd = sys.argv[6]
codes = collections.Counter(l.strip() for l in open(sys.argv[7]) if l.strip())
send = max(t1 - t0, .001); drain = max(t2 - t1, .001)
print(f"轮 {rnd}: {n} 条 {dict(codes)}")
print(f"   入口 {send:6.2f}s → {n/send:6.0f} msg/s ｜ 发送结束积压 {L}"
      f" ｜ 排空 {drain:6.2f}s → 消费 {L/drain:6.0f} msg/s ｜ 端到端 {n/(t2-t0):5.0f} msg/s")
if L < n * 0.2:
    print("   ⚠ 积压不足总量 20%：消费快于入口，'消费净速率'不可信，只看端到端或加大并发")
PY
done
