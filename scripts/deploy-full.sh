#!/usr/bin/env bash
# ============================================================
# FULL 扩容档 · 容器化部署（一容器一服务，可 --scale 多副本）
#   与本机进程形态（scripts/start-all.sh）二选一，二者都依赖同一常驻数据层
# 用法：./scripts/deploy-full.sh [docker compose up 的额外参数]
#       ./scripts/deploy-full.sh --scale marketing-discount=3
#       SKIP_BUILD=1 ./scripts/deploy-full.sh        跳过 mvn 构建
# 前置：docker + 已 mvn package（除非 SKIP_BUILD=1）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."
source "$(dirname "$0")/common.sh"

DATA="$PWD/docker/docker-compose.data.yml"
MW="$PWD/docker/docker-compose.prod.yml"
APP="$PWD/docker/docker-compose.full-app.yml"

if docker ps --format '{{.Names}}' | grep -q '^mkt-preview-standalone$'; then
  echo "!! LITE 形态正在占用 8090/8085，先执行 ./scripts/stop-preview.sh" >&2
  exit 1
fi

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "==> mvn package（全部模块）"
  mvn -q -DskipTests package
fi

echo "==> 数据层 + 扩容档中间件（nacos / rocketmq / prometheus）"
docker compose -f "$DATA" up -d --wait
docker compose -f "$MW" up -d --wait

echo "==> 构建并启动 FULL 应用栈"
docker compose -f "$APP" up -d --build "$@"

echo "==> 等待网关就绪（服务启动 + 注册进 nacos 需要一点时间）"
wait_healthy marketing-gateway 8090 180

echo "==> FULL 应用栈就绪"
docker compose -f "$MW" ps --format '{{.Name}}\t{{.Status}}' 2>/dev/null | head -6 || true
docker compose -f "$APP" ps --format 'table {{.Service}}\t{{.Name}}\t{{.Status}}'
