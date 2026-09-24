#!/usr/bin/env bash
# ============================================================
# FULL 扩容档 · 容器化部署（一容器一服务，可 --scale 多副本）
#   与本机进程形态（scripts/start-all.sh）二选一，二者都依赖同一常驻数据层
# 用法：./scripts/deploy-full.sh [docker compose up 的额外参数]
#       ./scripts/deploy-full.sh --scale marketing-discount=3
#       SKIP_BUILD=1 ./scripts/deploy-full.sh        跳过 mvn 构建，并且复用已有 :full 镜像
# 前置：docker + 已 mvn package（SKIP_BUILD=1 时改用现成镜像，改了 Java 代码就别用它）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."
source "$(dirname "$0")/common.sh"

DATA="$PWD/docker/docker-compose.data.yml"
MW="$PWD/docker/docker-compose.prod.yml"
# 容器形态的 broker 广播地址覆盖层（详见该文件头注释）
MW_C="$PWD/docker/docker-compose.prod.container.yml"
APP="$PWD/docker/docker-compose.full-app.yml"
# 后台 token 密钥必须显式导出：不放在 compose 的 :? 里，是因为那连 down/ps 都要求值，
# 漏配时"把栈停掉"都会失败。在入口拦，报错更早也更准。
if [ -z "${ADMIN_JWT_SECRET:-}" ]; then
  echo "!! 请先导出 ADMIN_JWT_SECRET（gateway 与 marketing-admin 必须同值）" >&2
  echo "   例：export ADMIN_JWT_SECRET=\$(openssl rand -base64 32)" >&2
  exit 1
fi

if docker ps --format '{{.Names}}' | grep -q '^mkt-preview-standalone$'; then
  echo "!! LITE 形态正在占用 8090/8085，先执行 ./scripts/stop-preview.sh" >&2
  exit 1
fi

