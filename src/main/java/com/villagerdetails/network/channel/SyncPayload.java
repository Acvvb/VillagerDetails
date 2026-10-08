package com.villagerdetails.network.channel;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jspecify.annotations.NonNull;

/**
 * 所有同步包的基类。
 *
 * <p>以前每个包都要抄三样东西：{@code PACKET_ID}、{@code TYPE = new Type<>(PACKET_ID)}、
 * {@code type()} 覆写。现在这些都收到基类里，包只要：
 * <ol>
 *     <li>声明一个 {@code public static final PayloadId<XxxPayload> ID = PayloadId.of("xxx");}</li>
 *     <li>声明自己的 {@code CODEC}</li>
 *     <li>构造里 {@code super(ID)} 一句</li>
 * </ol>
 *
 * <p>泛型参数是「自己的类型」：{@code class XxxPayload extends SyncPayload<XxxPayload>}，
 * 这样 {@code payloadId()} / {@code type()} 都能返回精确类型，不用到处强转。
 */
public abstract class SyncPayload<P extends SyncPayload<P>> implements CustomPacketPayload {

    private final PayloadId<P> payloadId;

    protected SyncPayload(PayloadId<P> payloadId) {
        this.payloadId = payloadId;
    }

    /** 这个包的身份证（id + Type） */
    public final PayloadId<P> payloadId() {
        return payloadId;
    }

    /** 接口要求的方法，基类实现一次就够了，子类不用再管 */
    @Override
    public final @NonNull Type<? extends CustomPacketPayload> type() {
        return payloadId.type();
    }
}
