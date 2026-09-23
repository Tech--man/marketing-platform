package com.example.marketing.discount.controller;

import com.example.marketing.common.exception.GlobalExceptionHandler;
import com.example.marketing.discount.service.DiscountCalcService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
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

    private final DiscountCalcService calcService = mock(DiscountCalcService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new DiscountController(calcService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

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
}
