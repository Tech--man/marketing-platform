package com.example.marketing.common.config;

import java.util.List;

/**
 * 参数自述：每个模块声明"我有哪些参数可在线改、边界多少、出厂值多少"。
 *
 * <p>形状照 {@code CacheReheater}：能力长在参数所属的模块，后台只渲染与校验，
 * 不持有第二套清单。声明了却没有被任何 {@code configValues.xxxOr()} 消费的键，
 * 就是后台里一个"点了没反应"的按钮——那种键不要声明。</p>
 */
public interface ConfigDefinitionProvider {

    /**
     * 参数所属的<b>模块名</b>，不是进程名：LITE 下 discount 的参数跑在 marketing-standalone
     * 进程里，但归属仍是 marketing-discount，后台要显示后者。
     *
     * <p>Redis 自述键的后缀用的是 {@code spring.application.name}（进程名）——一个进程一份自述，
     * 而每条声明在载荷里额外带一个 {@code owner} 模块名。</p>
     */
    String service();

    List<ConfigDefinition> definitions();
}
