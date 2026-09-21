#!/usr/bin/env bash
# ============================================================
# 停止预览环境。用法：
#   ./scripts/stop-preview.sh        停止并移除 LITE 应用容器（数据层由 data.yml 常驻，不动）
#   ./scripts/stop-preview.sh -v     连共用数据卷一起清空（三套形态的数据都会没，谨慎）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE="$PWD/docker/docker-compose.preview.yml"

if [ "${1:-}" = "-v" ]; then
  docker compose -f "$COMPOSE" down
  docker compose -f "$PWD/docker/docker-compose.data.yml" down -v
  echo "!! 已连同常驻数据层与数据卷一起清空"
else
  docker compose -f "$COMPOSE" down
fi
echo "==> LITE 应用栈已停止（数据层容器与卷保持原样，供 FULL 原地接管）"
