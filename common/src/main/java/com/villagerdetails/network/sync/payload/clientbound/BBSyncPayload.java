package com.villagerdetails.network.sync.payload.clientbound;

import com.villagerdetails.network.channel.ClientboundPayload;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 服务端 → 客户端：本机玩家 BB 选择器的两个角点（任一可为 null）。
 */
public final class BBSyncPayload extends SyncPayload<BBSyncPayload> implements ClientboundPayload {

    public static final PayloadId<BBSyncPayload> ID = PayloadId.of("bb_sync");

    public static final StreamCodec<RegistryFriendlyByteBuf, BBSyncPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.pos1() != null);
                if (payload.pos1() != null) buf.writeBlockPos(payload.pos1());
                buf.writeBoolean(payload.pos2() != null);
                if (payload.pos2() != null) buf.writeBlockPos(payload.pos2());
            },
            buf -> {
                BlockPos a = buf.readBoolean() ? buf.readBlockPos() : null;
                BlockPos b = buf.readBoolean() ? buf.readBlockPos() : null;
                return new BBSyncPayload(a, b);
            }
    );

    private final BlockPos pos1;
    private final BlockPos pos2;

    public BBSyncPayload(BlockPos pos1, BlockPos pos2) {
        super(ID);
        this.pos1 = pos1;
        this.pos2 = pos2;
    }

    public BlockPos pos1() {
        return pos1;
    }

    public BlockPos pos2() {
        return pos2;
    }
}