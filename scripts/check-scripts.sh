#!/usr/bin/env bash
# ============================================================
# shell 引号门禁（2026-10-01 审计 P1-3 的机器闸）：
#   本轮修掉的两类"全角字符紧跟变量名/转义掏空变量"bug 形状，不许再回来。
#     ① `\${...}`——f706bfc 用错的形式：双引号里输出字面量 ${VAR}，
#        断言诊断值被静默掏空（grep 得有分辨力，先管住自己）；
#     ② `$VAR）` 等——bash 3.2 + C.UTF-8 下变量名解析吞掉全角首字节，
#        set -u 直接 unbound variable 中止脚本。
#   合法豁免：`echo` 里给用户看的 `\$（openssl …）` 命令样例（要展示字面量 $）。
#   用法：./scripts/check-scripts.sh   （CI/pre-commit 直接挂本脚本，非零即红）
# ============================================================
set -uo pipefail
cd "$(dirname "$0")/.."

FAIL=0

# ① \${ 形式（f706bfc 坏形状；合法的 \$ 样例不带花括号，不受影响）。
#   grep 排除本文件自身：门禁的说明文档必须能描述这两个形状，否则永远自咬。
BAD_ESCAPED=$(grep -rn '\\\${' scripts/*.sh | grep -v '^scripts/check-scripts\.sh:' || true)
if [ -n "$BAD_ESCAPED" ]; then
  # 文案用单引号：双引号里的 \$ + { 会被 bash 当参数展开，本门禁不打自己的脸
  echo '❌ 发现 \${ 形式（变量被转义成字面量，诊断值会被掏空）：' >&2
  echo "$BAD_ESCAPED" >&2
  FAIL=1
fi

# ② 变量名后紧跟全角标点（unbound variable 中止形状）
BAD_FULLWIDTH=$(grep -rn '\$[A-Za-z_][A-Za-z0-9_]*[）（，。：；、]' scripts/*.sh \
  | grep -v '^scripts/check-scripts\.sh:' || true)
if [ -n "$BAD_FULLWIDTH" ]; then
  echo '❌ 发现变量名后紧跟全角标点（bash 3.2 + C.UTF-8 会吞字节报 unbound variable），请改 ${VAR}全角：' >&2
  echo "$BAD_FULLWIDTH" >&2
  FAIL=1
fi

if [ "$FAIL" -eq 0 ]; then
  echo "==> 引号门禁通过：$(ls scripts/*.sh | wc -l | tr -d ' ') 个脚本两类形状均未复发"
fi
exit $FAIL