# 本机进程形态还在跑时同样拒绝：macOS 上容器把 8090 发布出去并不报错（OrbStack 走 VM 转发），
# 于是冒烟实际打到宿主机那套 JVM 上——形态记成"容器"、测的却是"进程"，五形态那张表整列作废
# （复跑时踩过一次，靠 lsof 对端口才发现）。
for pid_file in run/*.pid; do
  [ -e "$pid_file" ] || continue
  pid=$(cat "$pid_file")
  if kill -0 "$pid" 2>/dev/null; then
    echo "!! 本机进程形态还在跑（$(basename "$pid_file" .pid) pid $pid），先执行 ./scripts/stop-all.sh" >&2
    exit 1
  fi
done

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "==> mvn package（全部模块）"
  mvn -q -DskipTests package
fi

# SKIP_BUILD=1 时连镜像也不重建：compose 的 --build 会重新 COPY 一层 fat jar，
# 实测一次全量重建多占 ~2 GB，而 broker 与 MySQL 的镜像层就写在这同一块盘上——
# 盘涨到 90% 以上时 broker 直接以 CODE:14 拒写，异步链路会"看起来坏了"。
# 用普通字符串而非数组：macOS 自带 bash 3.2 在 set -u 下展开空数组会直接报错。
BUILD_FLAG="--build"
[ "${SKIP_BUILD:-0}" = "1" ] && BUILD_FLAG=""

echo "==> 数据层 + 扩容档中间件（nacos / rocketmq / prometheus，broker 用容器形态广播地址）"
docker compose -f "$DATA" up -d --wait
docker compose -f "$MW" -f "$MW_C" up -d --wait
# 单独重建 broker：bind 挂载钉 inode，只改 conf 内容时 compose 不会认为服务有变化（详见 start-all.sh 同处注释）
docker compose -f "$MW" -f "$MW_C" up -d --wait --force-recreate rocketmq-broker

# 起应用前先报 broker 的磁盘水位：分区使用率 ≥90% 时它以 CODE:14 service not available 拒写，
# 症状和"异步链路坏了"完全一样（实测被这个坑过一整轮排查）。
broker_used=$(docker exec mkt-rocketmq-broker df -P / 2>/dev/null | awk 'NR==2 {gsub("%","",$5); print $5}')
if [ -n "${broker_used:-}" ] && [ "$broker_used" -ge 85 ]; then
  echo "!! broker 分区已用 ${broker_used}%：≥90% 会拒写异步投递（消息退回本地消息表，不丢但延迟）。" >&2
  echo "   先回收空间：docker builder prune -f（实测一次释放 3.4 GB）" >&2
fi

echo "==> ${BUILD_FLAG:+构建并}启动 FULL 应用栈"
docker compose -f "$APP" up -d $BUILD_FLAG "$@"

echo "==> 等待网关就绪（服务启动 + 注册进 nacos 需要一点时间）"
wait_healthy marketing-gateway 8090 180

# 网关自身健康 ≠ 五条路由都能服务：Spring Cloud Gateway 是**首次命中**某条 lb:// 路由时
# 才去 nacos 订阅该服务，订阅+实例推送到位前请求会被回 503（空响应体）。实测部署后立刻跑
# 冒烟会吃到 28 条"C 端全红"的假失败——排查方向还长得像异步链路坏了，代价很大。
# 所以这里按路由各打一发真实业务请求，等到 `"code":0` 才算就绪。
echo "==> 等待五条路由真正可服务（服务发现订阅是懒加载的）"
CT="Authorization: Bearer ${GATEWAY_TOKEN:-demo-token-123}"
wait_route() { # url desc 期望片段
  local url=$1 desc=$2 want=$3 body=""
  for _ in $(seq 1 60); do
    body=$(curl -s -m 5 -H "$CT" "http://127.0.0.1:8090$url")
    if echo "$body" | grep -q "$want"; then
      echo "    $desc 可服务"
      return 0
    fi
    sleep 2
  done
  # 把末次响应带出来：路径漂了（接口改名/404）与订阅没到位是两种病，
  # 只看"没返回预期响应"会让人去查服务发现（实测这样红过一次，真因是探针路径已不存在）
  echo "!! $desc 在 120s 内没有返回预期响应（$url），末次响应: $(echo "$body" | head -c 160)" >&2
  return 1
}
# ③：业务后台前缀要带 admin token（C 端 demo token 打 /api/admin/** 必 40100）
# 登录响应原样回显给调用方判读，只回 token 不够："服务还没起来"和"口令错/被限速"
# 都是空串，而前者该等、后者该停 —— 后者继续重试会烧掉 LoginGuard 每分钟十次的额度，
# 把紧接着跑的冒烟变成限速测试（它自己要登四次）。
admin_login() {
  curl -s -m 25 -X POST http://127.0.0.1:8090/api/admin/auth/login \
    -H "Content-Type: application/json" -d '{"username":"admin","password":"rootdev123"}'
}
token_of() { echo "$1" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'; }

ADMIN_BOOT_TOKEN=""
relogin_left=3
wait_route_admin() { # url desc 期望片段
  local url=$1 desc=$2 want=$3 body=""
  for _ in $(seq 1 60); do
    body=$(curl -s -m 5 -H "Authorization: Bearer ${ADMIN_BOOT_TOKEN}" "http://127.0.0.1:8090$url")
    if echo "$body" | grep -q "$want"; then
      echo "    $desc 可服务"
      return 0
    fi
    # 凭证半路失效（token 过期，或补登时 admin 才刚起来）时再登一次，最多三次
    if echo "$body" | grep -q '"code":40100' && [ "$relogin_left" -gt 0 ]; then
      relogin_left=$((relogin_left - 1))
      ADMIN_BOOT_TOKEN=$(token_of "$(admin_login)")
    fi
    sleep 2
  done
  echo "!! $desc 在 120s 内没有返回预期响应（$url），末次响应: $(echo "$body" | head -c 160)" >&2
  echo "   若末次仍是 40100：先查 gateway 与 marketing-admin 两侧的 ADMIN_JWT_SECRET 是否同值" >&2
  return 1
}

rc=0
# 后台路由先探：它同时验到 lb://marketing-admin → DB → BCrypt 这条完整链，
# 而下面 discount 的探测要复用它签出的 token —— 顺序反了就会出现
# "拿空 token 探优惠路由"这种指向错地方的红。它是第一道探测，所以自带等待：
# admin 冷启动比业务服务慢半拍，服务发现没订阅到时网关回 503（空响应体）。
LOGIN_RESP=""
for _ in $(seq 1 30); do
  LOGIN_RESP=$(admin_login)
  ADMIN_BOOT_TOKEN=$(token_of "$LOGIN_RESP")
  [ -n "$ADMIN_BOOT_TOKEN" ] && break
  # 明确被拒（凭证不对 / 已被限速）就不再等：等到超时也不会变好
  echo "$LOGIN_RESP" | grep -qE '"code":(40100|40101|42900)' && break
  sleep 2
done
if [ -n "$ADMIN_BOOT_TOKEN" ]; then
  echo "    后台路由可服务"
else
  echo "!! 后台路由没有返回登录成功，末次响应: $(echo "$LOGIN_RESP" | head -c 160)" >&2
  echo "   查 ADMIN_JWT_SECRET 两侧是否同值 / LoginGuard 是否已被前面几次失败尝试锁住" >&2
  rc=1
fi
wait_route "/api/activity/ACT2026001" "活动路由" '"code":0' || rc=1
wait_route "/api/coupon/stock/CT2026001" "券路由" '"code":0' || rc=1
# ③ 之后 discount 没有 C 端 GET 了（规则读写搬进 /api/admin/discount/rules，
# 因为 GET 那条会把整套规则 DSL 交给共享 demo token）。探测改用后台列表：
# 它同时验到 lb://marketing-discount → DB → MyBatis 这条链，比原来的 C 路径更有代表性。
wait_route_admin "/api/admin/discount/rules" "优惠路由" '"code":0' || rc=1
wait_route "/api/seckill/activities" "秒杀路由" '"code":0' || rc=1
[ $rc -ne 0 ] && exit 1

echo "==> FULL 应用栈就绪"
docker compose -f "$MW" ps --format '{{.Name}}\t{{.Status}}' 2>/dev/null | head -6 || true
docker compose -f "$APP" ps --format 'table {{.Service}}\t{{.Name}}\t{{.Status}}'
