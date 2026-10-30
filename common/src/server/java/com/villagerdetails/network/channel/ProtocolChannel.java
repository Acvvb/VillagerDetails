package com.villagerdetails.network.channel;

/**
 * <b>协议级通道</b>的标记接口：实现了它的 {@link SyncChannel} 子类表示
 * 「这条通道是版本握手 / 能力协商一类的系统级通信，<b>不受客户端静音设置影响</b>」。
 *
 * <p>它本身没有任何方法 —— 真正决定行为的是 {@link SyncChannel#isProtocolChannel()}。
 * 「标记」和「钩子」看起来重复，是有意的：
 * <ul>
 *     <li><b>标记接口</b>是「人类可读、可被检索」的声明：{@code grep ProtocolChannel} 就能列出
 *         全项目有哪些协议通道，不用进每个类的实现里翻；</li>
 *     <li><b>钩子</b>是运行期真正被读的那个布尔值。</li>
 * </ul>
 *
 * <p>两者必须一致，否则就是「协议通道被漏标」或者「钩子写错」。这个一致性由
 * {@link SyncChannel} 在<b>启动自检</b>时强制校验：不一致会直接抛
 * {@link IllegalStateException}，由 {@code ServerChannels} 汇总成一条 ERROR ——
 * 把「握手包被静音吞掉」这种运行期静默失效，提前成启动期就能看见的报错。
 *
 * <p>正确写法：
 * <pre>{@code
 * public final class HandshakeSyncChannel extends SyncChannel<HandshakePayload> implements ProtocolChannel {
 *     public static final HandshakeSyncChannel INSTANCE = new HandshakeSyncChannel();
 *
 *     private HandshakeSyncChannel() {
 *         super(HandshakePayload.ID);
 *     }
 *
 *     @Override
 *     protected boolean isProtocolChannel() {
 *         return true;
 *     }
 * }
 * }</pre>
 *
 * <p>忘了写 {@code isProtocolChannel()} 的 override 时，启动日志会出现一条明确指名的 ERROR，
 * 而不是等玩家把通道静音之后才发现握手没了。
 */
public interface ProtocolChannel {
}
