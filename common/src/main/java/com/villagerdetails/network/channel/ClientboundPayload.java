package com.villagerdetails.network.channel;

/**
 * <b>服务端 → 客户端</b>的同步包。实现它就等于声明「我是 S→C 方向的包」。
 *
 * <p>方向不再靠「包放在哪个子目录」猜：{@code PayloadTypes} 扫包时看类实现的是
 * {@link ClientboundPayload} 还是 {@link ServerboundPayload}，然后调对应接口的
 * {@link #register(Class)} —— 「实现哪个接口就调哪个方法」。
 *
 * <p>实现类的约定（和以前一样，只是现在由接口把「登记」这一步收拢了）：
 * <pre>
 * public final class XxxPayload extends SyncPayload&lt;XxxPayload&gt; implements ClientboundPayload {
 *     public static final PayloadId&lt;XxxPayload&gt; ID = PayloadId.of("xxx");
 *     public static final StreamCodec&lt;RegistryFriendlyByteBuf, XxxPayload&gt; CODEC = ...;
 * }
 * </pre>
 *
 * <p>方法为什么是 {@code static}：包类都带构造参数（要塞数据），没法像其它扫包角色那样
 * 「实例化再调实例方法」，所以这里用「接口静态方法 + 实现类的 Class」的形式。
 */
public interface ClientboundPayload {

    /**
     * 把这个实现类登记进 S→C 的包类型表；由 {@code PayloadTypes} 扫包时调用。
     *
     * @param payloadClass 实现了本接口的包类
     */
    static void register(Class<?> payloadClass) {
        PayloadRegistration.register(payloadClass, true);
    }
}
