#!/usr/bin/env bash
# ============================================================
# 开发环境启动：中间件容器（mysql + redis）+ 本机 2 个 JVM
#   · standalone 8085：活动/券/优惠/秒杀四模块聚合单进程，消息走 Redis Stream
#   · gateway 8090：四条路由统一指向 8085
#   不含 RocketMQ / Nacos / Prometheus；内存按"够功能验证"给定，不追求吞吐
# 用法：./scripts/start-dev.sh              起中间件 → 构建 → 起 JVM
#       ./scripts/start-dev.sh --no-build   跳过构建，只拉起 JVM
# 停止：./scripts/stop-dev.sh
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."
source "$(dirname "$0")/common.sh"

ROOT="$(pwd)"
LOG_DIR="$ROOT/logs"
RUN_DIR="$ROOT/run"
mkdir -p "$LOG_DIR" "$RUN_DIR"

DEV_COMPOSE="$ROOT/docker/docker-compose.dev.yml"
# 小堆 + SerialGC + 只跑 C1：换更快的启动与更小的常驻，牺牲峰值吞吐
STANDALONE_OPTS="${STANDALONE_OPTS:--Xmx320m -Xss512k -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC -XX:TieredStopAtLevel=1}"
GATEWAY_OPTS="${GATEWAY_OPTS:--Xmx192m -Xss512k -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1}"

if [ "${1:-}" != "--no-build" ]; then
  echo "==> mvn package（standalone + gateway）"
  mvn -q -DskipTests package -pl marketing-standalone,marketing-gateway -am
fi

echo "==> 启动开发中间件（mysql + redis）并等待健康检查"
docker compose -f "$DEV_COMPOSE" up -d --wait

start_jvm() { # name jar opts
  local name=$1 jar=$2 opts=$3
  local pid_file="$RUN_DIR/$name.pid"
  if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
    echo "==> $name 已在运行 (pid $(cat "$pid_file"))，跳过（重启请先 ./scripts/stop-dev.sh）"
    return
  fi
  if [ ! -f "$jar" ]; then
    echo "!! 未找到 $jar，请先执行 mvn package" >&2
    exit 1
  fi
  nohup java $opts -jar "$jar" > "$LOG_DIR/$name.log" 2>&1 &
  echo $! > "$pid_file"
  echo "==> $name 已启动 pid $(cat "$pid_file")，日志 logs/$name.log"
}

start_jvm marketing-standalone "$ROOT/marketing-standalone/target/marketing-standalone-1.0.0-SNAPSHOT.jar" "$STANDALONE_OPTS"
wait_healthy marketing-standalone 8085 90

# 网关四路指向聚合进程（standalone 不读这些变量）
export ACTIVITY_HOST=127.0.0.1 COUPON_HOST=127.0.0.1 DISCOUNT_HOST=127.0.0.1 SECKILL_HOST=127.0.0.1
export ACTIVITY_PORT=8085 COUPON_PORT=8085 DISCOUNT_PORT=8085 SECKILL_PORT=8085
start_jvm marketing-gateway "$ROOT/marketing-gateway/target/marketing-gateway-1.0.0-SNAPSHOT-exec.jar" "$GATEWAY_OPTS"
wait_healthy marketing-gateway 8090 60

echo "==> 开发环境就绪。冒烟测试：./scripts/smoke-test.sh"
