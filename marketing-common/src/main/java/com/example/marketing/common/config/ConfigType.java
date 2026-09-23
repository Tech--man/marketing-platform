package com.example.marketing.common.config;

/**
 * 可在线改的值类型。只有这三种：布尔一旦出现就要回答"未知"是什么，
 * 那是比 true/false 更麻烦的第三种状态，留到有真实需求时再加。
 */
public enum ConfigType {
    INT, LONG, STRING
}
