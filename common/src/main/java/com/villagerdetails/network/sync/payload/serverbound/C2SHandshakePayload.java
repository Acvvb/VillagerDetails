package com.villagerdetails.network.sync.payload.serverbound;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerboundPayload;
import com.villagerdetails.network.channel.SyncPayload;
import com.villagerdetails.network.codec.ModCodecs;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 客户端 → 服务端：<b>版本握手</b>。客户端连上之后报一次自己的模组版本。
 *
 * <p>为什么单独开一个包，而不是往已有的 {@code rule_descriptor_hello} 里加字段：
 * 那个包的编码形状（协议版本 + 语言）已经被旧客户端用着了，加字段会让<b>旧客户端发来的包被
 * 新服务端按新形状解码</b> —— 多读一个字段就是读越界 / 读到垃圾，连接直接断，
 * 甚至可能让对面出崩溃报告。新开一个包是纯增量：老客户端不会发它（服务端视作「版本未知」），
 * 也不影响任何现有包的解析。
 *
 * <p>只有 {@code modVersion} 一个字段，就是
 * {@code FabricLoader.getModContainer("villagerdetails").getMetadata().getVersion().getFriendlyString()}
 * 的原文（例如 {@code 1.1.0+26.2}）。<b>不在客户端做任何比较</b>：客户端只负责如实上报，
 * 判断兼容性、要不要踢人是服务端的事（服务端才是版本权威）。
 *
 * <p>包 id：{@code villagerdetails:handshake}
 */
public final class C2SHandshakePayload extends SyncPayload<C2SHandshakePayload> implements ServerboundPayload {

    public static final PayloadId<C2SHandshakePayload> ID = PayloadId.of("handshake");

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SHandshakePayload> CODEC =
            StreamCodec.composite(
                    ModCodecs.STRING, C2SHandshakePayload::modVersion,
                    C2SHandshakePayload::new);

    /**
     * 客户端上报的模组版本原文；null 统一收成空串，避免编解码时 NPE
     */
    private final String modVersion;

    public C2SHandshakePayload(String modVersion) {
        super(ID);
        this.modVersion = modVersion == null ? "" : modVersion;
    }

    /**
     * 模组版本原文，例如 {@code 1.1.0+26.2}；拿不到时是空串
     */
    public String modVersion() {
        return modVersion;
    }
}
