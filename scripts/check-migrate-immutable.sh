#!/usr/bin/env bash
# ============================================================
# 迁移不可变性闸（v4 审计 P1-2；v2 N-8 / v3 N-15 的机器收口）：
#   docker/mysql/migrate/SHA256SUMS 是"已发布迁移"的基线清单（随首个迁移提交入仓，
#   此后每个新迁移在同一提交里重新生成）。本脚本对拍目录与清单，三类判红：
#   ① 已入清单的文件内容变了 —— 改写已发布迁移对已入台账的卷是永久静默 no-op
#      （本仓历史上真实发生过三次，见 v4 审计 P1-2），形状/回填变化一律新增文件；
#   ② 目录里出现了清单外的 *.sql —— 新迁移必须与清单更新同提交，否则这里红；
#   ③ 清单里有条目但文件缺失。
# 重新生成：cd docker/mysql/migrate && for f in $(LC_ALL=C ls *.sql); do shasum -a 256 "$f"; done > SHA256SUMS
# 零 docker 依赖、秒级，进 CI 的 gates job。
# ============================================================
set -uo pipefail
cd "$(dirname "$0")/.."

DIR="docker/mysql/migrate"
MANIFEST="$DIR/SHA256SUMS"
FAIL=0

sha256_of() { # BSD shasum 与 GNU sha256sum 双分支
    local s
    if s=$(shasum -a 256 "$1" 2>/dev/null); then
        echo "${s%% *}"
    else
        sha256sum "$1" | awk '{print $1}'
    fi
}

[ -f "$MANIFEST" ] || { echo "!! 缺少基线清单 ${MANIFEST}（按脚本头注释生成并入仓）" >&2; exit 1; }

# ① + ②：目录内每个 .sql 必须在清单中且哈希一致
for f in "$DIR"/*.sql; do
    [ -f "$f" ] || continue
    base=$(basename "$f")
    want=$(grep -m1 "  $base\$" "$MANIFEST" | awk '{print $1}')
    if [ -z "$want" ]; then
        echo "❌ 新迁移未入基线清单: $base —— 新文件与清单更新必须同一提交（按脚本头注释重新生成 SHA256SUMS）" >&2
        FAIL=1
        continue
    fi
    got=$(sha256_of "$f")
    if [ "$want" != "$got" ]; then
        echo "❌ 已发布迁移被改写: ${base}（清单 ${want} ≠ 当前 ${got}）——对已入台账的卷这是静默 no-op；形状/回填变化请新增迁移文件" >&2
        FAIL=1
    fi
done

# ③：清单条目必须对应存在的文件
while read -r sum entry; do
    [ -n "$sum" ] || continue
    if [ ! -f "$DIR/$entry" ]; then
        echo "❌ 清单条目无对应文件: $entry" >&2
        FAIL=1
    fi
done < "$MANIFEST"

if [ "$FAIL" -eq 0 ]; then
    echo "==> 迁移不可变性闸通过：$(grep -c . "$MANIFEST") 个已发布迁移与基线清单逐字节一致"
fi
exit $FAIL
