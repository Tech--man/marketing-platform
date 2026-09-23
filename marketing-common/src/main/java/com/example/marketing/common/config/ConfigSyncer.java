package com.example.marketing.common.config;

/**
 * 配置同步器的标记接口：进程内只要存在任一实现，common 的阻塞轮询器就让位。
 *
 * <p>存在的理由是一处实测事实：{@code spring-boot-starter-data-redis-reactive} 会把
 * spring-data-redis 的核心也带进来，于是<b>网关里其实有 {@code StringRedisTemplate} bean</b>
 * （"网关只有 reactive 模板"说的是它用什么，不是它的 classpath 里缺什么）。少了这个标记，
 * 阻塞轮询器会在网关里也起一个线程，与网关自己的 reactive 同步器同时喂同一份生效值——
 * 两份节拍、两套连接，而看起来都只是"能工作"。</p>
 */
public interface ConfigSyncer {
}
