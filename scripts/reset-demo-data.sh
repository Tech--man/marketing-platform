#!/usr/bin/env bash
# ============================================================
# 复位演示容量：调秒杀活动总库存，走后台端点，不直连 DB 也不直连 Redis
#   冒烟与压测会持续吃掉 seckill 库存（sold_stock 涨到 total_stock 后恒返回"已售罄"），
#   本脚本把 total_stock 抬到指定值；分桶由 owning 服务在**同一个事务里**按
#   `remain = total - sold` 重建，所以账实一致不需要脚本再插手。
#   （换库布局时必须跑一次：单库 ↔ 每服务一库之间分桶键是共用的、sold_stock 各算各的，
#    不重置的话恒等式会因为上一布局的消费记录对不上而红。）
# 用法：./scripts/reset-demo-data.sh [新总库存]   默认 5000
# 前置：任一形态的网关在跑（三套形态的网关都是 :8090），后台账号已种子
#   ③ 之前这里是三条形态分支（探测容器 → restart 容器 / 重启宿主机进程 → 等键数变多），
#   现在形态差异对脚本不再可见 —— 这正是把写入口收到业务进程里换来的东西。
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

GW="${GW:-http://127.0.0.1:8090}"
NEW_TOTAL=${1:-5000}
ACT=SK2026001
JSON="Content-Type: application/json"

# 冷栈守卫：脚本什么都能做，唯独不能在栈没起来时装作成功了。
# 上一版这里只打两行提示就 0 退出，实测把 FULL 进程形态留成 41007「秒杀库存未预热」
# + 冒烟整片红，而退出码看起来一切正常。
GW_MGMT="${GW_MGMT:-http://127.0.0.1:8091}"   # actuator 已与业务端口分离
if ! curl -fs --max-time 3 "$GW_MGMT/actuator/health" >/dev/null 2>&1; then
  echo "!! 网关不可达（$GW）：先起任一形态（deploy-preview / deploy-full / start-dev）再复位" >&2
  exit 1
fi

TOKEN=$(curl -s -m 25 -X POST "$GW/api/admin/auth/login" -H "$JSON" \
  -d '{"username":"admin","password":"rootdev123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then
  echo "!! 后台登录失败：账号未种子、口令不对，或刚被 LoginGuard 限速（每 IP 10 次/分钟）" >&2
  exit 1
fi
AAUTH="Authorization: Bearer $TOKEN"

# version 必须先读回来：改库存带乐观锁，脚本自己造一个数会把 41008 变成常态。
# 按 '{' 切行再挑本活动那条，是为了不把别的活动的 version 抓来。
LIST=$(curl -s -m 10 -H "$AAUTH" "$GW/api/admin/seckill/activities?page=1&size=50")
ROW=$(echo "$LIST" | tr '{' '\n' | grep "\"activityNo\":\"$ACT\"" | head -1 || true)
if [ -z "$ROW" ]; then
  echo "!! 后台列表里没有 $ACT，末次响应: $(echo "$LIST" | head -c 200)" >&2
  exit 1
fi
field() { echo "$ROW" | sed -n "s/.*\"$1\":\([0-9]*\).*/\1/p"; }
OLD_TOTAL=$(field totalStock)
SOLD=$(field soldStock)
VERSION=$(field version)
STATUS=$(echo "$ROW" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')
echo "==> $ACT：total=$OLD_TOTAL sold=$SOLD status=$STATUS version=$VERSION → 抬到 $NEW_TOTAL"

if [ "$STATUS" != "ONLINE" ]; then
  # 不"顺手"帮忙上线：只有 ONLINE 的活动会重建分桶（开闸是独立动作，③ 的 T6 钉过这条）
  echo "    注意：活动是 $STATUS，改完库存不会重建分桶（要放票需另做上线动作）"
fi

RESP=$(curl -s -m 15 -X PUT -H "$AAUTH" -H "$JSON" \
  "$GW/api/admin/seckill/activities/$ACT/stock" \
  -d "{\"totalStock\":$NEW_TOTAL,\"version\":${VERSION:-0}}")
if ! echo "$RESP" | grep -q '"code":0'; then
  echo "!! 改库存失败: $(echo "$RESP" | head -c 300)" >&2
  echo "   41008=有人（或上一次运行）先改过 version，重跑一次即可；40000=新值低于已售数 $SOLD" >&2
  exit 1
fi

# 恒等式复查走 C 端读路径：分桶余量合计 + 已售 == 新总库存。
# 读 C 端而不是读 Redis：脚本因此不需要知道键名，也不依赖 docker exec 能进得去。
# 先 sed 出 data 数组再相加：整串直接喂给 awk 的话，第一个桶会跟着 `"data":[` 一起
# 变成非数字而被当成 0（实测少算一个桶，把一次正常的复位读成恒等式破了）。
buckets_sum() {
  # 不带凭证：/api/seckill/stock/** 是游客可读的余量读数（C 端共享 demo token 那一层已删除）
  curl -s -m 10 "$GW/api/seckill/stock/$ACT" \
    | sed -n 's/.*"data":\[\([^]]*\)\].*/\1/p' \
    | tr ',' '\n' | awk '{s += $1 + 0} END {print s + 0}'
}
WANT=$((NEW_TOTAL - SOLD))
SUM=0
for _ in $(seq 1 30); do
  SUM=$(buckets_sum)
  [ "${SUM:-0}" -eq "$WANT" ] && break
  sleep 2
done
if [ "${SUM:-0}" -ne "$WANT" ]; then
  echo "!! 分桶余量合计=$SUM，与期望 $WANT 不符（新总库存 $NEW_TOTAL − 已售 $SOLD）" >&2
  echo "   恒等式对不上时不要接着跑冒烟：那会把'库存未预热'的 41007 当成新 bug 查" >&2
  exit 1
fi
echo "==> 复位后：分桶余量合计=$SUM，已售=$SOLD，合计 $((SUM + SOLD)) == 新总库存 $NEW_TOTAL"
