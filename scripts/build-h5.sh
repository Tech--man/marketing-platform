#!/usr/bin/env bash
# ============================================================
# C 端 H5 唯一的前端构建入口：装依赖 → vite build → 注入构建指纹 → 报告体积
#   产物 marketing-admin/src/main/resources/static/h5/** 是**入仓文件**，口径与
#   build-ui.sh（⑥ 后台）完全一致：改了 .vue 没跑这里 = jar 里还是旧界面。
# 用法：./scripts/build-h5.sh
#       SKIP_INSTALL=1 ./scripts/build-h5.sh   只重构建
# 前置：本机 node ≥ 18；构建不进 maven 生命周期。
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."
source "$(dirname "$0")/common.sh"

UI="$PWD/marketing-h5-ui"
OUT="$PWD/marketing-admin/src/main/resources/static/h5"

if ! command -v node >/dev/null 2>&1; then
  echo "!! 没找到 node：C 端 H5 的产物需要一次本机构建" >&2
  exit 1
fi
[ -f "$UI/package.json" ] || { echo "!! $UI/package.json 不在，前端目录被移过？" >&2; exit 1; }

cd "$UI"
if [ "${SKIP_INSTALL:-0}" = "1" ] && [ -d node_modules ]; then
  echo "==> 跳过 npm install"
else
  echo "==> npm install"
  npm install --no-audit --no-fund
fi

echo "==> vite build"
# outDir 在项目根之外，vite 的 emptyOutDir 对外链目录不总是一视同仁地清空，
# 曾出现两次构建的产物混在一起（孤儿 chunk）。构建前物理清一次，保证 dist 只等于本次结果。
rm -rf "$OUT"
npm run build

# 清 target 里的上一份 h5 dist（maven 资源拷贝只覆盖不删，攒旧产物的坑与后台同源）
if [ -d "$PWD/../marketing-admin/target/classes/static/h5" ]; then
  echo "==> 清理 target/classes/static/h5 里的旧产物"
  rm -rf "$PWD/../marketing-admin/target/classes/static/h5"
fi

REV=$(git rev-parse --short HEAD)
[ -n "$(git status --porcelain -- ':/marketing-h5-ui')" ] && REV="$REV-dirty"
BUILD_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
python3 - "$OUT/index.html" "$REV" "$BUILD_AT" <<'PY'
import sys
path, rev, at = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path, encoding='utf-8') as f:
    html = f.read()
marker = f'<!-- build-h5: rev={rev} at={at} -->'
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
grep -q "build-h5" "$OUT/index.html" || { echo "!! 指纹没进去：检查上面的 python 步骤" >&2; exit 1; }
