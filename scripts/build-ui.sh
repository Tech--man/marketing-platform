#!/usr/bin/env bash
# ============================================================
# ⑥ 唯一的前端构建入口：装依赖 → vite build → 注入构建指纹 → 报告体积
#   产物 marketing-admin/src/main/resources/static/ui/** 是**入仓文件**，
#   所以"改了 .vue 没跑这里"= jar 里还是旧界面（UiDistIntegrityTest 挡一半，
#   check-ui-dist.sh 挡另一半，见计划 T11）。
# 用法：./scripts/build-ui.sh                      首次或依赖变了（会 npm install）
#       SKIP_INSTALL=1 ./scripts/build-ui.sh       只重构建
# 前置：本机 node ≥ 18（实测 v22.22.3 + npm 10.9.8）。构建不进 maven 生命周期（spec §10）。
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."
source "$(dirname "$0")/common.sh"

UI="$PWD/marketing-admin-ui"
OUT="$PWD/marketing-admin/src/main/resources/static/ui"

if ! command -v node >/dev/null 2>&1; then
  echo "!! 没找到 node：⑥ 的产物需要一次本机构建（不装进 maven 生命周期，见 spec §10）" >&2
  exit 1
fi
[ -f "$UI/package.json" ] || { echo "!! $UI/package.json 不在，前端目录被移过？" >&2; exit 1; }

cd "$UI"
if [ "${SKIP_INSTALL:-0}" = "1" ] && [ -d node_modules ]; then
  echo "==> 跳过 npm install"
else
  echo "==> npm install（首次会拉 Element Plus 与 vite，几分钟）"
  npm install --no-audit --no-fund
fi

echo "==> vite build"
npm run build

# 指纹注释：让"jar 里的界面是哪棵树的哪个时刻"在浏览器里直接看得出来。
# 没有这一行，产物漂了只能靠比对文件时间猜——而入仓产物最容易漂。
# pathspec 必须写成 `:/` 开头（仓库根相对）：脚本此刻已经 cd 进了 marketing-admin-ui/，
# 用相对路径的话 status 永远匹配不到任何东西，-dirty 标记就变成一件从不发生的装饰。
REV=$(git rev-parse --short HEAD)
[ -n "$(git status --porcelain -- ':/marketing-admin-ui')" ] && REV="$REV-dirty"
BUILD_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
python3 - "$OUT/index.html" "$REV" "$BUILD_AT" <<'PY'
import sys
path, rev, at = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path, encoding='utf-8') as f:
    html = f.read()
marker = f'<!-- build-ui: rev={rev} at={at} -->'
if marker not in html:
    if '</head>' not in html:
        sys.exit('!! index.html 里没有 </head>：vite 的 html 模板形状变了')
    html = html.replace('</head>', f'  {marker}\n  </head>', 1)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(html)
print(f'==> 指纹已注入：rev={rev} at={at}')
PY

FILES=$(find "$OUT" -type f | wc -l | tr -d ' ')
KB=$(du -sk "$OUT" | cut -f1)

# 产物必须比源码新。这条堵的是"构建其实失败了但报告照样打印"：
# 一旦把本脚本接进管道（`./build-ui.sh | tail`），set -e 的退出码被管道尾端吃掉，
# vite 报完 SFC 语法错、这里仍会自信地报出"产物 N 个文件"——实测这样骗过一次。
python3 - "$UI" "$OUT" <<'PY'
import pathlib
import sys

ui, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
sources = [p for p in (ui / 'src').rglob('*') if p.is_file()]
sources += [p for p in (ui / 'index.html', ui / 'vite.config.js') if p.is_file()]
artifacts = [p for p in out.rglob('*') if p.is_file()]
if not artifacts:
    sys.exit('!! 产物目录是空的：vite build 没落地')
newest_src = max(p.stat().st_mtime for p in sources)
newest_art = max(p.stat().st_mtime for p in artifacts)
if newest_src > newest_art:
    stale = max(sources, key=lambda p: p.stat().st_mtime)
    sys.exit(f'!! 源码比产物新（{stale}）：上面的 vite 步骤失败过，这份体积报告是旧的')
PY

echo "==> 产物：$FILES 个文件，${KB} KiB（未压缩）→ $OUT"
grep -q "build-ui" "$OUT/index.html" || { echo "!! 指纹没进去：检查上面的 python 步骤" >&2; exit 1; }
