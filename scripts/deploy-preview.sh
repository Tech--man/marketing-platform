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

if [ "${1:-}" != "--no-build" ]; then
  echo "==> mvn package（standalone + gateway fat jar）"
  mvn -q -DskipTests package -pl marketing-standalone,marketing-gateway -am
fi

echo "==> 构建并启动预览栈（standalone 首次启动约 40-90s）"
docker compose -f "$COMPOSE" up -d --build --wait

# compose 里的 healthcheck 只探 TCP 端口，而"端口在听"不等于"应用可用"——本轮开发环境
# 就撞到过 JVM 起来、8085 监听、但 /actuator/health 返回 503 DOWN 的情况，--wait 会照样
# 判就绪。所以就绪判定用应用自己的 health 端点收口（这两个端口编排里都对宿主机发布）。
echo "==> 等待应用级就绪（actuator/health）"
wait_healthy marketing-standalone 8085 90
wait_healthy marketing-gateway 8090 60

echo "==> 预览栈就绪。验收：./scripts/smoke-test.sh"
docker compose -f "$COMPOSE" ps
