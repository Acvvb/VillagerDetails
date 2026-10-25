package com.villagerdetails.network.sync.payload.serverbound;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerboundPayload;
import com.villagerdetails.network.channel.SyncPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 客户端 → 服务端：请求清空本机玩家的 EB（实体 + 方块）选区（配置界面的触发按钮用）。
 *
 * <p>没有字段，所以编解码用 {@code StreamCodec.unit}。
 * 包 id：{@code villagerdetails:eb_clear}
 */
public final class EBClearPayload extends SyncPayload<EBClearPayload> implements ServerboundPayload {

    public static final PayloadId<EBClearPayload> ID = PayloadId.of("eb_clear");

    public static final EBClearPayload INSTANCE = new EBClearPayload();

    public static final StreamCodec<RegistryFriendlyByteBuf, EBClearPayload> CODEC =
            StreamCodec.unit(INSTANCE);

    private EBClearPayload() {
        super(ID);
    }
}