#!/usr/bin/env bash
# ============================================================
# C 端 H5 的产物一致性闸：jar 里的 static/h5/** 必须与仓库里的逐项 sha256 相同。
# 口径与 check-ui-dist.sh（⑥ 后台）完全对称：唯一构建入口是 build-h5.sh，
# 这道闸验"提交进仓库的 dist == 打包出来的 dist"。
# 用法：./scripts/check-h5-dist.sh   前提：已 mvn package
# 退出码：0 一致 / 1 有漂移 / 2 前置不满足
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=$(ls marketing-admin/target/marketing-admin-*.jar 2>/dev/null | grep -v sources | head -1 || true)
if [ -z "$JAR" ]; then
  echo "!! 找不到 marketing-admin 的 jar：先 source scripts/common.sh && mvn -q -pl marketing-admin package" >&2
  exit 2
fi

REPO="marketing-admin/src/main/resources/static/h5"
[ -d "$REPO" ] || { echo "!! 仓库侧 $REPO 不存在：C 端产物被移走了？" >&2; exit 2; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
if ! unzip -qq "$JAR" 'BOOT-INF/classes/static/h5/*' -d "$WORK" 2>/dev/null; then
  echo "!! jar 里没有 static/h5：C 端产物没进包（检查 vite 的 outDir 与 admin 的 resources）" >&2
  exit 1
fi

DRIFT=0
while IFS= read -r f; do
  rel="${f#"$WORK"/BOOT-INF/classes/static/h5/}"
  want=$(shasum -a 256 "$f" | cut -d' ' -f1)
  if [ ! -f "$REPO/$rel" ]; then
    echo "!! jar 里有而仓库没有：$rel"; DRIFT=1; continue
  fi
  got=$(shasum -a 256 "$REPO/$rel" | cut -d' ' -f1)
  [ "$want" = "$got" ] || { echo "!! 内容不一致：$rel"; DRIFT=1; }
done < <(find "$WORK" -type f)

while IFS= read -r f; do
  rel="${f#"$REPO"/}"
  [ -f "$WORK/BOOT-INF/classes/static/h5/$rel" ] || { echo "!! 仓库里有而 jar 里没有：$rel"; DRIFT=1; }
done < <(find "$REPO" -type f)

if [ "$DRIFT" = "0" ]; then
  echo "==> jar 与仓库的 static/h5 逐项一致（$(find "$REPO" -type f | wc -l | tr -d ' ') 个文件）"
else
  echo "!! 重跑 ./scripts/build-h5.sh 并把产物一起提交" >&2
fi
exit $DRIFT
