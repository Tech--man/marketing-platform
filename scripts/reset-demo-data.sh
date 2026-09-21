#!/usr/bin/env bash
# ============================================================
# 复位演示容量：只调秒杀活动库存，**不删任何业务数据**
#   冒烟与压测会持续吃掉 seckill 库存（sold_stock 涨到 total_stock 后开始返回"已售罄"），
#   本脚本把 total_stock 抬到指定值并清掉 Redis 分桶键，让应用重启后按
#   warm-up 的 `remain = total - sold` 口径重建，账实保持一致。
# 用法：./scripts/reset-demo-data.sh [新总库存]   默认 5000
# 支持三套形态：自动探测在跑的 mkt-preview-* / mkt-dev-* / mkt-* 容器
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

NEW_TOTAL=${1:-5000}
ACT=SK2026001

pick() { docker ps --format '{{.Names}}' 2>/dev/null | grep -m1 -E "$1" || true; }
MYSQL="$(pick '^mkt-(preview|dev)-mysql$|^mkt-mysql$')"
REDIS="$(pick '^mkt-(preview|dev)-redis$|^mkt-redis$')"

if [ -z "$MYSQL" ] || [ -z "$REDIS" ]; then
  echo "!! 没有同时探测到本项目的 MySQL 与 Redis 容器，无法复位" >&2
  echo "   当前在跑：$(docker ps --format '{{.Names}}' | tr '\n' ' ')" >&2
  exit 1
fi

echo "==> 目标容器：$MYSQL / $REDIS"
# 表所在库随形态不同：LITE/dev 是单库 marketing，FULL 是 marketing_seckill —— 查出来再用
SCHEMA="$(docker exec "$MYSQL" mysql -umarketing -pmarketing123 -N -e \
  "SELECT table_schema FROM information_schema.tables WHERE table_name='seckill_activity' LIMIT 1;" 2>/dev/null)"
if [ -z "$SCHEMA" ]; then echo "!! 在 $MYSQL 里找不到 seckill_activity 表" >&2; exit 1; fi
echo "==> 秒杀表所在库：$SCHEMA"
docker exec "$MYSQL" mysql -umarketing -pmarketing123 -e \
  "UPDATE ${SCHEMA}.seckill_activity SET total_stock=${NEW_TOTAL} WHERE activity_no='${ACT}';" 2>/dev/null

# 只删分桶键：SETNX 语义下不删就永远不会按新库存重建。
# 不动 seckill:bought:*（防重购标记）——冒烟每轮用随机用户段，本就不冲突；删掉反而会让
# 老用户重新扣一次桶却在 DB 撞唯一索引，白白造成"桶比实际少"的保守漂移。
docker exec "$REDIS" sh -c "
  redis-cli --scan --pattern 'seckill:stock:${ACT}:*' | xargs -r redis-cli DEL
" | tr -d '\r' | xargs echo "==> 已清理分桶键数："

echo "==> 预热只在应用启动时按 (total - sold) 重建分桶，需要重启应用侧："
if docker ps --format '{{.Names}}' | grep -q '^mkt-preview-standalone$'; then
  docker restart mkt-preview-standalone >/dev/null
  echo "    已重启 mkt-preview-standalone（LITE 服役档）"
  for _ in $(seq 1 60); do
    curl -fs --max-time 3 http://127.0.0.1:8085/actuator/health 2>/dev/null | grep -q '"UP"' && break
    sleep 1
  done
else
  echo "    dev 形态：./scripts/stop-dev.sh && ./scripts/start-dev.sh --no-build" >&2
  echo "    FULL 形态：./scripts/stop-all.sh && ./scripts/start-all.sh marketing-seckill" >&2
fi

echo "==> 复位后状态："
docker exec "$REDIS" sh -c "redis-cli --scan --pattern 'seckill:stock:${ACT}:*' | xargs -r redis-cli MGET | awk '{s+=\$1} END {print \"  Redis 分桶余量合计=\" s+0}'"
