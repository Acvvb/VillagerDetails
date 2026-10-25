package com.villagerdetails.network.sync.clientbound;

import com.villagerdetails.network.channel.SyncChannel;
import com.villagerdetails.network.sync.payload.clientbound.WorkstationPayload;
import com.villagerdetails.pojo.dto.WorkstationPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Optional;

/**
 * 村民工作站点同步：服务端 → 客户端。
 *
 * <p>放在 {@code network.sync.clientbound} 包下 = 自动注册成 S→C 通道，不用手动登记，
 * 同时也会被 {@code ServerChannels} 的「通道体检」扫到（体检要求对应的包定义在 payload 目录里，
 * 那边已经有了）。
 *
 * <p>三处触发点：
 * <ul>
 *     <li>玩家开始追踪这个村民（实体进视距）→ {@code network.sync.event.WorkstationSyncEvents}</li>
 *     <li>村民 tick 时工作站点变了 → {@code mixin.villager.sync.VillagerSyncMixin}
 *         （和床记忆共用同一次 tick 注入，不额外加一次 tick 开销）</li>
 *     <li>村民卸载时的客户端缓存清理 → {@code client.network.sync.event.WorkstationClientChannel}</li>
 * </ul>
 *
 * <p>包 id：{@code villagerdetails:workstation_sync}
 */
public class WorkstationSyncChannel extends SyncChannel<WorkstationPayload> {

    public static final WorkstationSyncChannel INSTANCE = new WorkstationSyncChannel();

    protected WorkstationSyncChannel() {
        super(WorkstationPayload.ID);
    }

    /**
     * 把这个村民当前绑的工作站点发给某个玩家（{@code pos} 为空表示没有工作站点）
     */
    public void sendWorkstation(ServerPlayer player, int villagerId, BlockPos pos) {
        sendTo(player, new WorkstationPayload(new WorkstationPacket(villagerId, Optional.ofNullable(pos))));
    }

    /**
     * 从村民大脑的 JOB_SITE 记忆里取工作站点坐标；没有返回 null（tick 时判断变没变也用它）。
     *
     * <p>注意：{@code JOB_SITE} 存的是 {@link GlobalPos}（带维度），这里只取坐标 ——
     * 跨维度的村民本来就不会被同一个玩家追踪到，取坐标足够用。
     */
    public static BlockPos workstationPosOf(Villager villager) {
        Brain<?> brain = villager.getBrain();
        return brain.getMemory(MemoryModuleType.JOB_SITE).map(GlobalPos::pos).orElse(null);
    }
}
