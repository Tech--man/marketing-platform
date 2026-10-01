import { describe, it, expect } from "vitest";
import {
  splitYuan,
  yuan,
  cutYuan,
  netPayable,
  hasAmt,
  cents,
  pct,
  countdown,
  countdownText,
  parseGrabResult,
  uuid,
} from "@/utils/format";

describe("金额/分/百分比", () => {
  it("splitYuan 把 BigDecimal 元拆成整数与两位小数，容忍字符串", () => {
    expect(splitYuan(1999)).toEqual({ int: "1999", dec: "00" });
    expect(splitYuan("89.5")).toEqual({ int: "89", dec: "50" });
    // N-5：旧断言 splitYuan(null)→{int:"0",dec:"00"} 把"读不到"钉成"真 0 元"，
    // 视图层照它写出了 ¥0.00 合计。现在钉相反契约：哨兵段，绝不折叠成 0。
    expect(splitYuan(null)).toEqual({ int: "—", dec: "" });
  });
  it("yuan 千分位分组，带符号", () => {
    expect(yuan(1234567.891)).toBe("¥1,234,567.89");
    expect(yuan(0, { sign: false })).toBe("0.00");
  });
  it("P2-7：读不到的金额给 —，绝不折叠成 ¥0.00（免费≠不知道）", () => {
    expect(hasAmt(null)).toBe(false);
    expect(hasAmt(undefined)).toBe(false);
    expect(hasAmt("")).toBe(false);
    expect(hasAmt(0)).toBe(true);
    expect(hasAmt("12.5")).toBe(true);
    expect(yuan(null)).toBe("—");
    expect(yuan(undefined)).toBe("—");
    expect(yuan("")).toBe("—");
  });
  it("N-5：扣减额 cutYuan 读不到给裸 —，读得到才带负号（不是 -¥0.00 也不是 -—）", () => {
    expect(cutYuan(12.5)).toBe("-12.50");
    expect(cutYuan("8")).toBe("-8.00");
    expect(cutYuan(null)).toBe("—");
    expect(cutYuan(undefined)).toBe("—");
  });
  it("N-5：应付合计 netPayable 试算未落地是 null，券未读到不抵扣，抵扣夹 0 下限", () => {
    expect(netPayable(null, 5)).toBeNull();
    expect(netPayable(undefined, null)).toBeNull();
    expect(netPayable(100, null)).toBe(100);
    expect(netPayable("88.5", "10")).toBe(78.5);
    expect(netPayable(3, 10)).toBe(0);
  });
  it("cents 分转元；null 视作未知而非 0", () => {
    expect(cents(12345)).toBe(123.45);
    expect(cents(null)).toBeNull();
  });
  it("pct 夹在 0..100，除零不炸", () => {
    expect(pct(30, 100)).toBe(30);
    expect(pct(200, 100)).toBe(100);
    expect(pct(-5, 100)).toBe(0);
    expect(pct(10, 0)).toBe(0);
  });
});

describe("倒计时", () => {
  it("未过期给出 d/h/m/s 且 ended=false", () => {
    const now = Date.now();
    const c = countdown(new Date(now + (2 * 3600 + 5 * 60 + 9) * 1000), now);
    expect(c.ended).toBe(false);
    expect([c.h, c.m, c.s]).toEqual([2, 5, 9]);
    expect(countdownText(c)).toBe("02:05:09");
  });
  it("已过期 ended=true，文案空", () => {
    const c = countdown(Date.now() - 1000);
    expect(c.ended).toBe(true);
    expect(countdownText(c)).toBe("");
  });
  it("无法解析的时间也判为已结束，不抛", () => {
    expect(countdown("not-a-date").ended).toBe(true);
  });
});

describe("抢购结果解析", () => {
  it("SUCCESS 前缀抽订单号", () => {
    expect(parseGrabResult("SUCCESS:SK2026001-8899")).toEqual({
      state: "success",
      orderNo: "SK2026001-8899",
    });
  });
  it("FAIL 前缀抽原因", () => {
    expect(parseGrabResult("FAIL:已售罄").state).toBe("fail");
    expect(parseGrabResult("FAIL:已售罄").reason).toBe("已售罄");
  });
  it("ACCEPTED / 空 归到未终态或 not_found", () => {
    expect(parseGrabResult("ACCEPTED").state).toBe("accepted");
    expect(parseGrabResult(null).state).toBe("not_found");
  });
});

describe("幂等键", () => {
  it("uuid 每次不同且非空", () => {
    const a = uuid();
    const b = uuid();
    expect(a).toBeTruthy();
    expect(a).not.toBe(b);
  });
});
