#!/usr/bin/env bash
# ============================================================
# Lua 原子核心契约清单（2026-10-01 审计 P1-2 补课）：
#   全部 8 个生产 Lua（限流滑窗/券预扣/券回补/预算扣减/闸门 CAS/抢购借桶/回桶/
#   预算退款）在一次性 Redis 容器上逐条断言——这些脚本是全部资损判定的原子核心，
#   却从未被任何自动化测试执行过一行（单测把 Java 侧 stub 成固定返回值）。
#   本脚本是发布前手工门禁，也是未来 Testcontainers 化的契约底稿。
#
# 用法：./scripts/lua-contract.sh        # 需 docker；自带一次性 redis:7-alpine，
#                                       # 不碰 mkt-redis 常驻实例，退出即销毁
# 产物：全量输出 tee 到 docs/superpowers/evidence/<date>-lua-contract.log
#   （复审 N-3/放行前置⑤：手工门禁不能"跑过无痕"——这两道闸是全部资损判定的
#    唯一真执行覆盖，跑过与否、结论如何必须留盘上物证）
# 契约来源：各 .lua 头注释的 KEYS/ARGV/返回值约定，与 Java 调用方逐一对齐。
# 实现：脚本从宿主 stdin 经 SCRIPT LOAD 装进一次性容器（不需要 docker cp），
#   断言用 EVAL + 显式 numkeys——KEYS/ARGV 个数本身也是契约的一部分。
# ============================================================
set -uo pipefail
cd "$(dirname "$0")/.."

EVIDENCE_DIR="docs/superpowers/evidence"
mkdir -p "$EVIDENCE_DIR"
exec > >(tee "$EVIDENCE_DIR/$(date +%Y-%m-%d)-lua-contract.log") 2>&1

PASS=0; FAIL=0
ok()  { echo "  ✅ $1"; PASS=$((PASS+1)); }
bad() { echo "  ❌ $1"; echo "     实际值: $2"; FAIL=$((FAIL+1)); }
expect_eq() { # desc want got
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望 $2）" "$3"; fi
}

command -v docker >/dev/null 2>&1 || { echo "!! 需要 docker（一次性 Redis 容器）" >&2; exit 1; }

CID="mkt-lua-contract-$$"
docker rm -f "$CID" >/dev/null 2>&1 || true
# noeviction 与生产同款（README 三节不可退让项），契约行为不因淘汰策略漂移
if ! docker run -d --rm --name "$CID" redis:7-alpine --maxmemory-policy noeviction >/dev/null 2>&1; then
  echo "!! 无法启动一次性 redis:7-alpine（先 docker pull redis:7-alpine）" >&2
  exit 1
fi
trap 'docker rm -f "$CID" >/dev/null 2>&1 || true' EXIT
until docker exec "$CID" redis-cli PING 2>/dev/null | grep -q PONG; do sleep 0.3; done

r() { docker exec "$CID" redis-cli "$@"; }
sha_of() { # 本地 .lua 文件 → 一次性容器内的脚本 sha1（stdin 装载）
  docker exec -i "$CID" redis-cli -x SCRIPT LOAD < "$1" | tr -d '\r\n'
}
SHA_SW=$(sha_of marketing-gateway/src/main/resources/lua/sliding_window.lua)
SHA_STOCK=$(sha_of marketing-coupon/src/main/resources/lua/deduct_stock.lua)
SHA_ROLLBACK=$(sha_of marketing-coupon/src/main/resources/lua/rollback_stock.lua)
SHA_BUDGET=$(sha_of marketing-activity/src/main/resources/lua/deduct_budget.lua)
SHA_GATE=$(sha_of marketing-activity/src/main/resources/lua/gate_cas.lua)
SHA_GRAB=$(sha_of marketing-seckill/src/main/resources/lua/seckill_grab.lua)
SHA_REFILL=$(sha_of marketing-seckill/src/main/resources/lua/seckill_refill.lua)
SHA_REFUND_BUDGET=$(sha_of marketing-activity/src/main/resources/lua/refund_budget.lua)
for s in "$SHA_SW" "$SHA_STOCK" "$SHA_ROLLBACK" "$SHA_BUDGET" "$SHA_GATE" "$SHA_GRAB" "$SHA_REFILL" "$SHA_REFUND_BUDGET"; do
  [ -n "$s" ] || { echo "!! 有 Lua 脚本装载失败（SCRIPT LOAD 返回空）" >&2; exit 1; }
