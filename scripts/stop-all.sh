#!/usr/bin/env bash
# 停止全部由 start-all.sh 启动的服务（按 run/*.pid）
set -uo pipefail
cd "$(dirname "$0")/.."

for pid_file in run/*.pid; do
  [ -e "$pid_file" ] || { echo "没有运行中的服务（run/ 为空）"; exit 0; }
  name=$(basename "$pid_file" .pid)
  pid=$(cat "$pid_file")
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" && echo "==> 已停止 $name (pid $pid)"
  else
    echo "==> $name 进程不存在，清理 pid 文件"
  fi
  rm -f "$pid_file"
done
