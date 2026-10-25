package com.villagerdetails.network.channel;

/**
 * <b>客户端 → 服务端</b>的同步包。实现它就等于声明「我是 C→S 方向的包」。
 *
 * <p>和 {@link ClientboundPayload} 完全对称：{@code PayloadTypes} 扫包时按「实现哪个接口」
 * 分派到对应接口的 {@link #register(Class)}，不再看包在哪个子目录。
 *
 * <p>实现类的约定：
 * <pre>
 * public final class XxxPayload extends SyncPayload&lt;XxxPayload&gt; implements ServerboundPayload {
 *     public static final PayloadId&lt;XxxPayload&gt; ID = PayloadId.of("xxx");
 *     public static final StreamCodec&lt;RegistryFriendlyByteBuf, XxxPayload&gt; CODEC = ...;
 * }
 * </pre>
 */
public interface ServerboundPayload {

    /**
     * 把这个实现类登记进 C→S 的包类型表；由 {@code PayloadTypes} 扫包时调用。
     *
     * @param payloadClass 实现了本接口的包类
     */
    static void register(Class<?> payloadClass) {
        PayloadRegistration.register(payloadClass, false);
    }
}
