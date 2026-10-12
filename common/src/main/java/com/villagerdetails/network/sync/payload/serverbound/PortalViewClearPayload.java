package com.villagerdetails.network.sync.payload.serverbound;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 客户端 → 服务端：请求清空本机玩家的传送门视图高亮（配置界面的触发按钮用）。
 *
 * <p>没有字段，所以编解码用 {@code StreamCodec.unit}。
 * 包 id：{@code villagerdetails:portal_view_clear}
 */
public final class PortalViewClearPayload extends SyncPayload<PortalViewClearPayload> {

    public static final PayloadId<PortalViewClearPayload> ID = PayloadId.of("portal_view_clear");

    public static final PortalViewClearPayload INSTANCE = new PortalViewClearPayload();

    public static final StreamCodec<RegistryFriendlyByteBuf, PortalViewClearPayload> CODEC =
            StreamCodec.unit(INSTANCE);

    private PortalViewClearPayload() {
        super(ID);
    }
}