done

# ev <sha> <numkeys> k1 [k2…] a1 [a2…]：numkeys 之后前 numkeys 个是 KEYS，其余是 ARGV
ev() {
  local sha=$1 n=$2; shift 2
  r EVALSHA "$sha" "$n" "$@"
}
fresh() { r DEL "$@" >/dev/null; }

echo "== 1/8 sliding_window.lua（网关限流：ZSET 滑窗）"
fresh lc:rl
expect_eq "窗口内第 1 发放行" 1 "$(ev "$SHA_SW" 1 lc:rl 100000 10000 2 m1)"
expect_eq "窗口内第 2 发放行" 1 "$(ev "$SHA_SW" 1 lc:rl 100000 10000 2 m2)"
expect_eq "超过阈值第 3 发限流" 0 "$(ev "$SHA_SW" 1 lc:rl 100000 10000 2 m3)"
expect_eq "时间前进超过窗口后旧请求被裁剪、重新放行" 1 \
  "$(ev "$SHA_SW" 1 lc:rl 120000 10000 2 m3)"

echo "== 2/8 deduct_stock.lua（券预扣：库存 × 单人限领原子判定）"
fresh lc:stock lc:user
r SET lc:stock 5 >/dev/null
expect_eq "库存 5 领 1 成功" 1 "$(ev "$SHA_STOCK" 2 lc:stock lc:user 1 2 60)"
expect_eq "第二张仍在限领内" 1 "$(ev "$SHA_STOCK" 2 lc:stock lc:user 1 2 60)"
expect_eq "第三张撞单人限领（-1），库存不动" -1 "$(ev "$SHA_STOCK" 2 lc:stock lc:user 1 2 60)"
expect_eq "库存未因被拒的多扣" 3 "$(r GET lc:stock)"
fresh lc:stock lc:user2
r SET lc:stock 1 >/dev/null
expect_eq "库存 1 领 2 拒（0 库存不足）" 0 "$(ev "$SHA_STOCK" 2 lc:stock lc:user2 2 5 60)"
fresh lc:stock3 lc:user3
expect_eq "键未预热（-2）且不创建幽灵键" -2 "$(ev "$SHA_STOCK" 2 lc:stock3 lc:user3 1 2 60)"
expect_eq "被拒后库存键仍不存在" 0 "$(r EXISTS lc:stock3)"

echo "== 3/8 rollback_stock.lua（券回补：EXISTS 守卫 + token 去重 + 用户计数回减）"
fresh lc:rstock lc:ruser lc:rback:t1 lc:rback:t2
r SET lc:rstock 5 >/dev/null; r SET lc:ruser 2 >/dev/null
expect_eq "回补 1 张后返回新库存 6" 6 "$(ev "$SHA_ROLLBACK" 3 lc:rstock lc:ruser lc:rback:t1 1 3600)"
expect_eq "用户计数回减到 1" 1 "$(r GET lc:ruser)"
expect_eq "同 token 二次回补被去重拒掉（-2，防窄双退）" -2 \
  "$(ev "$SHA_ROLLBACK" 3 lc:rstock lc:ruser lc:rback:t1 1 3600)"
expect_eq "  库存没有二次回补" 6 "$(r GET lc:rstock)"
expect_eq "不同 token（另一次预扣）照常回补到 7" 7 \
  "$(ev "$SHA_ROLLBACK" 3 lc:rstock lc:ruser lc:rback:t2 1 3600)"
fresh lc:rstock2 lc:ruser2 lc:rback:t3
expect_eq "库存键缺失拒绝回补（-1，不占 token）" -1 \
  "$(ev "$SHA_ROLLBACK" 3 lc:rstock2 lc:ruser2 lc:rback:t3 1 3600)"
