#!/usr/bin/env bash
# ============================================================
# 复位演示容量：只调秒杀活动库存，**不删任何业务数据**
#   冒烟与压测会持续吃掉 seckill 库存（sold_stock 涨到 total_stock 后开始返回"已售罄"），
#   本脚本把 total_stock 抬到指定值并清掉 Redis 分桶键，让应用重启后按
#   （换库布局时必须跑一次：单库 ↔ 每服务一库之间分桶键是共用的、sold_stock 各算各的，
#    不重置的话恒等式 "余量+已售==总库存" 会因为上一布局的消费记录对不上而红。）
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
  # 必须先记下旧启动时刻：docker restart 返回时旧 JVM 可能还在响应 /actuator/health，
  # 只看健康检查会"提前放行"，冒烟就撞上新 JVM 的启动窗口（实测 500 connection refused）。
  before=$(docker inspect -f '{{.State.StartedAt}}' mkt-preview-standalone)
  docker restart mkt-preview-standalone >/dev/null
  echo "    已重启 mkt-preview-standalone（LITE 服役档）"
  for _ in $(seq 1 90); do
    now=$(docker inspect -f '{{.State.StartedAt}}' mkt-preview-standalone)
    if [ "$now" != "$before" ] \
       && curl -fs --max-time 3 http://127.0.0.1:8085/actuator/health 2>/dev/null | grep -q '"UP"'; then
      break
    fi
    sleep 1
  done
elif sk=$(docker ps --format '{{.Names}}' | grep -m1 -E '(^|-)marketing-seckill-[0-9]+$'); then
  # FULL 容器形态：应用容器不发布端口（多副本设计），就绪只能看"分桶键是否被重建"。
  # 容器名按后缀匹配：项目名前缀随 compose 的 name 变（现在是 mkt-full-）
  docker restart "$sk" >/dev/null
  echo "    已重启 $sk（FULL 容器形态）"
  for _ in $(seq 1 90); do
    n=$(docker exec "$REDIS" sh -c "redis-cli --scan --pattern 'seckill:stock:${ACT}:*' | wc -l" | tr -d '\r')
    [ "${n:-0}" -gt 0 ] && { echo "    分桶已重建（键数 $n）"; break; }
    sleep 1
  done
elif [ -f "$PWD/run/marketing-seckill.pid" ] && kill -0 "$(cat "$PWD/run/marketing-seckill.pid")" 2>/dev/null; then
  # FULL 进程形态：应用是宿主机 JVM，没有容器可 restart。复用 start-all.sh 的单服务启动，
  # 只重启持有分桶预热的那个服务（SKIP_BUILD=1 沿用刚构建好的 jar）。
  echo "    重启宿主机 marketing-seckill 进程（FULL 进程形态）"
  kill "$(cat "$PWD/run/marketing-seckill.pid")" 2>/dev/null || true
  sleep 3
  SKIP_BUILD=1 ./scripts/start-all.sh marketing-seckill >/dev/null
  for _ in $(seq 1 60); do
    n=$(docker exec "$REDIS" sh -c "redis-cli --scan --pattern 'seckill:stock:${ACT}:*' | wc -l" | tr -d '\r')
    [ "${n:-0}" -gt 0 ] && { echo "    分桶已重建（键数 $n）"; break; }
    sleep 1
  done
else
  # 走到这里说明既没有可重启的容器、也没有本仓库启动的进程。此时分桶键已被删掉，
  # 栈是"冷"的——必须非零退出：上一版这里只打两行提示就 0 退出，实测把 FULL 进程形态
  # 留成了 41007「秒杀库存未预热」+ 冒烟整片红，而退出码看起来一切正常。
  echo "!! 找不到可重启的应用侧（容器与 run/marketing-seckill.pid 都不存在）" >&2
  echo "   分桶键已删除但未重建，请手工启动应用侧后再跑冒烟" >&2
  exit 1
fi

echo "==> 复位后状态："
docker exec "$REDIS" sh -c "redis-cli --scan --pattern 'seckill:stock:${ACT}:*' | xargs -r redis-cli MGET | awk '{s+=\$1} END {print \"  Redis 分桶余量合计=\" s+0}'"
