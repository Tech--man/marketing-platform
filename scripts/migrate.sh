#!/usr/bin/env bash
# ============================================================
# 迁移台账执行器（W15，2026-09-29 审查收口；2026-10-01 v4 审计 P1-2 补校验和）：
#   按文件名序执行 docker/mysql/migrate/*.sql 里未应用的迁移，
#   应用结果记入 _migration 表（台账），重复执行安全（已应用的跳过）。
#
#   台账表在目标库自建（IF NOT EXISTS），对每个装着业务表的库各执行一遍。
#   校验和（v4 P1-2）：新应用的迁移记录文件 sha256；已应用行与仓库当前内容
#   不一致时拒绝执行（exit 1）——已发布迁移不许改写（配套闸：
#   scripts/check-migrate-immutable.sh 挡仓库侧，本脚本挡卷侧）。
#   逃生门 ACK_MIGRATION_DRIFT=1 显式放行并留 ⚠ 日志；早于本机制的行
#   checksum 为 NULL，只 WARN 不回填（回填=假装核验过）。
#
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

sha256_of() { # BSD shasum 与 GNU sha256sum 双分支（本地 macOS / CI Linux 通用）
    local s
    if s=$(shasum -a 256 "$1" 2>/dev/null); then
        echo "${s%% *}"
    else
        sha256sum "$1" | awk '{print $1}'
    fi
}

run_mysql() { # <db> [mysql 额外参数…] — stdin 走 SQL；"$@" 让 -e 等参数也透传
    docker exec -i "$CONTAINER" mysql --default-character-set=utf8mb4 \
        -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$@"
}

migrate_one_db() { # <db>
    local db=$1
    echo "==> 迁移台账目标库: ${db}"
    # 建表（新库直接带 checksum 列）+ 老台账补列（MySQL 8 无 ADD COLUMN IF NOT EXISTS，
    # 走 information_schema + PREPARE 守卫——与迁移文件自身的幂等手法同款）
    run_mysql "$db" <<SQL
CREATE TABLE IF NOT EXISTS ${LEDGER_TABLE} (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    filename   VARCHAR(255) NOT NULL COMMENT '迁移文件名（docker/mysql/migrate/ 下相对名）',
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    checksum   CHAR(64) NULL COMMENT '文件内容 sha256（v4 审计 P1-2）；NULL=早于校验和机制的行，内容不可核验',
    PRIMARY KEY (id),
    UNIQUE KEY uk_filename (filename)
) ENGINE = InnoDB COMMENT = '迁移执行台账（scripts/migrate.sh 维护）';
SET @has_cs := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = '${LEDGER_TABLE}' AND column_name = 'checksum');
SET @ddl := IF(@has_cs = 0,
    'ALTER TABLE ${LEDGER_TABLE} ADD COLUMN checksum CHAR(64) NULL AFTER applied_at',
    'SELECT 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
SQL

    local applied_count
    applied_count=$(run_mysql "$db" -N -B -e "SELECT COUNT(*) FROM ${LEDGER_TABLE}" 2>/dev/null || echo 0)
    echo "    台账已有 ${applied_count} 条记录"

    local total=0
    local file
    for file in $(ls "$MIGRATE_DIR"/*.sql | xargs -n1 basename | sort); do
        local sum
        sum=$(sha256_of "$MIGRATE_DIR/$file")
        local row
        row=$(run_mysql "$db" -N -B -e "SELECT checksum FROM ${LEDGER_TABLE} WHERE filename='$file'" 2>/dev/null || true)
        if [ -n "$row" ]; then
            # 已应用：校验和比对（NULL=早于机制的行，循环后统一 WARN）
            if [ "$row" != "NULL" ] && [ "$row" != "$sum" ]; then
                if [ "${ACK_MIGRATION_DRIFT:-0}" = "1" ]; then
                    echo "    ⚠️ ACK_MIGRATION_DRIFT：卷上已应用的 ${file} 与仓库内容不同（显式放行，请在发布记录留痕）" >&2
                else
                    echo "!! 卷上已应用的迁移 ${file} 与仓库当前内容不一致（台账 ${row} ≠ 当前 ${sum}）。" >&2
                    echo "   已发布迁移不许改写——确需处置请新增迁移文件；确认知悉本漂移可 ACK_MIGRATION_DRIFT=1 显式放行。" >&2
                    exit 1
                fi
            fi
            continue
        fi
        if grep -q '^-- requires-root: 1' "$MIGRATE_DIR/$file"; then
            echo "    跳过（需 root 权限，手动执行）: ${file}"
            continue
        fi
        echo "    应用: ${file}"
        run_mysql "$db" < "$MIGRATE_DIR/$file"
        run_mysql "$db" -e "INSERT INTO ${LEDGER_TABLE} (filename, checksum) VALUES ('$file', '$sum')"
        total=$((total + 1))
    done

    local legacy
    legacy=$(run_mysql "$db" -N -B -e "SELECT COUNT(*) FROM ${LEDGER_TABLE} WHERE checksum IS NULL" 2>/dev/null || echo 0)
    if [ "$legacy" -gt 0 ]; then
        echo "    ⚠️ 台账有 ${legacy} 行早于校验和机制（checksum 为 NULL，内容不可核验，不回填）"
    fi

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
