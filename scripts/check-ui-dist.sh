#!/usr/bin/env bash
# ============================================================
# ⑥ 的产物一致性闸：jar 里的 static/ui/** 必须与仓库里的逐项 sha256 相同。
#
#   dist 是入仓产物，所以"改了源码没重构建"与"只改了产物"都表现为
#   界面与代码不同步，而且只在打包之后才看得见。仓库没有 CI
#   （.github 都不存在），因此这道闸靠人跑——它是第三道，
#   前两道是 scripts/build-ui.sh（唯一构建入口）与 UiDistIntegrityTest
#   （唯一会在 mvn test 里被顺带跑到的那道）。
#
# 用法：./scripts/check-ui-dist.sh
# 前提：已 mvn package（找不到 jar 时退出码是 2，不当成"通过"）
# 退出码：0 一致 / 1 有漂移 / 2 前置不满足
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=$(ls marketing-admin/target/marketing-admin-*.jar 2>/dev/null | grep -v sources | head -1 || true)
if [ -z "$JAR" ]; then
  echo "!! 找不到 marketing-admin 的 jar：先 source scripts/common.sh && mvn -q -pl marketing-admin package" >&2
  exit 2
fi

REPO="marketing-admin/src/main/resources/static/ui"
[ -d "$REPO" ] || { echo "!! 仓库侧 $REPO 不存在：⑥ 的产物被移走了？" >&2; exit 2; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
# 解不出来（jar 里压根没有 static/ui）必须非零：那正是"产物没进包"这种病
if ! unzip -qq "$JAR" 'BOOT-INF/classes/static/ui/*' -d "$WORK" 2>/dev/null; then
  echo "!! jar 里没有 static/ui：⑥ 的产物没进包（检查 vite 的 outDir 与 admin 的 resources）" >&2
  exit 1
fi

DRIFT=0
# 正向：jar 里每一项都要在仓库里且内容一致
while IFS= read -r f; do
  rel="${f#"$WORK"/BOOT-INF/classes/static/ui/}"
  want=$(shasum -a 256 "$f" | cut -d' ' -f1)
  if [ ! -f "$REPO/$rel" ]; then
    echo "!! jar 里有而仓库没有：$rel"; DRIFT=1; continue
  fi
  got=$(shasum -a 256 "$REPO/$rel" | cut -d' ' -f1)
  [ "$want" = "$got" ] || { echo "!! 内容不一致：$rel"; DRIFT=1; }
done < <(find "$WORK" -type f)

# 反向：仓库里有而 jar 里没有 = 有人往 dist 手塞了文件（或构建没产出它）
while IFS= read -r f; do
  rel="${f#"$REPO"/}"
  [ -f "$WORK/BOOT-INF/classes/static/ui/$rel" ] || { echo "!! 仓库里有而 jar 里没有：$rel"; DRIFT=1; }
done < <(find "$REPO" -type f)

if [ "$DRIFT" = "0" ]; then
  echo "==> jar 与仓库的 static/ui 逐项一致（$(find "$REPO" -type f | wc -l | tr -d ' ') 个文件）"
else
  echo "!! 重跑 ./scripts/build-ui.sh 并把产物一起提交" >&2
fi
exit $DRIFT
