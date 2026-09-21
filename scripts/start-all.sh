#!/usr/bin/env bash
# ============================================================
# 一键启动五个服务（网关 8090 + 四业务服务 8081-8084）
# 前置：docker compose 中间件已启动（见 docker/README 或 README.md）
# 用法：./scripts/start-all.sh [服务名...]   不带参数启动全部
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

ROOT="$(pwd)"
LOG_DIR="$ROOT/logs"
RUN_DIR="$ROOT/run"
mkdir -p "$LOG_DIR" "$RUN_DIR"

ALL_SERVICES=(marketing-gateway marketing-activity marketing-coupon marketing-discount marketing-seckill)
SERVICES=("$@")
[ ${#SERVICES[@]} -eq 0 ] && SERVICES=("${ALL_SERVICES[@]}")

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
  nohup java -jar "$jar" > "$LOG_DIR/$name.log" 2>&1 &
  echo $! > "$pid_file"
  echo "==> $name 已启动 pid $(cat "$pid_file")，日志 logs/$name.log"
}

wait_healthy() {
  local name=$1 port=$2
  for i in $(seq 1 60); do
    if curl -fs "http://127.0.0.1:$port/actuator/health" 2>/dev/null | grep -q '"UP"'; then
      echo "==> $name 健康检查通过 (:$port)"
      return 0
    fi
    sleep 1
  done
  echo "!! $name 60s 内未就绪，请查看 logs/$name.log" >&2
  return 1
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
