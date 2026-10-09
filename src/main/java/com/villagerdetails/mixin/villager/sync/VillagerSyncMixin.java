package com.villagerdetails.mixin.villager.sync;

import com.villagerdetails.network.sync.clientbound.VillagerBedSyncChannel;
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

@Mixin(Villager.class)
public class VillagerSyncMixin {

    @Unique
    private static final Logger log = LogManager.getLogger(VillagerSyncMixin.class);

    /** 记录上一次已发送的床位置，用于检测变化 */
    @Unique
    private BlockPos villagerDetails$lastBedPos;

    /**
     * 注入 Villager 每帧 tick 的末尾
     * 通过 brain.getMemory(HOME) 读取当前床记忆，对比上次发送的位置，
     * 有变化时才发包，避免重复网络流量
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void onTick(CallbackInfo ci) {
        Villager villager = (Villager) (Object) this;

        // 只在服务端执行
        if (!(villager.level() instanceof ServerLevel)) {
            return;
        }

        // 从 Brain 的 HOME 记忆里读当前床位置（公开 API，不需要反射）
        BlockPos currentPos = VillagerBedSyncChannel.bedPosOf(villager);

        // 对比上次记录的床位置，有变化才发包
        if (!Objects.equals(currentPos, villagerDetails$lastBedPos)) {
            villagerDetails$lastBedPos = currentPos;
            sendUpdate(villager, currentPos);
        }
    }

    @Unique
    private void sendUpdate(Villager villager, BlockPos bedPos) {
        Collection<ServerPlayer> viewers = PlayerLookup.tracking(villager);
        for (ServerPlayer viewer : viewers) {
            log.debug("UUID:{},bedPos:{}", villager.getUUID(), bedPos);
            // 走同步通道（自带「对方装没装本 mod」判断）
            VillagerBedSyncChannel.INSTANCE.sendBed(viewer, villager.getId(), bedPos);
        }
    }

}