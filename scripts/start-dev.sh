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

DATA_COMPOSE="$ROOT/docker/docker-compose.data.yml"
# 宿主机中间件端口：Redis 刻意避开 6379，见 docker-compose.dev.yml 注释
DEV_MYSQL_PORT="${DEV_MYSQL_PORT:-3307}"
DEV_REDIS_PORT="${DEV_REDIS_PORT:-6380}"
# 小堆 + SerialGC + 只跑 C1：换更快的启动与更小的常驻，牺牲峰值吞吐
STANDALONE_OPTS="${STANDALONE_OPTS:--Xmx320m -Xss512k -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC -XX:TieredStopAtLevel=1}"
GATEWAY_OPTS="${GATEWAY_OPTS:--Xmx192m -Xss512k -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1}"

if [ "${1:-}" != "--no-build" ]; then
  echo "==> mvn package（standalone + gateway）"
  mvn -q -DskipTests package -pl marketing-standalone,marketing-gateway -am
fi

# 宿主机若已有 redis-server/mysqld 占住这两个端口，容器发布会被遮蔽、进程会连错实例
assert_port_not_shadowed "$DEV_MYSQL_PORT"
assert_port_not_shadowed "$DEV_REDIS_PORT"

echo "==> 数据层常驻检查（mysql + redis，与 LITE/FULL 同一份数据）"
docker compose -f "$DATA_COMPOSE" up -d --wait

export MYSQL_PORT="$DEV_MYSQL_PORT" REDIS_PORT="$DEV_REDIS_PORT"
# 必须在启动任何 JVM 之前导出：standalone 里就装着 admin 模块，AdminSecurityConfig 对空密钥
# 是"启动即失败"（宁可起不来也不签一枚谁都能伪造的 admin token），而它在下面第一个
# start_jvm 就会被用到 —— 放在后面等于只给网关配了密钥，聚合进程直接死在健康检查上（实测）。
# dev 档给固定占位值并让 AdminSecurityConfig 为此打 WARN。
export ADMIN_JWT_SECRET="${ADMIN_JWT_SECRET:-dev-only-secret-change-me}"
export CONSUMER_JWT_SECRET="${CONSUMER_JWT_SECRET:-dev-only-consumer-secret-change-me}"
# 与密钥一样必须在启动任何 JVM 之前导出：standalone 与 gateway 要在同一个 form 下解析，
# 否则会出现"业务进程按 LITE 收口、网关按 FULL 放行"这种两半都对但合起来漏水的组合
export DEPLOY_FORM="${DEPLOY_FORM:-DEV}"

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
wait_healthy marketing-standalone 8085 150  # 实测冷启约 100s（本机 JVM + 首次建连接），90s 会假报失败

# 网关四路指向聚合进程（standalone 不读这些变量）
export ACTIVITY_HOST=127.0.0.1 COUPON_HOST=127.0.0.1 DISCOUNT_HOST=127.0.0.1 SECKILL_HOST=127.0.0.1
export ACTIVITY_PORT=8085 COUPON_PORT=8085 DISCOUNT_PORT=8085 SECKILL_PORT=8085
# 后台与 LITE 同进程：路由指向 standalone 的 8085
export ADMIN_HOST=127.0.0.1 ADMIN_PORT=8085
# 账号服务同理：dev 下它聚在 standalone 里，8087 只是 FULL 进程形态的默认端口。
# 漏这两行的表现与当年漏 ADMIN_HOST 完全同形：POST /api/auth/login 被网关打到
# 127.0.0.1:8087（那里没人听）→ 500 Connection refused，而冒烟在第一步就停。
export ACCOUNT_HOST=127.0.0.1 ACCOUNT_PORT=8085
start_jvm marketing-gateway "$ROOT/marketing-gateway/target/marketing-gateway-1.0.0-SNAPSHOT-exec.jar" "$GATEWAY_OPTS"
wait_healthy marketing-gateway 8090 60

echo "==> 开发环境就绪。冒烟测试：./scripts/smoke-test.sh"
