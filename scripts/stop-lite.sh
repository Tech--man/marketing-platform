#!/usr/bin/env bash
# ============================================================
# 停止 Lite 形态。用法：
#   ./scripts/stop-lite.sh        停止并移除容器（保留数据卷）
#   ./scripts/stop-lite.sh -v     连数据卷一起清空
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

if [ "${1:-}" = "-v" ]; then
  docker compose -f docker/docker-compose.lite.yml down -v
else
  docker compose -f docker/docker-compose.lite.yml down
fi
echo "==> Lite 栈已停止"
