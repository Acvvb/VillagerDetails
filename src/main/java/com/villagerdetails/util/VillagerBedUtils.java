package com.villagerdetails.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * 村民床绑定工具类
 * <p>
 * 功能：手动更改村民绑定的床，不影响村民的寻路机制
 * </p>
 *
 * @author your-name
 */
public class VillagerBedUtils {

    private static final Logger log = LogManager.getLogger(VillagerBedUtils.class);

    /**
     * 通过 UUID 更改村民绑定的床（推荐方式）
     */
    public static boolean changeVillagerBed(ServerLevel level, ServerPlayer operator, UUID villagerUuid, BlockPos newBedPos) {
        Entity entity = level.getEntity(villagerUuid);
        if (!(entity instanceof Villager villager)) {
            return false;
        }
        return changeVillagerBedSync(level, operator, villager, villagerUuid, newBedPos);
    }

    /**
     * 通过实体ID(int) 更改村民绑定的床
     */
    public static boolean changeVillagerBed(ServerLevel level, ServerPlayer operator, int villagerId, BlockPos newBedPos) {
        Entity entity = level.getEntity(villagerId);
        if (!(entity instanceof Villager villager)) {
            return false;
        }
        return changeVillagerBedSync(level, operator, villager, villager.getUUID(), newBedPos);
    }

    /**
     * 通过 Villager 实体直接更改绑定的床
     */
    public static boolean changeVillagerBed(ServerLevel level, ServerPlayer operator, Villager villager, BlockPos newBedPos) {
        return changeVillagerBedSync(level, operator, villager, villager.getUUID(), newBedPos);
    }

    private static boolean changeVillagerBedSync(ServerLevel level, ServerPlayer operator, Villager villager, UUID villagerUuid, BlockPos newBedPos) {

        // 验证目标位置是否为床
        BlockPos bedHeadPos = findBedHead(level,operator, newBedPos);
        if (bedHeadPos == null) return false;

        // 检查是否已经绑定过这张床
        if (checkDuplicateBinding(villager, villagerUuid, bedHeadPos, level, operator)) return true;

        // 检查是否同维度
        if (!isSameDimension(villager, level, operator)) return false;

        // 清除其他村民对目标床的绑定
        clearOtherVillagersBedBinding(level, villagerUuid, bedHeadPos);

        // Poi
        if (releaseOldPoiAndRegisterNew(villager, level, bedHeadPos).isEmpty()) {
            SendMessengerUtils.sendOrBroadcastActionBar(operator, Component.translatable("msg.villager_bed.bind.fail"));
            return false;
        }

        // 更新村民记忆
        villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bedHeadPos));

        // 发送成功消息
        SendMessengerUtils.sendOrBroadcastActionBar(operator, Component.translatable("msg.villager_bed.bind.success",
                villagerUuid.toString(), bedHeadPos.toShortString()));

        return true;
    }

    /**
     * 清除其他村民对指定床的绑定
     */
    private static void clearOtherVillagersBedBinding(ServerLevel level, UUID targetVillagerUuid, BlockPos bedPos) {
        MinecraftServer server = level.getServer();
        for (ServerLevel otherLevel : server.getAllLevels()) {
            for (Entity entity : otherLevel.getAllEntities()) {
                if (entity instanceof Villager otherVillager && !otherVillager.getUUID().equals(targetVillagerUuid)) {
                    Optional<GlobalPos> otherHome = otherVillager.getBrain().getMemory(MemoryModuleType.HOME);
                    if (otherHome.isPresent() && otherHome.get().pos().equals(bedPos)) {
                        otherVillager.releasePoi(MemoryModuleType.HOME);
                        otherVillager.getBrain().eraseMemory(MemoryModuleType.HOME);
                        log.debug("已清除村民 {} 对床 {} 的绑定", otherVillager.getUUID(), bedPos);
                    }
                }
            }
        }
    }

    /**
     * 验证指定位置是否为床，并返回床头坐标
     */
    private static BlockPos findBedHead(ServerLevel level,ServerPlayer operator, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        if (!state.is(BlockTags.BEDS)) {
            SendMessengerUtils.sendOrBroadcastActionBar(operator,
                    Component.translatable("msg.villager_bed.not_a_bed",state.getBlock().getName().getString()));
            return null;
        }

        Object partObj = state.getValue(BedBlock.PART);
        if (partObj == BedPart.HEAD) return pos;

        return pos.relative(state.getValue(BedBlock.FACING));
    }

    /**
     * 检查村民是否已经绑定了目标床
     */
    private static boolean checkDuplicateBinding(Villager villager, UUID villagerUuid, BlockPos bedHeadPos, ServerLevel level, ServerPlayer operator) {
        Optional<GlobalPos> currentHomeOpt = villager.getBrain().getMemory(MemoryModuleType.HOME);
        if (currentHomeOpt.isPresent()) {
            GlobalPos currentHome = currentHomeOpt.get();
            // 维度相同 且 坐标相同 = 真正重复绑定
            if (currentHome.dimension().equals(level.dimension()) && currentHome.pos().equals(bedHeadPos)) {
                SendMessengerUtils.sendOrBroadcastActionBar(operator, Component.translatable("msg.villager_bed.already_bound",
                        villagerUuid.toString()));
                return true;
            }
        }
        return false;
    }

    /**
     * 校验是否允许跨维度绑定
     */
    private static boolean isSameDimension(Villager villager, ServerLevel targetLevel, ServerPlayer operator) {
        ResourceKey<Level> villagerDimension = villager.level().dimension();
        ResourceKey<Level> targetDimension = targetLevel.dimension();
        if (villagerDimension.equals(targetDimension)) return true;
        SendMessengerUtils.sendOrBroadcastActionBar(operator, Component.translatable("msg.villager_bed.cross_dimension"));
        return false;
    }

    /**
     * 释放村民旧床的 POI 占用，并确保新床的 POI 已注册
     */
    private static Optional<Holder<PoiType>> releaseOldPoiAndRegisterNew(Villager villager, ServerLevel level, BlockPos bedHeadPos) {
        villager.releasePoi(MemoryModuleType.HOME);

        PoiManager poiManager = level.getPoiManager();
        Optional<Holder<PoiType>> holderOpt = poiManager.getType(bedHeadPos);

        if (holderOpt.isEmpty()) {
            holderOpt = PoiTypes.forState(level.getBlockState(bedHeadPos));
            holderOpt.ifPresent(poiTypeHolder -> poiManager.add(bedHeadPos, poiTypeHolder));
        }

        return holderOpt;
    }
}