expect_eq "未凭空创建幽灵库存键" 0 "$(r EXISTS lc:rstock2)"
expect_eq "  去重标记也未落（之后的合法重试仍可回补）" 0 "$(r EXISTS lc:rback:t3)"
# P3-1 声明（v3/v4 审计）：token=requestId 时"回补→同键再预扣→再回补"的窗口里，
# 第二次回补被 -2 吞（少发方向，由 mismatch 面板收敛）。这里把当前行为钉成文档化契约：
# 改 token 粒度前先想清楚谁依赖 -2 的幂等防窄双退。
fresh lc:wstock lc:wuser lc:wback:t9
r SET lc:wstock 5 >/dev/null
expect_eq "窗口①预扣 1 张（库存 5→4）" 1 "$(ev "$SHA_STOCK" 2 lc:wstock lc:wuser 1 2 60)"
expect_eq "窗口②首次回补 token=t9（库存回 5）" 5 "$(ev "$SHA_ROLLBACK" 3 lc:wstock lc:wuser lc:wback:t9 1 3600)"
expect_eq "窗口③同 requestId 第二次预扣（5→4）" 1 "$(ev "$SHA_STOCK" 2 lc:wstock lc:wuser 1 2 60)"
expect_eq "窗口④第二次回补 token=t9 被 -2 吞（已声明窗口）" -2 \
  "$(ev "$SHA_ROLLBACK" 3 lc:wstock lc:wuser lc:wback:t9 1 3600)"
expect_eq "  库存停在 4（少一张，mismatch 面板可见）" 4 "$(r GET lc:wstock)"

echo "== 4/8 deduct_budget.lua（预算扣减）"
fresh lc:budget
r SET lc:budget 100 >/dev/null
expect_eq "余额 100 扣 30 成功" 1 "$(ev "$SHA_BUDGET" 1 lc:budget 30)"
expect_eq "扣后余 70" 70 "$(r GET lc:budget)"
expect_eq "余 70 扣 80 拒（0 余额不足）" 0 "$(ev "$SHA_BUDGET" 1 lc:budget 80)"
expect_eq "被拒后余额不动" 70 "$(r GET lc:budget)"
fresh lc:budget2
expect_eq "键未预热（-1）" -1 "$(ev "$SHA_BUDGET" 1 lc:budget2 10)"

echo "== 5/8 gate_cas.lua（活动闸门版本化 CAS：五种情形）"
fresh lc:gate
expect_eq "①键缺失直写" 1 "$(ev "$SHA_GATE" 1 lc:gate 'ONLINE|3' 3)"
expect_eq "  写入形状带版本" "ONLINE|3" "$(r GET lc:gate)"
expect_eq "②先升到版本 5" 1 "$(ev "$SHA_GATE" 1 lc:gate 'ONLINE|5' 5)"
expect_eq "旧快照(3)＜键内(5)拒写" 0 "$(ev "$SHA_GATE" 1 lc:gate 'GRAY|3' 3)"
expect_eq "  值未被旧快照覆盖" "ONLINE|5" "$(r GET lc:gate)"
expect_eq "③新版本(7)＞键内(3)覆盖" 1 "$(ev "$SHA_GATE" 1 lc:gate 'OFFLINE|7' 7)"
expect_eq "  值更新为新版本" "OFFLINE|7" "$(r GET lc:gate)"
expect_eq "④同版本重写幂等放行(>=)" 1 "$(ev "$SHA_GATE" 1 lc:gate 'OFFLINE|7' 7)"
fresh lc:gate2
r SET lc:gate2 ONLINE >/dev/null
expect_eq "⑤旧代码裸值视 version=0，新版本覆盖" 1 "$(ev "$SHA_GATE" 1 lc:gate2 'ONLINE|1' 1)"
expect_eq "  覆盖后形状升级为带版本" "ONLINE|1" "$(r GET lc:gate2)"
# W2.4（2026-10-01 审计 P2-1）：灰度键三段形状 percent|w1,w2|version 也走同一把 CAS
fresh lc:gray
r SET lc:gray '10|70001,70002|5' >/dev/null
expect_eq "灰度形状：旧快照(3)＜键内(5)拒写" 0 "$(ev "$SHA_GATE" 1 lc:gray '10|70001|3' 3)"
expect_eq "  三段值未被旧快照覆盖" "10|70001,70002|5" "$(r GET lc:gray)"
expect_eq "灰度形状：新版本(7)覆盖三段值" 1 "$(ev "$SHA_GATE" 1 lc:gray '10|70001|7' 7)"
expect_eq "  版本段取最后一个 | 之后" "10|70001|7" "$(r GET lc:gray)"
fresh lc:gray2
r SET lc:gray2 '10|70001,70002' >/dev/null
expect_eq "灰度两段旧形状（白名单段非数字）视 version=0，可覆盖" 1 \
  "$(ev "$SHA_GATE" 1 lc:gray2 '10|70001|1' 1)"

