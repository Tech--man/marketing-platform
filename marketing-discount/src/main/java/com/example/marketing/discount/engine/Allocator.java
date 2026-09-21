package com.example.marketing.discount.engine;

import com.example.marketing.discount.domain.CalcItem;
import com.example.marketing.discount.domain.CalcResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 优惠分摊：规则优惠额按范围内各行原始金额比例分摊，行级尾差归该行范围内末项。
 *
 * <p>算法：前 n-1 行按 {@code discount * rowAmount / scopeAmount} DOWN 取分，
 * 末行 = discount - 前面已分摊之和（吸收尾差）；行级可分摊额度受"该行剩余空间
 * （行金额 - 已被之前规则分摊）"约束，超出部分顺延给范围内其他有余量的行。</p>
 */
public final class Allocator {

    private Allocator() {
    }

    /**
     * @param hit        命中规则（提供范围行与基数）
     * @param discount   本规则实际要分摊的优惠额（可能已被剩余额度 cap 缩小）
     * @param usedShares 各行已被之前规则分摊的金额（行级 cap 依据），本方法会就地更新
     * @return 行级分摊明细
     */
    public static List<CalcResult.ItemShare> apportion(RuleHit hit, BigDecimal discount,
                                                       Map<String, BigDecimal> usedShares) {
        List<CalcItem> scope = hit.getScopeItems();
        BigDecimal scopeAmount = hit.getScopeAmount();
        List<CalcResult.ItemShare> shares = new ArrayList<>();
        if (discount.signum() <= 0 || scope.isEmpty()) {
            return shares;
        }
        // 每行剩余可分摊空间
        Map<String, BigDecimal> room = new java.util.HashMap<>();
        BigDecimal totalRoom = BigDecimal.ZERO;
        for (CalcItem item : scope) {
            BigDecimal used = usedShares.getOrDefault(item.getLineId(), BigDecimal.ZERO);
            BigDecimal r = item.amount().subtract(used).max(BigDecimal.ZERO);
            room.put(item.getLineId(), r);
            totalRoom = totalRoom.add(r);
        }
        // 剩余额度不足则整体缩减（不应发生，防御兜底）
        BigDecimal target = discount.min(totalRoom).setScale(2, RoundingMode.HALF_UP);

        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < scope.size() - 1; i++) {
            CalcItem item = scope.get(i);
            BigDecimal share = target.multiply(item.amount())
                    .divide(scopeAmount, 2, RoundingMode.DOWN)
                    .min(room.get(item.getLineId()))
                    .min(target.subtract(allocated));
            if (share.signum() > 0) {
                shares.add(new CalcResult.ItemShare(item.getLineId(), share));
                allocated = allocated.add(share);
                room.put(item.getLineId(), room.get(item.getLineId()).subtract(share));
            }
        }
        // 尾差：优先给末行，末行空间不足则顺延给有余量的行
        BigDecimal remainder = target.subtract(allocated);
        CalcItem last = scope.get(scope.size() - 1);
        BigDecimal lastShare = remainder.min(room.get(last.getLineId()));
        if (lastShare.signum() > 0) {
            shares.add(new CalcResult.ItemShare(last.getLineId(), lastShare));
            remainder = remainder.subtract(lastShare);
        }
        for (int i = 0; i < scope.size() - 1 && remainder.signum() > 0; i++) {
            CalcItem item = scope.get(i);
            BigDecimal extra = remainder.min(room.get(item.getLineId()));
            if (extra.signum() > 0) {
                mergeShare(shares, item.getLineId(), extra);
                remainder = remainder.subtract(extra);
            }
        }
        // 回填 usedShares（行级 cap 累积）
        for (CalcResult.ItemShare share : shares) {
            usedShares.merge(share.getLineId(), share.getAmount(), BigDecimal::add);
        }
        return shares;
    }

    /** 同一行可能已有一条分摊记录（尾差顺延时），合并金额 */
    private static void mergeShare(List<CalcResult.ItemShare> shares, String lineId, BigDecimal extra) {
        for (int i = 0; i < shares.size(); i++) {
            if (shares.get(i).getLineId().equals(lineId)) {
                shares.set(i, new CalcResult.ItemShare(lineId, shares.get(i).getAmount().add(extra)));
                return;
            }
        }
        shares.add(new CalcResult.ItemShare(lineId, extra));
    }
}
