package com.villagerdetails.pojo.dto;

import com.villagerdetails.network.codec.ModCodecs;
import com.villagerdetails.network.sync.payload.clientbound.WorkstationPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.Optional;

/**
 * 村民工作站点同步的内容载体（{@link WorkstationPayload} 的第二个字段）。
 *
 * <p>和 {@code VillagerPacket} 同款结构：一个村民 id + 一个可空坐标
 * （{@code Optional.empty()} = 这个村民当前没有工作站点，客户端应当把记录删掉）。
 *
 * <p>缓冲类型用 {@link FriendlyByteBuf} 就够（外面的大包再套成 {@code RegistryFriendlyByteBuf}），
 * 这样 {@code BlockPos.STREAM_CODEC} 这类以 {@code ByteBuf} 为缓冲的原版 codec 能直接用。
 */
public record WorkstationPacket(int villagerId, Optional<BlockPos> workstationPos) {

    public static final StreamCodec<FriendlyByteBuf, WorkstationPacket> PACKET_CODEC =
            StreamCodec.composite(
                    ModCodecs.VAR_INT, WorkstationPacket::villagerId,
                    ModCodecs.optionalOf(BlockPos.STREAM_CODEC), WorkstationPacket::workstationPos,
                    WorkstationPacket::new);

}
