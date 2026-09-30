#!/usr/bin/env bash
# ============================================================
# 迁移台账执行器（W15，2026-09-29 审查收口）：
#   按文件名序执行 docker/mysql/migrate/*.sql 里未应用的迁移，
#   应用结果记入 _migration 表（台账），重复执行安全（已应用的跳过）。
#
#   台账表在目标库自建（IF NOT EXISTS），对每个装着业务表的库各执行一遍。
#   用法：./scripts/migrate.sh                       # 对单库档 marketing 执行
#         DB=marketing_seckill ./scripts/migrate.sh # 对隔离档某个库执行
#         ALL_DBS=1 ./scripts/migrate.sh            # 对 marketing + 全部 marketing_* 各执行
# 前置：mkt-mysql 容器在跑（迁移经 docker exec 走容器内客户端，不依赖宿主 mysql）。
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

MIGRATE_DIR="docker/mysql/migrate"
MYSQL_USER="${MYSQL_USER:-marketing}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-marketing123}"
CONTAINER="${MYSQL_CONTAINER:-mkt-mysql}"
LEDGER_TABLE="_migration"

run_mysql() { # <db> [mysql 额外参数…] — stdin 走 SQL；"$@" 让 -e 等参数也透传
    docker exec -i "$CONTAINER" mysql --default-character-set=utf8mb4 \
        -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$@"
}

migrate_one_db() { # <db>
    local db=$1
    echo "==> 迁移台账目标库: $db"
    run_mysql "$db" <<SQL
CREATE TABLE IF NOT EXISTS ${LEDGER_TABLE} (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    filename   VARCHAR(255) NOT NULL COMMENT '迁移文件名（docker/mysql/migrate/ 下相对名）',
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_filename (filename)
) ENGINE = InnoDB COMMENT = '迁移执行台账（scripts/migrate.sh 维护）';
SQL

    local applied
    applied=$(run_mysql "$db" -N -e "SELECT filename FROM ${LEDGER_TABLE}" 2>/dev/null | sort || true)
    local applied_count
    applied_count=$(echo -n "$applied" | grep -c . || true)
    echo "    台账已有 ${applied_count} 条记录"

    local total=0
    local file
    for file in $(ls "$MIGRATE_DIR"/*.sql | xargs -n1 basename | sort); do
        if echo "$applied" | grep -qx "$file"; then
            continue
        fi
        if grep -q '^-- requires-root: 1' "$MIGRATE_DIR/$file"; then
            echo "    跳过（需 root 权限，手动执行）: $file"
            continue
        fi
        echo "    应用: $file"
        run_mysql "$db" < "$MIGRATE_DIR/$file"
        run_mysql "$db" -e "INSERT INTO ${LEDGER_TABLE} (filename) VALUES ('$file')"
        total=$((total + 1))
    done

    echo "    本轮新应用 ${total} 个迁移；台账累计 $((applied_count + total)) 条"
}

if [ "${ALL_DBS:-0}" = "1" ]; then
    # 隔离档：每个装着业务表的库各走一遍（迁移自带表存在性守卫，乱跑无害）
    databases=$(docker exec "$CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -N \
        -e "SELECT schema_name FROM information_schema.schemata
             WHERE schema_name LIKE 'marketing%' ORDER BY schema_name" 2>/dev/null)
    for db in $databases; do
        migrate_one_db "$db"
    done
else
    migrate_one_db "${DB:-marketing}"
fi

echo "==> 完成。台账明细："
run_mysql "${DB:-marketing}" -e "SELECT filename, applied_at FROM ${LEDGER_TABLE} ORDER BY id" 2>/dev/null || true
