#!/usr/bin/env bash
# ============================================================
# 正式环境形态（Full 拓扑）本机启动：网关 8090 + 四业务服务 8081-8084，5 个 JVM
# 前置：docker + 本机 JDK17。RocketMQ 由本脚本按需拉起（进程形态要 127.0.0.1 广播）；
#       nacos / prometheus 仍可选：docker compose -f docker/docker-compose.prod.yml up -d
# 用法：./scripts/start-all.sh [服务名...]        不带参数启动全部（local 静态路由）
#       PROFILES=nacos ./scripts/start-all.sh     注册中心模式：注册发现 + lb:// 路由
#       MYSQL_DB_PER_SERVICE=1 ./scripts/start-all.sh   每服务一库（四库隔离档，需四库 DDL）
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
# -Duser.timezone：DB 是 serverTimezone=Asia/Shanghai，JVM 走 UTC 主机时
# "LocalDateTime.now() 直比 DATETIME"的到期收口/超时取消判定整体偏 8 小时
# （2026-10-01 审计 P2-2；容器形态已由 Dockerfile ENV TZ 收口，这里收宿主进程形态）
JAVA_OPTS="${JAVA_OPTS:--Xmx512m -XX:MaxMetaspaceSize=256m -Duser.timezone=Asia/Shanghai}"

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
export MYSQL_DB="${MYSQL_DB:-marketing}"
# MYSQL_DB_PER_SERVICE=1 → 每服务一库（marketing_activity / _coupon / _discount / _seckill / _admin），
# 配合 docker/mysql/init 的多库 DDL。这是另一种生产姿态：数据不再与 LITE 共用，
# 也就不能"原地"来回切；隔离的收益是跨模块 join 由 DB 拦住。
MYSQL_DB_PER_SERVICE="${MYSQL_DB_PER_SERVICE:-0}"

