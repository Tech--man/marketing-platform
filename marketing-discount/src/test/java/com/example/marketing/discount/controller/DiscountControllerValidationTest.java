package com.example.marketing.discount.controller;

import com.example.marketing.common.exception.GlobalExceptionHandler;
import com.example.marketing.common.security.ConsumerClaims;
import com.example.marketing.common.security.ConsumerRequestIdentity;
import com.example.marketing.common.security.ConsumerTokenCodec;
import com.example.marketing.discount.domain.CalcInput;
import com.example.marketing.discount.service.DiscountCalcService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 客户端把字段名写错时，必须拿到 40000 而不是 50000。
 *
 * <p>触发这件事很平常：{@code unitPrice} 拼成 {@code price} 就是一个 null。
 * 加了约束之前它一路走到 {@code CalcItem.amount()} 抛 NPE，被 catch-all 兜成
 * "系统繁忙，请稍后再试" —— 客户端错误被说成服务器忙，会把人引向完全错误的排查方向。
 * 这与 ⑤ 修掉的"请求体解析失败返回 50000"是同一族问题。</p>
 */
class DiscountControllerValidationTest {

    private static final String SECRET = "unit-test-consumer-secret";
    private final DiscountCalcService calcService = mock(DiscountCalcService.class);
    private final ConsumerRequestIdentity identity =
            new ConsumerRequestIdentity(SECRET, java.time.Duration.ofSeconds(30));
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new DiscountController(calcService, identity))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private String access(long uid) {
        long now = java.time.Instant.now().getEpochSecond();
        return new ConsumerTokenCodec(SECRET, java.time.Duration.ofSeconds(30))
                .issue(new ConsumerClaims(uid, "u" + uid, "jti-" + uid,
                        ConsumerClaims.TYPE_ACCESS, now, now + 900));
    }

    @Test
    @DisplayName("缺 unitPrice → 40000 并点名是哪个字段，且根本没进计算")
    void missingUnitPriceIs40000() throws Exception {
        mvc.perform(post("/api/discount/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"activityNo\":\"ACT1\","
                                + "\"items\":[{\"lineId\":\"L1\",\"skuId\":9,\"itemId\":7,\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("unitPrice")));

        verify(calcService, never()).calculate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("数量为 0 → 40000（负/零数量会把行小计算成非正数）")
    void zeroQuantityIs40000() throws Exception {
        mvc.perform(post("/api/discount/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"items\":[{\"lineId\":\"L1\",\"unitPrice\":10.00,"
                                + "\"quantity\":0}]}"))
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("空购物车 → 40000，而不是走进计算后返回全零结果")
    void emptyCartIs40000() throws Exception {
        mvc.perform(post("/api/discount/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"items\":[]}"))
                .andExpect(jsonPath("$.code").value(40000));
        verify(calcService, never()).calculate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("请求体里塞 userId 不生效：进计算引擎的那一个必须是 token 里验出来的")
    void bodyUserIdIsIgnoredAndReplacedByIdentity() throws Exception {
        // @JsonIgnore 让反序列化根本不收 userId，控制器再填上验过的那一个。
        // 这条断言同时钉住两个失败模式：字段没收紧（客户端自报），和忘了填（引擎拿到 null）
        mvc.perform(post("/api/discount/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Token", access(70001L))
                        .content("{\"userId\":999,\"items\":[{\"lineId\":\"L1\","
                                + "\"unitPrice\":10.00,\"quantity\":2}]}"))
                .andExpect(jsonPath("$.code").value(0));

        org.mockito.ArgumentCaptor<CalcInput> cap = org.mockito.ArgumentCaptor.forClass(CalcInput.class);
        verify(calcService).calculate(cap.capture());
        assertEquals(70001L, cap.getValue().getUserId(), "自报的 999 必须被丢掉");
    }

    @Test
    @DisplayName("H9：自报 userTags 被覆写为空集——人群折扣不能靠请求体冒领")
    void selfDeclaredUserTagsAreOverwritten() throws Exception {
        // 与 userId 同族的第二条身份输入：引擎里 user:MEMBER 规则按 userTags 命中，
        // 自报 ["MEMBER"] 在收口前等于给任意登录用户发会员价。
        mvc.perform(post("/api/discount/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Token", access(70001L))
                        .content("{\"userTags\":[\"MEMBER\",\"NEW\"],\"items\":[{\"lineId\":\"L1\","
                                + "\"unitPrice\":10.00,\"quantity\":2}]}"))
                .andExpect(jsonPath("$.code").value(0));

        org.mockito.ArgumentCaptor<CalcInput> cap = org.mockito.ArgumentCaptor.forClass(CalcInput.class);
        verify(calcService).calculate(cap.capture());
        assertEquals(true, cap.getValue().getUserTags().isEmpty(),
                "进引擎的 userTags 必须是空集，出现 MEMBER 就是回归");
    }

    @Test
    @DisplayName("没登录就打算价口 → 40100，而不是拿 null userId 算出一套游客价")
    void anonymousCalcIsRejected() throws Exception {
        mvc.perform(post("/api/discount/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"lineId\":\"L1\",\"unitPrice\":10.00,\"quantity\":2}]}"))
                .andExpect(jsonPath("$.code").value(40100));
        verify(calcService, never()).calculate(org.mockito.ArgumentMatchers.any());
    }
}
