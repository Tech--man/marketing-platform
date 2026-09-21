#!/usr/bin/env bash
# ============================================================
# FULL 扩容档 · 容器化部署（一容器一服务，可 --scale 多副本）
#   与本机进程形态（scripts/start-all.sh）二选一，二者都依赖同一常驻数据层
# 用法：./scripts/deploy-full.sh [docker compose up 的额外参数]
#       ./scripts/deploy-full.sh --scale marketing-discount=3
#       SKIP_BUILD=1 ./scripts/deploy-full.sh        跳过 mvn 构建，并且复用已有 :full 镜像
# 前置：docker + 已 mvn package（SKIP_BUILD=1 时改用现成镜像，改了 Java 代码就别用它）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."
source "$(dirname "$0")/common.sh"

DATA="$PWD/docker/docker-compose.data.yml"
MW="$PWD/docker/docker-compose.prod.yml"
# 容器形态的 broker 广播地址覆盖层（详见该文件头注释）
MW_C="$PWD/docker/docker-compose.prod.container.yml"
APP="$PWD/docker/docker-compose.full-app.yml"

if docker ps --format '{{.Names}}' | grep -q '^mkt-preview-standalone$'; then
  echo "!! LITE 形态正在占用 8090/8085，先执行 ./scripts/stop-preview.sh" >&2
  exit 1
fi

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "==> mvn package（全部模块）"
  mvn -q -DskipTests package
fi

# SKIP_BUILD=1 时连镜像也不重建：compose 的 --build 会重新 COPY 一层 fat jar，
# 实测一次全量重建多占 ~2 GB，而 broker 与 MySQL 的镜像层就写在这同一块盘上——
# 盘涨到 90% 以上时 broker 直接以 CODE:14 拒写，异步链路会"看起来坏了"。
# 用普通字符串而非数组：macOS 自带 bash 3.2 在 set -u 下展开空数组会直接报错。
BUILD_FLAG="--build"
[ "${SKIP_BUILD:-0}" = "1" ] && BUILD_FLAG=""

echo "==> 数据层 + 扩容档中间件（nacos / rocketmq / prometheus，broker 用容器形态广播地址）"
docker compose -f "$DATA" up -d --wait
docker compose -f "$MW" -f "$MW_C" up -d --wait
# 单独重建 broker：bind 挂载钉 inode，只改 conf 内容时 compose 不会认为服务有变化（详见 start-all.sh 同处注释）
docker compose -f "$MW" -f "$MW_C" up -d --wait --force-recreate rocketmq-broker

# 起应用前先报 broker 的磁盘水位：分区使用率 ≥90% 时它以 CODE:14 service not available 拒写，
# 症状和"异步链路坏了"完全一样（实测被这个坑过一整轮排查）。
broker_used=$(docker exec mkt-rocketmq-broker df -P / 2>/dev/null | awk 'NR==2 {gsub("%","",$5); print $5}')
if [ -n "${broker_used:-}" ] && [ "$broker_used" -ge 85 ]; then
  echo "!! broker 分区已用 ${broker_used}%：≥90% 会拒写异步投递（消息退回本地消息表，不丢但延迟）。" >&2
  echo "   先回收空间：docker builder prune -f（实测一次释放 3.4 GB）" >&2
fi

echo "==> ${BUILD_FLAG:+构建并}启动 FULL 应用栈"
docker compose -f "$APP" up -d $BUILD_FLAG "$@"

echo "==> 等待网关就绪（服务启动 + 注册进 nacos 需要一点时间）"
wait_healthy marketing-gateway 8090 180

echo "==> FULL 应用栈就绪"
docker compose -f "$MW" ps --format '{{.Name}}\t{{.Status}}' 2>/dev/null | head -6 || true
docker compose -f "$APP" ps --format 'table {{.Service}}\t{{.Name}}\t{{.Status}}'
