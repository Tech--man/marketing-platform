#!/usr/bin/env bash
# ============================================================
# 正式环境形态（Full 拓扑）本机启动：网关 8090 + 四业务服务 8081-8084，5 个 JVM
# 前置：./scripts/start-all.sh 会自动确保数据层；扩容档中间件另需
#       docker compose -f docker/docker-compose.prod.yml up -d
# 用法：./scripts/start-all.sh [服务名...]        不带参数启动全部（local 静态路由）
#       PROFILES=nacos ./scripts/start-all.sh     注册中心模式：注册发现 + lb:// 路由
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

# PROFILES=nacos → 注册到 Nacos 且网关改用 lb:// 服务发现路由（多实例扩容的前置）；
# 留空则是默认的 local 静态路由（按端口直连），单实例够用、启动更快
PROFILES="${PROFILES:-}"
# 必须是普通字符串而不是数组：macOS 自带 bash 3.2 在 set -u 下展开空数组
# ("${APP_ARGS[@]}") 会直接报 unbound variable 并放弃整条 java 命令，
# 表现为"pid 写了、进程没起"。空串按词分割后就是零个参数，正是我们要的。
APP_ARGS=""
[ -n "$PROFILES" ] && APP_ARGS="--spring.profiles.active=$PROFILES"

# 中间件宿主机端口（与 dev 形态同一套，两套互斥）；Redis 避开 6379 见 compose 注释
export MYSQL_PORT="${MYSQL_PORT:-3307}" REDIS_PORT="${REDIS_PORT:-6380}"
# 默认与 LITE 共用同一个库 → 两档原地双向切换不需要搬数据
# （7 张业务表表名全局唯一、idempotent_record/local_message 列定义一致，共用一库零冲突）
# 想要"每服务一库"的生产隔离档：逐服务导出 MYSQL_DB=marketing_<模块> 并改用 docker/mysql/init
export MYSQL_DB="${MYSQL_DB:-marketing}"

ALL_SERVICES=(marketing-gateway marketing-activity marketing-coupon marketing-discount marketing-seckill)
SERVICES=("$@")
[ ${#SERVICES[@]} -eq 0 ] && SERVICES=("${ALL_SERVICES[@]}")

assert_port_not_shadowed "$MYSQL_PORT"
assert_port_not_shadowed "$REDIS_PORT"

echo "==> 数据层常驻检查（与 LITE 同一份数据，支持原地双向切换）"
docker compose -f "$ROOT/docker/docker-compose.data.yml" up -d --wait

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
  nohup java $JAVA_OPTS -jar "$jar" $APP_ARGS > "$LOG_DIR/$name.log" 2>&1 &
  echo $! > "$pid_file"
  echo "==> $name 已启动 pid $(cat "$pid_file")，日志 logs/$name.log"
}

# 本机进程形态的端口是静态路由的依据：只等本次真正拉起的那几个服务
port_of() {
  case $1 in
    marketing-gateway) echo 8090 ;;
    marketing-activity) echo 8081 ;;
    marketing-coupon) echo 8082 ;;
    marketing-discount) echo 8083 ;;
    marketing-seckill) echo 8084 ;;
  esac
}

for s in "${SERVICES[@]}"; do start_one "$s"; done

echo "==> 等待健康检查 ..."
PIDS=()
for s in "${SERVICES[@]}"; do
  wait_healthy "$s" "$(port_of "$s")" &
  PIDS+=("$!")
done
rc=0
for p in "${PIDS[@]}"; do wait "$p" || rc=1; done
if [ $rc -ne 0 ]; then
  echo "!! 有服务未就绪，冒烟测试不要执行：看 logs/<服务>.log，再 ./scripts/stop-all.sh 清理" >&2
  exit 1
fi

echo "==> 全部就绪。冒烟测试：./scripts/smoke-test.sh"
