#!/usr/bin/env bash
# ============================================================
# Lite 形态一键部署（2C4G 级单机）：构建 jar → compose 起全栈 → 健康等待
# 用法：./scripts/deploy-lite.sh          首次部署/重启
#       ./scripts/deploy-lite.sh --no-build  跳过 mvn 构建
# 依赖：docker + docker compose（服务器需原生 x86；Mac 本机构建镜像为 arm64 仅本机可用）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

if [ "${1:-}" != "--no-build" ]; then
  echo "==> mvn package（构建 standalone + gateway fat jar）"
  mvn -q -DskipTests package
fi

echo "==> 启动 Lite 栈（mysql/redis/standalone/gateway）"
docker compose -f docker/docker-compose.lite.yml up -d --build

echo "==> 等待网关 8090 就绪（standalone 启动约 40-90s）..."
for i in $(seq 1 60); do
  if curl -sf --max-time 3 http://127.0.0.1:8090/actuator/health | grep -q '"UP"'; then
    echo "==> Lite 栈就绪。验收：./scripts/smoke-test.sh（GW 默认已指向 8090）"
    docker compose -f docker/docker-compose.lite.yml ps
    exit 0
  fi
  sleep 5
done
echo "!! 网关 5 分钟内未就绪，排查：docker logs mkt-lite-standalone / mkt-lite-gateway" >&2
exit 1