echo "== 6/8 seckill_grab.lua（hash 定位 + 环形借桶）"
fresh lc:sk:1 lc:sk:2 lc:sk:3
r MSET lc:sk:1 0 lc:sk:2 5 lc:sk:3 0 >/dev/null
# uid=4 → 4%3=1 → 目标桶 2（有 5 张）→ 命中 2 号桶
expect_eq "hash 定位命中 2 号桶" 2 "$(ev "$SHA_GRAB" 3 lc:sk:1 lc:sk:2 lc:sk:3 4 1)"
expect_eq "命中桶扣减" 4 "$(r GET lc:sk:2)"
fresh lc:sk:1 lc:sk:2 lc:sk:3
r MSET lc:sk:1 0 lc:sk:2 0 lc:sk:3 2 >/dev/null
# uid=3 → 3%3=0 → 目标桶 1（空）→ 环形：先看 2 号（空）再 3 号（2 张）→ 命中 3
expect_eq "目标桶空时环形借到 3 号桶" 3 "$(ev "$SHA_GRAB" 3 lc:sk:1 lc:sk:2 lc:sk:3 3 1)"
fresh lc:sk:1 lc:sk:2 lc:sk:3
r MSET lc:sk:1 0 lc:sk:2 0 lc:sk:3 0 >/dev/null
expect_eq "全部桶空 → 售罄(0)" 0 "$(ev "$SHA_GRAB" 3 lc:sk:1 lc:sk:2 lc:sk:3 5 1)"
fresh lc:sk:1 lc:sk:2 lc:sk:3
expect_eq "首桶键缺失 → 未预热(-2)" -2 "$(ev "$SHA_GRAB" 3 lc:sk:1 lc:sk:2 lc:sk:3 5 1)"

echo "== 7/8 seckill_refill.lua（超时取消回补原桶）"
fresh lc:rf:1 lc:rf:2
r MSET lc:rf:1 3 lc:rf:2 0 >/dev/null
expect_eq "回补 2 号桶 1 张成功" 1 "$(ev "$SHA_REFILL" 2 lc:rf:1 lc:rf:2 2 1)"
expect_eq "  桶值 +1" 1 "$(r GET lc:rf:2)"
expect_eq "桶号越界拒绝（-2）" -2 "$(ev "$SHA_REFILL" 2 lc:rf:1 lc:rf:2 9 1)"
r DEL lc:rf:2 >/dev/null
expect_eq "桶键失效拒绝（-2）" -2 "$(ev "$SHA_REFILL" 2 lc:rf:1 lc:rf:2 2 1)"

echo "== 8/8 refund_budget.lua（退款差额封顶原子判定，2026-10-01 P2-4）"
fresh lc:rbudget
r SET lc:rbudget 7000 >/dev/null
# 期望 7500（当前 7000，差 500）：全额退 500
expect_eq "当前低于期望 → 全额退 500" 500 "$(ev "$SHA_REFUND_BUDGET" 1 lc:rbudget 500 7500)"
expect_eq "  余额回到期望值 7500" 7500 "$(r GET lc:rbudget)"
r SET lc:rbudget 7600 >/dev/null
# 孤儿形态：当前 7600 已高于期望 7500 → 封顶为 0（不造钱）
expect_eq "当前高于期望 → 封顶退 0" 0 "$(ev "$SHA_REFUND_BUDGET" 1 lc:rbudget 500 7500)"
expect_eq "  余额分文未动" 7600 "$(r GET lc:rbudget)"
r SET lc:rbudget 7300 >/dev/null
# 差额 200 < 请求 500 → 只退差额
expect_eq "差额不足 → 按差额退 200" 200 "$(ev "$SHA_REFUND_BUDGET" 1 lc:rbudget 500 7500)"
expect_eq "  余额恰收敛到期望" 7500 "$(r GET lc:rbudget)"
fresh lc:rbudget2
expect_eq "键缺失 → -1（只落流水，不凭空起算）" -1 "$(ev "$SHA_REFUND_BUDGET" 1 lc:rbudget2 500 7500)"
expect_eq "  未创建幽灵键" 0 "$(r EXISTS lc:rbudget2)"

echo
echo "================ Lua 契约：通过 $PASS / 失败 $FAIL ================"
[ $FAIL -eq 0 ]
