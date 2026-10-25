package com.villagerdetails.network.sync.payload.clientbound;

import com.villagerdetails.network.channel.ClientboundPayload;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 服务端 → 客户端：把某条传送门绑定的一侧区域推给客户端做「视图高亮」。
 *
 * <p>一个包只装<b>一侧</b>。{@code /ec c PortalBinding view} 会把绑定的两侧各发一个包，
 * 客户端按维度分别记住（见 {@code PortalViewClientState}），玩家进哪个维度就画哪一侧。
 *
 * <p>三个字段全 {@code null} 表示清除高亮。维度用 {@code Identifier} 字符串序列化
 * （和 {@code PortalBindingConfig} 落盘口径一致，最稳）。
 *
 * <p>包 id：{@code villagerdetails:portal_view_sync}
 */
public final class PortalViewSyncPayload extends SyncPayload<PortalViewSyncPayload> implements ClientboundPayload {

    public static final PayloadId<PortalViewSyncPayload> ID = PayloadId.of("portal_view_sync");

    public static final StreamCodec<RegistryFriendlyByteBuf, PortalViewSyncPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                boolean empty = payload.dimension() == null;
                buf.writeBoolean(empty);
                if (!empty) {
                    buf.writeUtf(payload.dimension().identifier().toString());
                    buf.writeBlockPos(payload.min());
                    buf.writeBlockPos(payload.max());
                }
            },
            buf -> {
                if (buf.readBoolean()) {
                    return new PortalViewSyncPayload(null, null, null);
                }
                Identifier id = Identifier.tryParse(buf.readUtf());
                ResourceKey<Level> dim = id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
                BlockPos min = buf.readBlockPos();
                BlockPos max = buf.readBlockPos();
                return new PortalViewSyncPayload(dim, min, max);
            }
    );

    private final ResourceKey<Level> dimension;
    private final BlockPos min;
    private final BlockPos max;

    public PortalViewSyncPayload(ResourceKey<Level> dimension, BlockPos min, BlockPos max) {
        super(ID);
        this.dimension = dimension;
        this.min = min;
        this.max = max;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public BlockPos min() {
        return min;
    }

    public BlockPos max() {
        return max;
    }
}