ALL_SERVICES=(marketing-gateway marketing-activity marketing-coupon marketing-discount marketing-seckill marketing-account marketing-admin)
SERVICES=("$@")
[ ${#SERVICES[@]} -eq 0 ] && SERVICES=("${ALL_SERVICES[@]}")

# 后台 token 的 HS256 密钥：gateway 与 admin（进程形态）必须同值，否则签得出验不过。
# 这里是 dev 档占位值，AdminSecurityConfig 见到它会打一条 WARN；预览/正式由 compose 显式下发。
export ADMIN_JWT_SECRET="${ADMIN_JWT_SECRET:-dev-only-secret-change-me}"
# 消费者 token 用另一把 dev 占位密钥：与后台同值会让两套凭证互通，dev 档也必须分开，
# 否则"dev 能跑、正式互不通"这种差异会在第一次部署时才暴露。
export CONSUMER_JWT_SECRET="${CONSUMER_JWT_SECRET:-dev-only-consumer-secret-change-me}"
[ "${ADMIN_JWT_SECRET}" = "dev-only-secret-change-me" ] \
  && echo "!! ADMIN_JWT_SECRET 未设置，使用 dev 占位密钥（仅限本机开发）" >&2
[ "${CONSUMER_JWT_SECRET}" = "dev-only-consumer-secret-change-me" ] \
  && echo "!! CONSUMER_JWT_SECRET 未设置，使用 dev 占位密钥（仅限本机开发）" >&2

# 形态标识：在线配置按它分档。进程形态与容器形态同档，所以这里也是 FULL；
# 与密钥一样必须在启动任何 JVM 之前导出（六个服务都在同一个 shell 里起）。
export DEPLOY_FORM="${DEPLOY_FORM:-FULL}"

assert_port_not_shadowed "$MYSQL_PORT"
assert_port_not_shadowed "$REDIS_PORT"

echo "==> 数据层常驻检查（与 LITE 同一份数据，支持原地双向切换）"
docker compose -f "$ROOT/docker/docker-compose.data.yml" up -d --wait

# 迁移接进入口（2026-10-01 审计 P1-1；复审 N-2 补齐本脚本）：FULL 本机形态与
# LITE/dev 共用同一常驻卷，四个部署入口此前只差这一个没串迁移——新 jar 起在未迁移
# 卷上时，SchemaMigrationGuard 会把 JVM 拦在启动期（fail-fast 但不如迁移先行友好；
# 四库隔离档同样覆盖：ALL_DBS 对全部 marketing% 库执行）。
echo "==> 迁移台账（ALL_DBS：对全部 marketing% 库执行未应用迁移）"
ALL_DBS=1 "$(dirname "$0")/migrate.sh"

# 进程形态需要 broker 广播 127.0.0.1；容器形态（deploy-full.sh）挂的是另一份
# broker.container.conf。两边都 --force-recreate broker：bind 挂载钉的是 inode，
# 光改文件内容 compose 认为"没变化"不会重建（今天就被这个坑过一次），而换形态
# 必须让新的广播地址真正生效。本机 broker 没有持久卷，在途消息由本地消息表兜底。
# 只有确实要用本机默认 broker 时才管它（ROCKETMQ_ADDR 指向别处就说明中间件不由本脚本负责）。
if [ -z "${ROCKETMQ_ADDR:-}" ] || [ "${ROCKETMQ_ADDR:-}" = "127.0.0.1:9876" ]; then
  echo "==> 调和扩容档 RocketMQ（进程形态：brokerIP1=127.0.0.1）"
  docker compose -f "$ROOT/docker/docker-compose.prod.yml" up -d --wait --force-recreate rocketmq-broker
fi

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
  # 四库隔离档：逐服务覆盖库名（env 不带赋值时直接 exec，所以关闭时留空即可）
  local svc_db_env=""
  [ "$MYSQL_DB_PER_SERVICE" = "1" ] && svc_db_env="MYSQL_DB=marketing_${name#marketing-}"
  nohup env $svc_db_env java $JAVA_OPTS -jar "$jar" $APP_ARGS > "$LOG_DIR/$name.log" 2>&1 &
  echo $! > "$pid_file"
  echo "==> $name 已启动 pid $(cat "$pid_file")，日志 logs/$name.log"
}

# 本机进程形态的端口是静态路由的依据：只等本次真正拉起的那几个服务
port_of() {
  case $1 in
    # 网关的健康检查端口是独立管理端口 8091（actuator 不再挂在业务入口 8090 上）
    marketing-gateway) echo 8091 ;;
    marketing-activity) echo 8081 ;;
    marketing-coupon) echo 8082 ;;
    marketing-discount) echo 8083 ;;
    marketing-seckill) echo 8084 ;;
    marketing-account) echo 8087 ;;
    marketing-admin) echo 8086 ;;
  esac
}

# 漏分支的代价必须是响亮的一条：空端口会让 wait_healthy 去 curl
# `http://127.0.0.1:/actuator/health`，那不会立刻失败，而是把 150s 的等待跑满
# 再报"未就绪"——加第一个服务时就是这么踩的（marketing-account 进 ALL_SERVICES
# 而 port_of 没跟上）。所以在这里逐个先验一遍，起 JVM 之前就把话说死。
for s in "${SERVICES[@]}"; do
  [ -n "$(port_of "$s")" ] || {
    echo "!! $s 在 port_of() 里没有端口映射：健康检查会拿空端口去 curl，等满 150s 才假报未就绪" >&2
    echo "   新加服务要同时补：ALL_SERVICES、port_of、（若它在 FULL 拓扑里）admin 的 ops.targets" >&2
    exit 1
  }
done

for s in "${SERVICES[@]}"; do start_one "$s"; done

echo "==> 等待健康检查 ..."
PIDS=()
for s in "${SERVICES[@]}"; do
  # 150s 而不是默认 60s：这里可能同时冷启 7 个 JVM（外加抢 MQ/Nacos），
  # 而 admin 的 Hikari 池是首个请求才建的 —— 实测 60s 会假报"未就绪"，进程其实活着。
  wait_healthy "$s" "$(port_of "$s")" 150 &
  PIDS+=("$!")
done
rc=0
for p in "${PIDS[@]}"; do wait "$p" || rc=1; done
if [ $rc -ne 0 ]; then
  echo "!! 有服务未就绪，冒烟测试不要执行：看 logs/<服务>.log，再 ./scripts/stop-all.sh 清理" >&2
  exit 1
fi

echo "==> 全部就绪。冒烟测试：./scripts/smoke-test.sh"
