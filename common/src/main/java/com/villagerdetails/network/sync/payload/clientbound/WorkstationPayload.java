package com.villagerdetails.network.sync.payload.clientbound;

import com.villagerdetails.network.channel.ClientboundPayload;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import com.villagerdetails.pojo.dto.WorkstationPacket;import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 服务端 → 客户端：某个村民当前绑定的工作站点（用于画线）。
 *
 * <p>包 id：{@code villagerdetails:workstation_sync}。
 *
 * <p>放在 {@code network.sync.payload.clientbound} 包下 = {@code PayloadTypes} 扫包时
 * 自动登记包类型，不用手动注册（手动再登记一次会因重复注册抛异常）。
 * 服务端侧的发送通道在同名子包 {@code network.sync.clientbound} 里。
 */
public final class WorkstationPayload extends SyncPayload<WorkstationPayload> implements ClientboundPayload {

    public static final PayloadId<WorkstationPayload> ID = PayloadId.of("workstation_sync");

    public static final StreamCodec<RegistryFriendlyByteBuf, WorkstationPayload> CODEC =
            StreamCodec.composite(
                    WorkstationPacket.PACKET_CODEC, WorkstationPayload::data,
                    WorkstationPayload::new);

    private final WorkstationPacket data;

    public WorkstationPayload(WorkstationPacket data) {
        super(ID);
        this.data = data;
    }

    public WorkstationPacket data() {
        return data;
    }
}
