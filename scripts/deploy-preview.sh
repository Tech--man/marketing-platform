#!/usr/bin/env bash
# ============================================================
# 预览环境一键部署（全栈容器化：mysql + redis + standalone + gateway）
#   两个 JVM、消息走 Redis Stream，内存上限约 1.1G，够功能验证与对外演示
# 用法：./scripts/deploy-preview.sh            构建 jar → 起全栈 → 等健康
#       ./scripts/deploy-preview.sh --no-build 跳过 mvn 构建
# 依赖：docker + docker compose（正式服务器需原生 x86；Apple Silicon 本机
#       构建出的 arm64 镜像只能在本机用）
# 注意：与 prod 形态（docker-compose.prod.yml）端口冲突，先 ./scripts/stop-all.sh
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."
source "$(dirname "$0")/common.sh"

COMPOSE="$PWD/docker/docker-compose.preview.yml"
DATA_COMPOSE="$PWD/docker/docker-compose.data.yml"

if [ "${1:-}" != "--no-build" ]; then
  echo "==> mvn package（standalone + gateway fat jar）"
  mvn -q -DskipTests package -pl marketing-standalone,marketing-gateway -am
fi

# 后台 token 密钥：standalone（签发与校验）和 gateway（校验）必须同值。
# 未显式导出时随机生成一次并落盘复用 —— 每次随机会让重启后所有已发 token 失效，
# 而"复用同一个文件"和"写死在仓库里"的区别是：前者泄露的是本机，后者泄露的是所有人。
if [ -z "${ADMIN_JWT_SECRET:-}" ]; then
  SECRET_FILE="$PWD/.admin-jwt-secret"
  if [ ! -f "$SECRET_FILE" ]; then
    (umask 077 && head -c 32 /dev/urandom | base64 | tr -d '/+=' > "$SECRET_FILE")
    echo "==> 已生成后台 JWT 密钥 → $SECRET_FILE（0600，已在 .gitignore 内）"
  fi
  ADMIN_JWT_SECRET="$(cat "$SECRET_FILE")"
  export ADMIN_JWT_SECRET
fi

# 消费者 token 密钥：与后台同一套处置（随机生成一次、0600 落盘、gitignore、复用）。
# 刻意不复用同一个变量：两把密钥同值 = 后台 token 可被消费者侧接受，
# "两套凭证不互通"就只剩 claim 形状这一层侥幸（见 AccountProperties 的注释）。
if [ -z "${CONSUMER_JWT_SECRET:-}" ]; then
  CONSUMER_SECRET_FILE="$PWD/.consumer-jwt-secret"
  if [ ! -f "$CONSUMER_SECRET_FILE" ]; then
    (umask 077 && head -c 32 /dev/urandom | base64 | tr -d '/+=' > "$CONSUMER_SECRET_FILE")
    echo "==> 已生成消费者 JWT 密钥 → $CONSUMER_SECRET_FILE（0600，已在 .gitignore 内）"
  fi
  CONSUMER_JWT_SECRET="$(cat "$CONSUMER_SECRET_FILE")"
  export CONSUMER_JWT_SECRET
fi

echo "==> 数据层常驻检查（mysql + redis，与 FULL/dev 同一份数据）"
docker compose -f "$DATA_COMPOSE" up -d --wait

echo "==> 构建并启动 LITE 应用栈（standalone 首次启动约 40-90s）"
docker compose -f "$COMPOSE" up -d --build --wait

# compose 里的 healthcheck 只探 TCP 端口，而"端口在听"不等于"应用可用"——本轮开发环境
# 就撞到过 JVM 起来、8085 监听、但 /actuator/health 返回 503 DOWN 的情况，--wait 会照样
# 判就绪。所以就绪判定用应用自己的 health 端点收口（这两个端口编排里都对宿主机发布）。
echo "==> 等待应用级就绪（actuator/health）"
wait_healthy marketing-standalone 8085 90
wait_healthy marketing-gateway 8091 60   # 网关管理端口（actuator 已与 8090 分离）

echo "==> 预览栈就绪。验收：./scripts/smoke-test.sh"
docker compose -f "$COMPOSE" ps
