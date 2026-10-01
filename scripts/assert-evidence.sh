#!/usr/bin/env bash
# ============================================================
# 证据链生成器（2026-10-01 审计 P1-6）：把"全绿"从口头声明变成盘上物证。
#   此前盘上唯一可证的绿灯只有 surefire 报告；双前端 vitest、冒烟输出、
#   迁移执行全部零落盘——质量门禁全靠"作者记得做"。
#
# 做三件事，全部产物落 docs/superpowers/evidence/<date>-assert-evidence.md：
#   ① 后端：跑 mvn test（SKIP_MVN=1 时复用既有 surefire 报告并做**新鲜度对拍**
#      ——报告必须新于全部源文件，防"旧绿灯当新证据"；--require-fresh 模式下
#      过期即红）；汇总 Tests run/Failures/Errors；
#   ② 双前端：vitest --reporter=json 落盘并解析 numPassed/numFailed；
#   ③ 写证据文档：HEAD sha、各套件计数、原始产物路径。任一套件失败 → 非零退出。
#
# 用法：./scripts/assert-evidence.sh                 # 全量（后端 + 双前端）
#       SKIP_MVN=1 ./scripts/assert-evidence.sh      # 只收前端证据 + 后端新鲜度对拍
#       REQUIRE_FRESH=1 ...                          # surefire 过期即红（发布前用）
# ============================================================
set -uo pipefail
cd "$(dirname "$0")/.."

EVIDENCE_DIR="docs/superpowers/evidence"
STAMP=$(date +%Y-%m-%d)
OUT="$EVIDENCE_DIR/${STAMP}-assert-evidence.md"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$EVIDENCE_DIR"

HEAD_SHA=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
DIRTY=$(git status --porcelain | wc -l | tr -d ' ')

RC=0

# mtime 秒值，跨 BSD（macOS）/GNU（Linux、GitHub runner）两套 stat（复审 N-3：
# 原版 stat -f 在 Linux 上结构性不可运行，这道证据闸因此进不了 CI）
mtime_of() {
  local m
  if m=$(stat -f '%m' "$1" 2>/dev/null); then
    echo "$m"
  else
    stat -c '%Y' "$1"
  fi
}

# ---------- ① 后端 ----------
BACKEND_SUMMARY="未执行（SKIP_MVN=1）"
if [ "${SKIP_MVN:-0}" != "1" ]; then
  echo "==> mvn test（全 reactor）…" >&2
  if mvn test > "$TMP/mvn.log" 2>&1; then
    BACKEND_SUMMARY="通过"
  else
    BACKEND_SUMMARY="失败（见 $TMP/mvn.log）"
    RC=1
  fi
fi
# surefire 汇总（跑了或复用都统计）
TESTS=0; FAILS=0; ERRS=0; SKIPS=0; REPORTS=0
NEWEST_REPORT=0; NEWEST_SRC=0
for txt in marketing-*/target/surefire-reports/*.txt; do
  [ -f "$txt" ] || continue
  REPORTS=$((REPORTS + 1))
  line=$(grep -m1 'Tests run:' "$txt" || true)
  [ -n "$line" ] || continue
  TESTS=$((TESTS + $(echo "$line" | sed -n 's/.*Tests run: \([0-9]*\).*/\1/p')))
  FAILS=$((FAILS + $(echo "$line" | sed -n 's/.*Failures: \([0-9]*\).*/\1/p')))
  ERRS=$((ERRS + $(echo "$line" | sed -n 's/.*Errors: \([0-9]*\).*/\1/p')))
  SKIPS=$((SKIPS + $(echo "$line" | sed -n 's/.*Skipped: \([0-9]*\).*/\1/p')))
  m=$(mtime_of "$txt")
  [ "$m" -gt "$NEWEST_REPORT" ] && NEWEST_REPORT=$m
done
for src in marketing-*/src/main/java marketing-*/src/test/java; do
  [ -d "$src" ] || continue
  # 取该树全部 .java 的最新 mtime（原变量名 OLDEST_SRC 与语义相反，复审 P3 已正名）
  m=$(find "$src" -name '*.java' -print 2>/dev/null | while IFS= read -r f; do mtime_of "$f"; done | sort -rn | head -1)
  m=${m:-0}
  [ "$m" -gt "$NEWEST_SRC" ] && NEWEST_SRC=$m
