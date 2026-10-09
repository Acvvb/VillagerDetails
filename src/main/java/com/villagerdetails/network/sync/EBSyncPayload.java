package com.villagerdetails.network.sync;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/**
 * 服务端 → 客户端：本机玩家当前选中的实体 / 方块（任一可为 null）。
 * 每次选择变化都推一次完整状态，客户端不做增量计算。
 */
public final class EBSyncPayload extends SyncPayload<EBSyncPayload> {

    public static final PayloadId<EBSyncPayload> ID = PayloadId.of("eb_sync");

    public static final StreamCodec<RegistryFriendlyByteBuf, EBSyncPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.entity() != null);
                if (payload.entity() != null) buf.writeUUID(payload.entity());
                buf.writeBoolean(payload.block() != null);
                if (payload.block() != null) buf.writeBlockPos(payload.block());
            },
            buf -> {
                UUID entity = buf.readBoolean() ? buf.readUUID() : null;
                BlockPos block = buf.readBoolean() ? buf.readBlockPos() : null;
                return new EBSyncPayload(entity, block);
            }
    );

    private final UUID entity;
    private final BlockPos block;

    public EBSyncPayload(UUID entity, BlockPos block) {
        super(ID);
        this.entity = entity;
        this.block = block;
    }

    public UUID entity() {
        return entity;
    }

    public BlockPos block() {
        return block;
    }
}