/**
 * 领券中心的展示位。后端 C 端**没有**"列券模板"端点（模板清单是 admin-only），
 * 所以这里固化种子券的展示文案，真正的**实时库存**再走 /api/coupon/stock/{no} 拉。
 * 值对齐 docker/mysql/init/01-schema.sql 的种子数据；改种子要同步这里。
 */
export const COUPON_OFFERS = [
  {
    templateNo: "CT2026001",
    name: "全场无门槛券",
    faceValue: 5,
    thresholdAmount: 0,
    couponType: "CASH",
    validDays: 7,
    perUserLimit: 1,
    tagline: "下单立减，无门槛",
    scene: "全场通用 · 新人友好",
    icon: "ticket",
    tone: "brand",
  },
  {
    templateNo: "CT2026002",
    name: "满 100 减 20 券",
    faceValue: 20,
    thresholdAmount: 100,
    couponType: "FULL_REDUCTION",
    validDays: 14,
    perUserLimit: 2,
    tagline: "凑单更划算",
    scene: "满 ¥100 可用 · 全场通用",
    icon: "tag",
    tone: "gold",
  },
];

export const HOME_ACTIVITY_NO = "ACT2026001";
