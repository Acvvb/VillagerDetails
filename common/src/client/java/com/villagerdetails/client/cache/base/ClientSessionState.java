package com.villagerdetails.client.cache.base;

import com.villagerdetails.client.cache.ClientSessionStates;

/**
 * 客户端会话状态（缓存）的生命周期契约。
 *
 * <p>实现此接口的类就是「只活在一次联机里」的东西：断线 / 切世界时必须整体清空，
 * 否则下一局会读到上一局留下的数据（换服后画着旧服的选区框、实体 id 复用连到错的床……）。
 *
 * <p><b>接入方式</b>（两条都要，缺一不可）：
 * <ol>
 *     <li>类放在 {@code com.villagerdetails.client.cache.impl} 包下 —— {@link ClientSessionStates}
 *         只扫这个包；</li>
 *     <li>提供一个 {@code public static final Xxx INSTANCE}（本项目统一写法）和私有构造器 ——
 *         扫包框架靠它拿实例，拿不到就退回无参构造。</li>
 * </ol>
 *
 * <p>写法和调用约定：<b>全部走实例</b>，即 {@code XxxClientState.INSTANCE.get()/set()/clear()}，
 * 不再提供同名的静态版本 —— 会话清理调的是实例方法，两套并存时最容易出现
 * 「清的是这份、读的是那份」。
 */
public interface ClientSessionState {

    /**
     * 断线 / 切换世界时清空；由 {@link ClientSessionStates#clearAll()} 统一调用。
     *
     * <p>实现必须做到「清完再进服，和刚启动时一模一样」：数据清空，请求标记 / 已通知标记
     * 这类"做过一次就别再做"的记忆也要一起清，否则下次进服会误以为已经同步过。
     */
    void clearSession();
}
