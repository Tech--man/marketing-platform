#!/usr/bin/env bash
# ============================================================
# 停止开发环境：本机 2 个 JVM
# 用法：./scripts/stop-dev.sh          只停 JVM（中间件容器留着，下次启动更快）
#       ./scripts/stop-dev.sh --down   连中间件容器一起停（保留数据卷）
#       ./scripts/stop-dev.sh -v       连数据卷一起清空
# ============================================================
set -uo pipefail
cd "$(dirname "$0")/.."

DATA_COMPOSE="$PWD/docker/docker-compose.data.yml"
STOPPED=0

for name in marketing-standalone marketing-gateway; do
  pid_file="run/$name.pid"
  [ -f "$pid_file" ] || continue
  pid=$(cat "$pid_file")
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" && echo "==> 已停止 $name (pid $pid)"
  else
    echo "==> $name 进程已不存在，清理 pid 文件"
  fi
  rm -f "$pid_file"
  STOPPED=1
done
[ "$STOPPED" = 1 ] || echo "==> 没有运行中的开发进程（run/ 为空）"

case "${1:-}" in
  --down)
    docker compose -f "$DATA_COMPOSE" down
    docker compose -f "$PWD/docker/docker-compose.web.yml" down
    echo "==> 数据层与前端容器已停止（卷保留）。注意数据层是三套形态共用的。"
    ;;
  -v)
    echo "!! -v 会清空三套形态共用的数据卷（mysql + redis AOF），不可恢复" >&2
    docker compose -f "$DATA_COMPOSE" down -v
    docker compose -f "$PWD/docker/docker-compose.web.yml" down
    echo "==> 数据层与数据卷已清空，前端容器已停止"
    ;;
esac