done
FRESH="n/a"
if [ "$REPORTS" -gt 0 ] && [ "$NEWEST_SRC" -gt 0 ]; then
  if [ "$NEWEST_REPORT" -ge "$NEWEST_SRC" ]; then
    FRESH="新鲜（报告新于全部源文件）"
  else
    FRESH="过期（有源文件新于最新报告——旧绿灯不能当新证据）"
    [ "${REQUIRE_FRESH:-0}" = "1" ] && RC=1
  fi
fi

# v3 复审 N-20：用例数下限——"-pl 局部跑 + 旧报告残留"也能凑出"报告全绿"的假证据；
# 全量基线（2026-10-01 第九批后）是 106 份 / 561 例，下限打 ~85 折，跌破即红。
# 用例只增不减时永不触发；确需大规模裁撤用例时显式 ASSERT_MIN_REPORTS/ASSERT_MIN_TESTS=0。
MIN_REPORTS="${ASSERT_MIN_REPORTS:-90}"
MIN_TESTS="${ASSERT_MIN_TESTS:-500}"
if [ "$REPORTS" -lt "$MIN_REPORTS" ] || [ "$TESTS" -lt "$MIN_TESTS" ]; then
  echo "!! 报告/用例数低于全量下限（${REPORTS}<${MIN_REPORTS} 份 或 ${TESTS}<${MIN_TESTS} 例）——疑似局部跑或残留报告，不能当全量证据" >&2
  RC=1
fi

# ---------- ② 双前端 ----------
front_json() { # dir name → 解析 vitest JSON，echo "passed failed suites"
  local dir=$1
  local name=$2
  local out="$TMP/${name}.json"
  if (cd "$dir" && npx vitest run --reporter=json --outputFile="$PWD/test-results.json" >/dev/null 2>&1); then
    cp "$dir/test-results.json" "$out" 2>/dev/null || out=""
    python3 - "$out" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
    print(d.get('numPassedTests', 0), d.get('numFailedTests', 0), d.get('numTotalTestSuites', 0))
except Exception:
    print('?', '?', '?')
PY
  else
    echo "RUN_FAILED ? ?"
  fi
}
read ADMIN_P ADMIN_F ADMIN_S <<< "$(front_json marketing-admin-ui admin)"
read H5_P H5_F H5_S <<< "$(front_json marketing-h5-ui h5)"
[ "$ADMIN_F" = "0" ] && [ "$ADMIN_P" != "?" ] && [ "$ADMIN_P" != "RUN_FAILED" ] || RC=1
[ "$H5_F" = "0" ] && [ "$H5_P" != "?" ] && [ "$H5_P" != "RUN_FAILED" ] || RC=1

# ---------- ③ 证据文档 ----------
{
  echo "# 测试证据 · ${STAMP}"
  echo
  echo "- HEAD：\`${HEAD_SHA}\`（工作树未提交变更 ${DIRTY} 个）"
  echo "- 生成：\`./scripts/assert-evidence.sh\`（SKIP_MVN=${SKIP_MVN:-0} REQUIRE_FRESH=${REQUIRE_FRESH:-0}）"
  echo
  echo "| 套件 | 结果 | 计数 |"
  echo "|---|---|---|"
  echo "| 后端 mvn test | ${BACKEND_SUMMARY} | 报告 ${REPORTS} 份：Tests ${TESTS} / Failures ${FAILS} / Errors ${ERRS} / Skipped ${SKIPS} |"
  echo "| 后端报告新鲜度 | ${FRESH} | — |"
  echo "| admin-ui vitest | $([ "$ADMIN_F" = "0" ] && echo 通过 || echo 失败) | passed ${ADMIN_P} / failed ${ADMIN_F} / suites ${ADMIN_S} |"
  echo "| h5-ui vitest | $([ "$H5_F" = "0" ] && echo 通过 || echo 失败) | passed ${H5_P} / failed ${H5_F} / suites ${H5_S} |"
  echo
  echo "## 门禁脚本（发布前另跑；前两道已自动落盘 evidence/，后两道产物在各自输出）"
  echo "- \`scripts/lua-contract.sh\`（8 个 Lua × 56+ 断言，一次性 Redis）→ \`evidence/<date>-lua-contract.log\`"
  echo "- \`scripts/check-migrate-chain.sh\`（init+迁移链对拍，一次性 MySQL 8）→ \`evidence/<date>-check-migrate-chain.log\`"
  echo "- \`scripts/check-scripts.sh\`（shell 引号门禁）"
  echo "- \`scripts/check-ui-dist.sh\`（需先 mvn package）"
} > "$OUT"

echo "==> 证据已落 ${OUT}（退出码 ${RC}）"
grep -A6 '套件 | 结果' "$OUT"
exit $RC
