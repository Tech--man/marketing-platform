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
# C 端身份现在是真账号：共享 demo token 那一层已删除，压测必须先登录。
CID="${CONSUMER_IDENTIFIER:-demo}"
CPWD="${CONSUMER_PASSWORD:-demo123456}"
TOKEN=$(curl -s -m 25 -X POST "$GW/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"identifier\":\"$CID\",\"password\":\"$CPWD\"}" \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
# 取不到 token 就停：否则整轮压测会打出 100% 的 401，曲线看着像"系统被打穿了"，
# 真因只是没登录
[ -n "$TOKEN" ] || { echo "!! C 端登录失败（$GW/api/auth/login，identifier=$CID）—— 压测无法继续" >&2; exit 1; }
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
  # 必须同时留 HTTP 码和业务码。只留 HTTP 码的话，"已超过单人限领"(41000) 会被算成受理
  # —— 平台对所有业务失败都回 HTTP 200 + 信封错误码，于是 accepted 虚高，
  # 下面那句"等 persisted 追上 accepted"就会空转到 900 秒超时。
  # 单账号身份时代这条从假设变成了日常：额度是每人一份的，用完就全是 41000。
  local out http
  out=$(curl -s -m 20 -w '\n%{http_code}' -X POST "$GW/api/coupon/grant" \
    -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"requestId\":\"$RUN-$1\",\"templateNo\":\"$TPL\"}")
  http=$(echo "$out" | tail -1)
  echo "$http $(echo "$out" | head -1 | sed -n 's/.*"code":\([0-9]*\).*/\1/p')"
}

# 身份改成单账号之后，per_user_limit 直接给并发量封顶：所有请求都是同一个人，
# 超过限领的那部分是 41000 —— 而它回的是 HTTP 200 + 信封里的错误码，下面按
# `^200$` 统计的"受理数"会把它一起算进去，于是"异步排空能力"被读成"排空得真快"。
# 上一版靠每个 worker 自报一个不同 userId 绕开，现在自报不改变得了，
# 所以这道护栏必须显式存在：宁可不出数，也不出一条假的曲线。
LIMIT_NEED="$N"
PL=$(docker exec "$MYSQL_C" mysql --default-character-set=utf8mb4 -umarketing -pmarketing123 -N -e \
  "SELECT per_user_limit FROM $SCHEMA.coupon_template WHERE template_no='$TPL';" 2>/dev/null | tr -d '[:space:]')
if [ -z "$PL" ]; then
  echo "!! 查不到模板 $TPL 的 per_user_limit（$SCHEMA.coupon_template）—— 无法判断这轮压测能不能测到东西" >&2
  exit 1
fi
if [ "$PL" -lt "$LIMIT_NEED" ]; then
  echo "!! 模板 $TPL 的 per_user_limit=$PL < 每轮请求数=$LIMIT_NEED" >&2
  echo "   单账号身份下超出的请求会被同步判 41000、根本不进队列，排空曲线是假的。" >&2
  echo "   用后台 API 建一张 per_user_limit>=$LIMIT_NEED 的专用压测模板，再以 TPL=<新模板号> 重跑；" >&2
  echo "   或把每轮请求数收到 $PL 以内（那测的是限领拦截，不是异步排空能力）。" >&2
  exit 1
fi
echo "   压测模板 $TPL per_user_limit=$PL >= 每轮 $LIMIT_NEED，继续"

export -f grant
for r in $(seq 1 "$ROUNDS"); do
  RUN="PROBE$(date +%s)$r"
  export RUN
  T0=$(now)
  seq 1 "$N" | xargs -P "$PAR" -I@ bash -c 'grant @' > /tmp/probe-codes.$r
  T1=$(now)
  L=$(xl)
  # 受理 = HTTP 200 且业务码 0。两者都要：42900 是 HTTP 429，41000 是 HTTP 200 + 业务码
  OK=$(awk '$2=="0"{c++} END{print c+0}' /tmp/probe-codes.$r)
  if [ "$OK" = "0" ]; then
    # 一条都没受理就别等排空：等 900 秒只会得到一条"排空很慢"的假结论。
    echo "!! 本轮 0 条受理（$N 发全被同步拒绝）。单账号身份下最常见的原因是" >&2
    echo "   这个登录名已经把模板 $TPL 的每人限领额度用完了 —— 换 CONSUMER_IDENTIFIER" >&2
    echo "   指向一个新账号，或建一张 per_user_limit 更大且未消耗的专用压测模板。" >&2
    echo "   业务码分布: $(awk '{print $2}' /tmp/probe-codes.$r | sort | uniq -c | tr '\n' ' ')" >&2
    exit 1
  fi
  for _ in $(seq 1 900); do [ "$(persisted)" -ge "$OK" ] && break; sleep 1; done
  T2=$(now)
  python3 - "$N" "$T0" "$T1" "$L" "$T2" "$r" /tmp/probe-codes.$r <<'PY'
import sys, collections
n = int(sys.argv[1]); t0, t1 = float(sys.argv[2]), float(sys.argv[3]); L = int(sys.argv[4]); t2 = float(sys.argv[5]); rnd = sys.argv[6]
codes = collections.Counter(l.strip() for l in open(sys.argv[7]) if l.strip())
# 每行是 "<http> <业务码>"；受理 = "200 0"
ok = codes.get('200 0', 0)
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
