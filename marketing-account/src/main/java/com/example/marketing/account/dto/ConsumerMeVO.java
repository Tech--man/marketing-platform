package com.example.marketing.account.dto;

/**
 * "我是谁"。只回已验签的身份字段 + 展示字段，不含口令、不含 token、不含剩余寿命
 * （那些字段留在 claims 里，暴露给前端没有用途，只多一个可泄露的东西）。
 */
public record ConsumerMeVO(long uid, String identifier, String nickname, String status) {
}
