package com.villagerdetails.mixin;

import com.villagerdetails.network.VillagerBedPayload;
import com.villagerdetails.network.VillagerPacket;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Mixin(Villager.class)
public class VillagerMixin {

    @Unique
    private static final Logger log = LogManager.getLogger(VillagerMixin.class);

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

        Brain<?> brain = villager.getBrain();
        if (brain == null) {
            return;
        }

        // 通过 Brain 的 getMemory 方法读取 HOME 记忆（这是公开 API，不需要反射）
        Optional<GlobalPos> currentBedOpt = brain.getMemory(MemoryModuleType.HOME);
        BlockPos currentPos = currentBedOpt.map(GlobalPos::pos).orElse(null);

        // 对比上次记录的床位置，有变化才发包
        if (!Objects.equals(currentPos, villagerDetails$lastBedPos)) {
            villagerDetails$lastBedPos = currentPos;
            sendUpdate(villager, currentPos);
        }
    }

    @Unique
    private void sendUpdate(Villager villager, BlockPos bedPos) {
        Set<ServerPlayer> viewers = (Set<ServerPlayer>) PlayerLookup.tracking(villager);
        for (ServerPlayer viewer : viewers) {
            log.debug("UUID:{},bedPos:{}", villager.getUUID(), bedPos);
            ServerPlayNetworking.send(
                    viewer,
                    new VillagerBedPayload(new VillagerPacket(villager.getId(), Optional.ofNullable(bedPos)))
            );
        }
    }

    /**
     * 每次补货后剩余可用次数 = maxUses 的倍数。
     * 1.0 = 原版（补满到 maxUses）
     * 2.0 = 2 倍
     * 3.0 = 3 倍
     */
    @Unique
    private static final float MULTIPLIER = 2.0F;

    @Redirect(
            method = "restock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/trading/MerchantOffer;resetUses()V"
            )
    )
    private void customResetUses(MerchantOffer offer) {
        MerchantOfferMixin acc = (MerchantOfferMixin) offer;
        int maxUses = acc.getMaxUses();
        int newUses = -(int) ((MULTIPLIER - 1.0F) * maxUses);
        acc.setUses(newUses);
    }
}