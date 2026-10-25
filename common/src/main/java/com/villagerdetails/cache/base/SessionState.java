package com.villagerdetails.cache.base;

import java.util.UUID;

/**
 * 「只活在一次联机会话里」的缓存的生命周期契约（<b>两端共用</b>）。
 *
 * <p>实现此接口的类都是「断线 / 换局之后必须重来」的东西：客户端是断线 / 切世界，
 * 服务端是玩家退出 / 停服。清不干净的直接后果就是下一局读到上一局的数据
 * （换服后画着旧服的选区框、实体 id 复用连到错的床、上一局的「同意名单」继续生效……）。
 *
 * <p><b>接入方式</b>（两条都要，缺一不可）：
 * <ol>
 *     <li>类放在扫包认识的 impl 包里：客户端 {@code com.villagerdetails.client.cache.impl}
 *         （由 {@code ClientSessionStates} 扫），服务端 {@code com.villagerdetails.cache.impl}
 *         （由 {@code ServerSessionStates} 扫）；</li>
 *     <li>给一个 {@code public static final Xxx INSTANCE} + 私有构造器（本项目统一写法）——
 *         扫包框架靠它拿实例，拿不到就退回无参构造。</li>
 * </ol>
 *
 * <p>写法和调用约定：<b>全部走实例</b>，即 {@code Xxx.INSTANCE.get()/set()}，不再同时提供静态版本
 * —— 清理调的是实例方法，两套并存时最容易出现「清的是这份、读的是那份」。
 */
public interface SessionState {

    /**
     * 整份清空：客户端断线 / 切世界、服务端停服时由扫包框架统一调用。
     *
     * <p>实现必须做到「清完再进服，和刚启动时一模一样」：数据清空，
     * 「做过一次就别再做」这类记忆（已通知标记、已请求标记）也要一起清。
     */
    void clearSession();

    /**
     * 服务端专用：某个玩家退出时摘掉属于他的那部分数据。
     *
     * <p>客户端缓存没有「按玩家」的概念（整份都是本机的），默认什么都不做。
     */
    default void clearPlayer(UUID playerUuid) {
    }
}
