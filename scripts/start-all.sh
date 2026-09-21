#!/usr/bin/env bash
# ============================================================
# 正式环境形态（Full 拓扑）本机启动：网关 8090 + 四业务服务 8081-8084，5 个 JVM
# 前置：docker compose -f docker/docker-compose.prod.yml up -d
# 用法：./scripts/start-all.sh [服务名...]   不带参数启动全部
# 说明：日常改代码请用 ./scripts/start-dev.sh（2 个 JVM，内存小一个数量级）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."
source "$(dirname "$0")/common.sh"

ROOT="$(pwd)"
LOG_DIR="$ROOT/logs"
RUN_DIR="$ROOT/run"
mkdir -p "$LOG_DIR" "$RUN_DIR"

# 每个服务一份独立堆：不给定则 JVM 默认按物理内存 1/4 取堆，5 个进程会失控
JAVA_OPTS="${JAVA_OPTS:--Xmx512m -XX:MaxMetaspaceSize=256m}"

# 中间件宿主机端口（与 dev 形态同一套，两套互斥）；Redis 避开 6379 见 compose 注释
export MYSQL_PORT="${MYSQL_PORT:-3307}" REDIS_PORT="${REDIS_PORT:-6380}"

ALL_SERVICES=(marketing-gateway marketing-activity marketing-coupon marketing-discount marketing-seckill)
SERVICES=("$@")
[ ${#SERVICES[@]} -eq 0 ] && SERVICES=("${ALL_SERVICES[@]}")

assert_port_not_shadowed "$MYSQL_PORT"
assert_port_not_shadowed "$REDIS_PORT"

# 先构建（跳过测试，测试已有独立阶段）
echo "==> mvn package（首次构建约 1-2 分钟）"
mvn -q -DskipTests package

start_one() {
  local name=$1
  local jar="$ROOT/$name/target/$name-1.0.0-SNAPSHOT-exec.jar"
  local pid_file="$RUN_DIR/$name.pid"
  if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
    echo "==> $name 已在运行 (pid $(cat "$pid_file"))，跳过"
    return
  fi
  if [ ! -f "$jar" ]; then
    echo "!! 未找到 ${jar}，请先执行 mvn package" >&2
    exit 1
  fi
  nohup java $JAVA_OPTS -jar "$jar" > "$LOG_DIR/$name.log" 2>&1 &
  echo $! > "$pid_file"
  echo "==> $name 已启动 pid $(cat "$pid_file")，日志 logs/$name.log"
}

for s in "${SERVICES[@]}"; do start_one "$s"; done

echo "==> 等待健康检查 ..."
wait_healthy marketing-gateway 8090 &
wait_healthy marketing-activity 8081 &
wait_healthy marketing-coupon 8082 &
wait_healthy marketing-discount 8083 &
wait_healthy marketing-seckill 8084 &
wait

echo "==> 全部就绪。冒烟测试：./scripts/smoke-test.sh"
