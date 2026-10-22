package com.villagerdetails.mixin.villager.sync;

import com.villagerdetails.network.sync.clientbound.VillagerBedSyncChannel;
import com.villagerdetails.network.sync.clientbound.WorkstationSyncChannel;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.Objects;

/**
 * 村民「记忆 → 客户端视图」同步的变化检测。
 *
 * <p>床（{@code HOME}）和工作站点（{@code JOB_SITE}）共用<b>同一次</b> tick 注入：
 * 两个记忆各自记一份「上次已发送的位置」，只有变了才发包，避免每 tick 刷屏。
 * 初始那一份由 {@code network.sync.event} 里的 START_TRACKING 事件负责，这里只补变化。
 */
@Mixin(Villager.class)
public class VillagerSyncMixin {

    @Unique
    private static final Logger log = LogManager.getLogger(VillagerSyncMixin.class);

    /** 记录上一次已发送的床位置，用于检测变化 */
    @Unique
    private BlockPos villagerDetails$lastBedPos;

    /** 记录上一次已发送的工作站点位置，用于检测变化 */
    @Unique
    private BlockPos villagerDetails$lastWorkstationPos;

    /**
     * 注入 Villager 每帧 tick 的末尾
     * 通过 brain.getMemory(HOME / JOB_SITE) 读取当前记忆，对比上次发送的位置，
     * 有变化时才发包，避免重复网络流量
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void onTick(CallbackInfo ci) {
        Villager villager = (Villager) (Object) this;

        // 只在服务端执行
        if (!(villager.level() instanceof ServerLevel)) {
            return;
        }

        // ---- 床：从 Brain 的 HOME 记忆里读当前床位置（公开 API，不需要反射）----
        BlockPos currentBedPos = VillagerBedSyncChannel.bedPosOf(villager);
        if (!Objects.equals(currentBedPos, villagerDetails$lastBedPos)) {
            villagerDetails$lastBedPos = currentBedPos;
            sendBedUpdate(villager, currentBedPos);
        }

        // ---- 工作站点：JOB_SITE 记忆（GlobalPos），只取坐标 ----
        BlockPos currentWorkstationPos = WorkstationSyncChannel.workstationPosOf(villager);
        if (!Objects.equals(currentWorkstationPos, villagerDetails$lastWorkstationPos)) {
            villagerDetails$lastWorkstationPos = currentWorkstationPos;
            sendWorkstationUpdate(villager, currentWorkstationPos);
        }
    }

    @Unique
    private void sendBedUpdate(Villager villager, BlockPos bedPos) {
        Collection<ServerPlayer> viewers = PlayerLookup.tracking(villager);
        for (ServerPlayer viewer : viewers) {
            log.debug("UUID:{},bedPos:{}", villager.getUUID(), bedPos);
            // 走同步通道（自带「对方装没装本 mod」判断）
            VillagerBedSyncChannel.INSTANCE.sendBed(viewer, villager.getId(), bedPos);
        }
    }

    @Unique
    private void sendWorkstationUpdate(Villager villager, BlockPos workstationPos) {
        Collection<ServerPlayer> viewers = PlayerLookup.tracking(villager);
        for (ServerPlayer viewer : viewers) {
            log.debug("UUID:{},workstationPos:{}", villager.getUUID(), workstationPos);
            WorkstationSyncChannel.INSTANCE.sendWorkstation(viewer, villager.getId(), workstationPos);
        }
    }
}
