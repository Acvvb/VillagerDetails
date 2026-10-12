package com.villagerdetails.client.cache;

/**
 * 「只活在本此联机会话里」的客户端状态：断线时必须清空，免得下次进别的服务器还留着上一份。
 *
 * <p>以前每个通道各注册一个 {@code ClientPlayConnectionEvents.DISCONNECT} 回调来清自己那份状态，
 * 四个地方互相看不见，新加一个视图状态很容易漏（症状就是「换服之后还留着上一个服的框」）。
 * 现在统一登记到 {@link ClientSessionStates}，全局只有一处断线回调。
 *
 * <p>实现方式就是方法引用（{@code BBSelectionClientState::clear}），
 * 所以这些状态类本身不用 implements 什么，静态清理方法照旧。
 */
@FunctionalInterface
public interface ClientSessionState {

    /**
     * 断线 / 会话结束时清空自己这份状态
     */
    void clearOnSessionEnd();
}
