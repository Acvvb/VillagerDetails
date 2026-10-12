package com.villagerdetails.network.sync.payload.serverbound;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 客户端 → 服务端：请求清空本机玩家的 BB 选区（配置界面的触发按钮用）。
 *
 * <p>没有字段，所以编解码用 {@code StreamCodec.unit}。
 * 包 id：{@code villagerdetails:bb_clear}
 */
public final class BBClearPayload extends SyncPayload<BBClearPayload> {

    public static final PayloadId<BBClearPayload> ID = PayloadId.of("bb_clear");

    public static final BBClearPayload INSTANCE = new BBClearPayload();

    public static final StreamCodec<RegistryFriendlyByteBuf, BBClearPayload> CODEC =
            StreamCodec.unit(INSTANCE);

    private BBClearPayload() {
        super(ID);
    }
}