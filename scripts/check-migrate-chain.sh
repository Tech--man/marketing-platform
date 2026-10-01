#!/usr/bin/env bash
# ============================================================
# DDL 门禁：迁移链与 init 副本的一致性对拍（2026-10-01 审计 P1-2）。
#
# 不变量：在一台空 MySQL 8 上，"全新 init 直建"的 schema 必须与
#   "全新 init + 全量 migrate 链"的 schema 完全一致——init 是迁移终态的镜像副本，
#   任何漂移都意味着两件事之一：① 迁移加了 init 没有的列/索引（老卷升级后
#   两类卷形状分叉）；② init 加了迁移没补的列（既有卷升级即缺列，P1-1 那类断层）。
#   SchemaParityTest 只对拍两份 init 文本，本脚本补的是"迁移链"这一段，
#   且跑的是真 MySQL 8 + 真 migrate.sh（含台账幂等），不是文本正则。
#
# 用法：./scripts/check-migrate-chain.sh     # 需 docker；一次性 mysql:8.0 容器，
#                                           # 不碰 mkt-mysql 常驻卷，退出即销毁
# 产物：全量输出 tee 到 docs/superpowers/evidence/<date>-check-migrate-chain.log
#   （复审 N-3/放行前置⑤：这道闸是"迁移链=init 终态"的唯一真库证明，跑过留痕）
# 已知豁免：标了 requires-root 的迁移由 migrate.sh 跳过（现实部署同样手动执行），
#   对拍语义不受影响；_migration 台账表是 migrate.sh 自己的簿记，排除在比较外。
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

EVIDENCE_DIR="docs/superpowers/evidence"
mkdir -p "$EVIDENCE_DIR"
exec > >(tee "$EVIDENCE_DIR/$(date +%Y-%m-%d)-check-migrate-chain.log") 2>&1

command -v docker >/dev/null 2>&1 || { echo "!! 需要 docker（一次性 MySQL 8 容器）" >&2; exit 1; }

CID="mkt-migrate-check-$$"
WORK=$(mktemp -d)
docker rm -f "$CID" >/dev/null 2>&1 || true
cleanup() { docker rm -f "$CID" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

echo "==> 起一次性 mysql:8.0（挂 init/01-schema.sql 首启直建六库）"
docker run -d --rm --name "$CID" \
  -e MYSQL_ROOT_PASSWORD=rootcheck \
  -e MYSQL_USER=marketing -e MYSQL_PASSWORD=marketing123 \
  -e MYSQL_DATABASE=marketing \
  -v "$PWD/docker/mysql/init/01-schema.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro" \
  mysql:8.0 >/dev/null

# 冷启要等 entrypoint 的完整两幕：临时初始化服务器（跑 init 脚本，本身打两条
# "ready for connections"）→ 关闭 → 正式服务器（再打两条）。判据用 ready 行数≥3
# 加 root 可查 + 六库已建——实测 mysql:8.0 的 mysqld 不一定 exec 成 PID 1，
# readlink /proc/1/exe 判据不可靠；arm64 主机跑 amd64 镜像是全模拟，两幕合计
# 可能要 20 分钟以上，别在中途动手。上限 30 分钟（复审 P3）：镜像拉不动/初始化
# 卡死时不能无限挂起占着 CI 或终端。
WAITED=0
until [ "$(docker logs "$CID" 2>&1 | grep -c 'ready for connections')" -ge 3 ] \
    && docker exec "$CID" mysql -uroot -prootcheck -N -B -e "SELECT 1" >/dev/null 2>&1 \
    && docker exec "$CID" mysql -uroot -prootcheck -N -B -e \
         "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='marketing_admin'" 2>/dev/null \
         | grep -qv '^0$'; do
  sleep 2
  WAITED=$((WAITED + 2))
  if [ "$WAITED" -ge 1800 ]; then
    echo "!! 一次性 MySQL 容器 ${WAITED}s 未就绪（镜像拉取失败或初始化卡死），视为门禁失败" >&2
    exit 1
  fi
done

# schema 快照：列（含序号/类型/可空）+ 索引（名 + 列序 + 唯一性），排除台账表
snapshot() { # outfile
  docker exec -i "$CID" mysql -uroot -prootcheck -N -B > "$1" <<'SQL'
SELECT table_schema, table_name, column_name, ordinal_position, column_type, is_nullable
  FROM information_schema.columns
 WHERE table_schema LIKE 'marketing%' AND table_name <> '_migration'
 ORDER BY table_schema, table_name, ordinal_position, column_name;
SELECT table_schema, table_name, index_name, non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
  FROM information_schema.statistics
 WHERE table_schema LIKE 'marketing%' AND table_name <> '_migration'
 GROUP BY table_schema, table_name, index_name, non_unique
 ORDER BY table_schema, table_name, index_name;
SQL
}

echo "==> 快照①：全新 init 直建"
snapshot "$WORK/before"

echo "==> 跑真 migrate.sh（ALL_DBS：marketing + 全部 marketing_*）"
ALL_DBS=1 MYSQL_CONTAINER="$CID" ./scripts/migrate.sh >/dev/null

echo "==> 快照②：init + 全量迁移链"
snapshot "$WORK/after"

if diff -u "$WORK/before" "$WORK/after" > "$WORK/drift.txt"; then
  echo "  ✅ 迁移链与 init 副本零漂移（老卷升级路径 = 新卷直建路径）"
else
  echo "  ❌ init 与迁移链出现漂移——老卷/新卷 schema 分叉：" >&2
  head -60 "$WORK/drift.txt" >&2
  exit 1
fi

echo "==> 幂等复跑：台账应跳过全部已应用迁移"
ALL_DBS=1 MYSQL_CONTAINER="$CID" ./scripts/migrate.sh >/dev/null
snapshot "$WORK/after2"
if diff -u "$WORK/after" "$WORK/after2" > "$WORK/drift2.txt"; then
  echo "  ✅ 复跑零变化（台账幂等成立）"
else
  echo "  ❌ 复跑后 schema 又变了——迁移不幂等：" >&2
  head -60 "$WORK/drift2.txt" >&2
  exit 1
fi

echo "==> DDL 门禁通过：$(wc -l < "$WORK/before" | tr -d ' ') 行 schema 事实（列+索引）两拍一致"
