#!/usr/bin/env bash
# 停止全部由 start-all.sh 启动的服务（按 run/*.pid）+ 前端容器（前后端分离后
# start-all 会拉起 marketing-web；数据层/中间件不在本脚本职责内，同 stop-dev 惯例）
set -uo pipefail
cd "$(dirname "$0")/.."

for pid_file in run/*.pid; do
  [ -e "$pid_file" ] || { echo "没有运行中的服务（run/ 为空）"; break; }
  name=$(basename "$pid_file" .pid)
  pid=$(cat "$pid_file")
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" && echo "==> 已停止 $name (pid $pid)"
  else
    echo "==> $name 进程不存在，清理 pid 文件"
  fi
  rm -f "$pid_file"
done

# 前端容器（compose.web.yml，host 进程形态在跑时才有；down 幂等）
docker compose -f "$PWD/docker/docker-compose.web.yml" down 2>/dev/null \
  && echo "==> 前端容器（marketing-web）已停止" || true
