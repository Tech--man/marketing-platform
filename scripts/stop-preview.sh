#!/usr/bin/env bash
# ============================================================
# 停止预览环境。用法：
#   ./scripts/stop-preview.sh        停止并移除容器（保留数据卷）
#   ./scripts/stop-preview.sh -v     连数据卷一起清空
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE="$PWD/docker/docker-compose.preview.yml"

if [ "${1:-}" = "-v" ]; then
  docker compose -f "$COMPOSE" down -v
else
  docker compose -f "$COMPOSE" down
fi
echo "==> 预览栈已停止"
