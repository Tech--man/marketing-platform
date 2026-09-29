import { defineStore } from "pinia";
import { ref, computed } from "vue";

/**
 * 购物车 / 结算态。承载 /api/discount/calculate 所需的 CalcInput 行项目，
 * 以及"选了哪张券"。金额、优惠、到手价全部交给后端算——这里只存输入，
 * 绝不本地复刻满减/折扣逻辑（复刻=又造一个真相源，必漂移）。
 */

// 演示商品目录：tags 对齐 ③ 的促销规则夹具（DIGITAL 触发 8.5 折，GIFT 被满减排除），
// 这样加进车里能看到规则真实命中、互斥组 PRICE_CUT 只生效其一。
// icon 是 Icon.vue 里的图标名——界面不用 emoji 当图标。
export const CATALOG = [
  { skuId: 20001, itemId: 20001, name: "降噪蓝牙耳机", spec: "白色 / 标配", unitPrice: 199, tags: ["DIGITAL"], icon: "headphones" },
  { skuId: 20002, itemId: 20002, name: "运动智能手表", spec: "42mm / 硅胶带", unitPrice: 899, tags: ["DIGITAL"], icon: "device" },
  { skuId: 20003, itemId: 20003, name: "重磅纯棉短袖", spec: "白 / L", unitPrice: 89, tags: ["APPAREL"], icon: "tag" },
  { skuId: 20004, itemId: 20004, name: "手冲咖啡套装", spec: "礼盒装", unitPrice: 399, tags: ["GIFT"], icon: "gift" },
  { skuId: 20005, itemId: 20005, name: "半自动咖啡机", spec: "复古绿", unitPrice: 599, tags: ["HOME"], icon: "cup" },
];

const ACTIVITY_NO = "ACT2026001";
const CART_KEY = "mkt.h5.cart";

function loadCart() {
  try {
    const raw = JSON.parse(localStorage.getItem(CART_KEY) || "[]");
    if (!Array.isArray(raw)) return [];
    // 旧版本把 emoji 存进了 localStorage；按 skuId 回填成图标名，
    // 否则回访用户的商品缩略图会全部退化成兜底图形。
    return raw.map((l) => ({ ...l, icon: l.icon || CATALOG.find((p) => p.skuId === l.skuId)?.icon || "bag" }));
  } catch {
    return [];
  }
}

export const useCart = defineStore("cart", () => {
  // [{ skuId, itemId, name, spec, unitPrice, tags, icon, qty }]
  const lines = ref(loadCart());
  const activityNo = ref(ACTIVITY_NO);
  // 人群标签开关已随服务端 H9 收口移除（2026-09-29）：/api/discount/calculate 会把
  // 请求体里的 userTags 覆写为空集——人群规则在接入可信人群来源前不可用，
  // 留一个"点了没反应"的自报开关只会误导。
  const selectedCoupon = ref(null); // 选中的券（含 faceValue），结算页据此显示抵扣
  const selectedCouponCode = computed(() => selectedCoupon.value?.couponCode || null);

  function persist() {
    localStorage.setItem(CART_KEY, JSON.stringify(lines.value));
  }

  function add(product, qty = 1) {
    const hit = lines.value.find((l) => l.skuId === product.skuId);
    if (hit) hit.qty += qty;
    else
      lines.value.push({
        skuId: product.skuId,
        itemId: product.itemId,
        name: product.name,
        spec: product.spec,
        unitPrice: product.unitPrice,
        tags: [...product.tags],
        icon: product.icon,
        qty,
      });
    persist();
  }

  function setQty(skuId, qty) {
    const l = lines.value.find((x) => x.skuId === skuId);
    if (!l) return;
    if (qty <= 0) return remove(skuId);
    l.qty = qty;
    persist();
  }

  function remove(skuId) {
    lines.value = lines.value.filter((x) => x.skuId !== skuId);
    persist();
  }

  function clear() {
    lines.value = [];
    selectedCoupon.value = null;
    persist();
  }

  function selectCoupon(coupon) {
    selectedCoupon.value =
      selectedCoupon.value?.couponCode === coupon.couponCode ? null : coupon;
  }

  const count = computed(() => lines.value.reduce((n, l) => n + l.qty, 0));

  const totalQuantity = computed(() =>
    lines.value.reduce((n, l) => n + l.qty, 0)
  );

  /** 构造 /api/discount/calculate 的 CalcInput；空车返回 null（后端本就 40000） */
  const calcInput = computed(() => {
    if (!lines.value.length) return null;
    return {
      // 不带 userId：服务端 @JsonIgnore 掉了它，身份由验签后的 access token 回填。
      // 也不带 userTags：服务端同样覆写为空集（H9），发了也是白发。
      activityNo: activityNo.value,
      items: lines.value.map((l) => ({
        lineId: String(l.skuId),
        skuId: l.skuId,
        itemId: l.itemId,
        tags: l.tags,
        unitPrice: l.unitPrice,
        quantity: l.qty,
      })),
    };
  });

  return {
    lines,
    activityNo,
    selectedCoupon,
    selectedCouponCode,
    add,
    setQty,
    remove,
    clear,
    selectCoupon,
    count,
    totalQuantity,
    calcInput,
  };
});
