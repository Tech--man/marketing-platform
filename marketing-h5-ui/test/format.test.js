import { describe, it, expect } from "vitest";
import {
  splitYuan,
  yuan,
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
    expect(splitYuan(null)).toEqual({ int: "0", dec: "00" });
  });
  it("yuan 千分位分组，带符号", () => {
    expect(yuan(1234567.891)).toBe("¥1,234,567.89");
    expect(yuan(0, { sign: false })).toBe("0.00");
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
