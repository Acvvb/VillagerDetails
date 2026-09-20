
package com.villagerdetails.util.entity;

import com.villagerdetails.cache.SelectionState;
import com.villagerdetails.event.type.BindingType;
import com.villagerdetails.util.BindingToolUtils;
import com.villagerdetails.util.SendMessengerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 村民绑定工具类
 * <p>
 * 功能：统一管理村民床绑定和工作方块绑定逻辑
 * </p>
 */
public class VillagerBindingUtils {

    private static final Logger log = LogManager.getLogger(VillagerBindingUtils.class);

    // ==================== 公共入口 ====================

    /**
     * 根据 BindingType 执行村民绑定（床 / 工作方块）
     *
     * @param level        服务端世界
     * @param operator     操作玩家
     * @param villagerUuid 村民 UUID
     * @param targetPos    目标方块位置（床头或工作方块）
     * @param type         绑定类型（BED / WORK_BLOCK）
     * @return 绑定是否成功
     */
    public static boolean bindVillager(ServerLevel level, ServerPlayer operator, UUID villagerUuid, BlockPos targetPos, BindingType type) {

        Villager villager = (Villager) level.getEntity(villagerUuid);
        if (villager == null) return false;
        // 维度校验
        if (BindingToolUtils.checkSameDimension(villager, level, operator, getMessageKey(type, "cross_dimension"))) return false;

        return event(level, operator, villagerUuid, targetPos, type, villager);
    }

    private static boolean bindAction(ServerLevel level, ServerPlayer operator, UUID villagerUuid, BlockPos targetPos, BindingType type) {
        Entity entity = level.getEntity(villagerUuid);
        if (!(entity instanceof Villager villager)) {
            SendMessengerUtils.sendOrBroadcastActionBar(operator,
                    Component.translatable(getMessageKey(type, "bind.fail")));
            return false;
        }
        // 复用通用维度校验
        if (BindingToolUtils.checkSameDimension(villager, level, operator, getMessageKey(type, "cross_dimension"))) {
            return false;
        }

        return event(level, operator, villagerUuid, targetPos, type, villager);
    }


    private static boolean event(ServerLevel level, ServerPlayer operator, UUID villagerUuid, BlockPos targetPos, BindingType type, Villager villager) {
        return switch (type) {
            case BED -> bindBed(level, operator, villager, villagerUuid, targetPos);
            case WORK_BLOCK -> bindWorkBlock(level, operator, villager, villagerUuid, targetPos);
        };
    }

    /**
     * 通过 UUID 更改村民绑定的床
     */
    public static boolean changeVillagerBed(ServerLevel level, ServerPlayer operator, UUID villagerUuid, BlockPos blockPos, BindingType type) {
        Entity entity = level.getEntity(villagerUuid);
        if (!(entity instanceof Villager villager)) {
            return false;
        }
        return bindAction(level, operator, villager.getUUID(), blockPos, type);
    }

    /**
     * 通过实体ID(int) 更改村民绑定的床
     */
    public static boolean changeVillagerBed(ServerLevel level, ServerPlayer operator, int villagerId, BlockPos blockPos, BindingType type) {
        Entity entity = level.getEntity(villagerId);
        if (!(entity instanceof Villager villager)) {
            return false;
        }
        return bindAction(level, operator, villager.getUUID(), blockPos, type);
    }

    /**
     * 通过 Villager 实体直接更改绑定的床
     */
    public static boolean changeVillagerBed(ServerLevel level, ServerPlayer operator, Villager villager, BlockPos blockPos, BindingType type) {
        return bindAction(level, operator, villager.getUUID(), blockPos, type);
    }

    /**
     * 通过 UUID 更改村民绑定的工作方块
     */
    public static boolean changeVillagerWorkBlock(ServerLevel level, ServerPlayer operator, UUID villagerUuid, BlockPos blockPos, BindingType type) {
        Entity entity = level.getEntity(villagerUuid);
        if (!(entity instanceof Villager villager)) {
            return false;
        }
        return bindAction(level, operator, villager.getUUID(), blockPos, type);
    }

    /**
     * 通过实体ID(int) 更改村民绑定的工作方块
     */
    public static boolean changeVillagerWorkBlock(ServerLevel level, ServerPlayer operator, int villagerId, BlockPos blockPos, BindingType type) {
        Entity entity = level.getEntity(villagerId);
        if (!(entity instanceof Villager villager)) {
            return false;
        }
        return bindAction(level, operator, villager.getUUID(), blockPos, type);
    }

    /**
     * 通过 Villager 实体直接更改绑定的工作方块
     */
    public static boolean changeVillagerWorkBlock(ServerLevel level, ServerPlayer operator, Villager villager, BlockPos blockPos, BindingType type) {
        return bindAction(level, operator, villager.getUUID(), blockPos, type);
    }

    // ==================== 床绑定逻辑 ====================

    private static boolean bindBed(ServerLevel level, ServerPlayer operator, Villager villager, UUID villagerUuid, BlockPos newBedPos) {
        // 验证目标位置是否为床，并返回床头坐标
        BlockPos bedHeadPos = findBedHead(level, operator, newBedPos);
        if (bedHeadPos == null) {
            return false;
        }

        // 检查是否已经绑定过这张床
        if (checkDuplicateBinding(villager, villagerUuid, bedHeadPos, level, operator)) {
            return true;
        }

        // 清除其他村民对目标床的绑定
        clearOtherVillagersBedBinding(level, villagerUuid, bedHeadPos);

        // 释放村民旧床的 POI 占用
        villager.releasePoi(MemoryModuleType.HOME);

        // 确保新床的 POI 已注册
        Optional<Holder<PoiType>> holderOpt = registerBedPoi(level, bedHeadPos);
        if (holderOpt.isEmpty()) {
            SendMessengerUtils.sendOrBroadcastActionBar(operator,
                    Component.translatable(getMessageKey(BindingType.BED, "bind.fail")));
            return false;
        }

        // 设置村民的床记忆
        villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bedHeadPos));

        // 发送成功消息
        SendMessengerUtils.sendOrBroadcastActionBar(operator,
                Component.translatable(getMessageKey(BindingType.BED, "bind.success"),
                        villagerUuid.toString(), bedHeadPos.toShortString()));

        return true;
    }

    /**
     * 验证指定位置是否为床，并返回床头坐标
     */
    private static BlockPos findBedHead(ServerLevel level, ServerPlayer operator, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        if (!state.is(BlockTags.BEDS)) {
            SendMessengerUtils.sendOrBroadcastActionBar(operator,
                    Component.translatable(getMessageKey(BindingType.BED, "not_a_bed"),
                            state.getBlock().getName().getString()));
            return null;
        }

        Object partObj = state.getValue(BedBlock.PART);
        if (partObj == BedPart.HEAD) {
            return pos;
        }

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
                SendMessengerUtils.sendOrBroadcastActionBar(operator,
                        Component.translatable(getMessageKey(BindingType.BED, "already_bound"),
                                villagerUuid.toString()));
                return true;
            }
        }
        return false;
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
     * 确保床的 POI 已注册（如果未注册则注册）
     */
    private static Optional<Holder<PoiType>> registerBedPoi(ServerLevel level, BlockPos bedHeadPos) {
        PoiManager poiManager = level.getPoiManager();
        Optional<Holder<PoiType>> holderOpt = poiManager.getType(bedHeadPos);

        if (holderOpt.isEmpty()) {
            holderOpt = PoiTypes.forState(level.getBlockState(bedHeadPos));
            holderOpt.ifPresent(poiTypeHolder -> poiManager.add(bedHeadPos, poiTypeHolder));
        }

        return holderOpt;
    }

    // ==================== 工作方块绑定逻辑 ====================

    private static boolean bindWorkBlock(ServerLevel level, ServerPlayer operator, Villager villager, UUID villagerUuid, BlockPos workBlockPos) {
        // 验证目标位置是否为工作方块，并返回POI类型
        Optional<Holder<PoiType>> poiHolderOpt = findWorkBlockPoi(level, operator, workBlockPos);
        if (poiHolderOpt.isEmpty()) return false;

        Holder<PoiType> poiHolder = poiHolderOpt.get();

        // 检查村民是否已交易过
        if (checkVillagerTraded(villager, operator)) return false;

        // 检查工作方块上已有村民是否已交易过
        if (checkWorkBlockOwnerTraded(level, operator, workBlockPos)) return false;

        // 根据POI类型获取目标职业
        ResourceKey<VillagerProfession> newProfession = getProfessionByPoiType(poiHolder);
        if (newProfession == null) return false;

        // 清除其他村民对工作方块的绑定
        clearOtherVillagersWorkBlockBinding(level, workBlockPos);

        // 释放目标方块上的旧POI占用
        releaseWorkBlockPoiOccupancy(level, workBlockPos);

        // 设置村民职业并更新POI和大脑记忆
        setVillagerProfessionAndMemories(villager, level, workBlockPos, newProfession, poiHolder);

        // 发送成功消息
        SendMessengerUtils.sendOrBroadcastActionBar(operator,
                Component.translatable(getMessageKey(BindingType.WORK_BLOCK, "bind.success"),
                        villagerUuid.toString(), workBlockPos.toShortString()));

        // 更新自定义缓存
        SelectionState.setSelectedWorkBlock(villagerUuid, workBlockPos);

        log.info("玩家 {} 成功将村民 {} 的职业绑定为 {}，工作方块位置: {}",
                operator.getUUID(), villagerUuid, newProfession, workBlockPos);

        return true;
    }

    /**
     * 验证指定位置是否为工作方块，并返回POI类型
     */
    private static Optional<Holder<PoiType>> findWorkBlockPoi(ServerLevel level, ServerPlayer operator, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Optional<Holder<PoiType>> poiHolder = PoiTypes.forState(state);
        if (poiHolder.isEmpty() || !isWorkPoiType(poiHolder.get())) {
            String blockName = state.getBlock().getName().getString();
            SendMessengerUtils.sendOrBroadcastActionBar(operator,
                    Component.translatable(getMessageKey(BindingType.WORK_BLOCK, "not_work_block"), blockName));
            log.warn("玩家点击的方块不是工作方块，方块名称: {}，位置: {}", blockName, pos);
            return Optional.empty();
        }
        return poiHolder;
    }

    /**
     * 检查村民是否已交易过（职业锁定）
     */
    private static boolean checkVillagerTraded(Villager villager, ServerPlayer operator) {
        if (villager.getVillagerXp() > 0) {
            SendMessengerUtils.sendOrBroadcastActionBar(operator,
                    Component.translatable(getMessageKey(BindingType.WORK_BLOCK, "traded")));
            return true;
        }
        return false;
    }

    /**
     * 检查工作方块上已有村民是否已交易过
     */
    private static boolean checkWorkBlockOwnerTraded(ServerLevel level, ServerPlayer operator, BlockPos workBlockPos) {
        List<Villager> boundVillagers = getVillagersBoundToWorkBlock(level, workBlockPos);
        for (Villager existingVillager : boundVillagers) {
            if (existingVillager.getVillagerXp() > 0) {
                SendMessengerUtils.sendOrBroadcastActionBar(operator,
                        Component.translatable(getMessageKey(BindingType.WORK_BLOCK, "owner_traded")));
                log.warn("工作方块 {} 所属村民 {} 已交易过，无法修改职业", workBlockPos, existingVillager.getUUID());
                return true;
            }
        }
        return false;
    }

    /**
     * 获取绑定到指定工作方块的所有村民
     */
    private static List<Villager> getVillagersBoundToWorkBlock(ServerLevel level, BlockPos workBlockPos) {
        return level.getEntitiesOfClass(Villager.class,
                new AABB(workBlockPos).inflate(48),
                v -> {
                    Optional<GlobalPos> jobSite = v.getBrain().getMemory(MemoryModuleType.JOB_SITE);
                    return jobSite.isPresent() && jobSite.get().pos().equals(workBlockPos);
                });
    }

    /**
     * 清除其他村民对工作方块的绑定
     */
    private static void clearOtherVillagersWorkBlockBinding(ServerLevel level, BlockPos workBlockPos) {
        getVillagersBoundToWorkBlock(level, workBlockPos).forEach(oldVillager -> {
            oldVillager.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
            oldVillager.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
            oldVillager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            log.debug("已清除旧村民 {} 对工作方块 {} 的记忆", oldVillager.getUUID(), workBlockPos);
        });
    }

    /**
     * 释放目标方块上的旧POI占用
     */
    private static void releaseWorkBlockPoiOccupancy(ServerLevel level, BlockPos workBlockPos) {
        boolean released = level.getPoiManager().release(workBlockPos);
        if (released) {
            log.info("已释放工作方块 {} 上原有的村民占用", workBlockPos);
        }
    }

    /**
     * 设置村民职业并更新POI和大脑记忆
     */
    private static void setVillagerProfessionAndMemories(Villager villager, ServerLevel level, BlockPos workBlockPos,
                                                         ResourceKey<VillagerProfession> profession, Holder<PoiType> poiHolder) {
        // 清除村民当前的职业记忆
        villager.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
        villager.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);

        // 设置新职业
        villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), profession));

        // 将工作方块注册为当前村民的POI
        level.getPoiManager().add(workBlockPos, poiHolder);

        // 设置村民大脑记忆
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                Optional.of(GlobalPos.of(level.dimension(), workBlockPos)));
        villager.getBrain().setMemory(MemoryModuleType.POTENTIAL_JOB_SITE,
                Optional.of(GlobalPos.of(level.dimension(), workBlockPos)));

        // 刷新交易列表
        villager.refreshBrain(level);
    }

    // ==================== 辅助方法 ====================

    /**
     * 根据 POI 类型获取对应的村民职业 ResourceKey
     */
    private static ResourceKey<VillagerProfession> getProfessionByPoiType(Holder<PoiType> poiHolder) {
        var registry = BuiltInRegistries.VILLAGER_PROFESSION;
        for (var profession : registry) {
            if (profession.heldJobSite().test(poiHolder)) {
                Identifier id = registry.getKey(profession);
                return ResourceKey.create(Registries.VILLAGER_PROFESSION, id);
            }
        }
        return null;
    }

    /**
     * 判断 POI 类型是否属于工作方块类型（排除床、家等）
     */
    private static boolean isWorkPoiType(Holder<PoiType> poiHolder) {
        var registry = BuiltInRegistries.VILLAGER_PROFESSION;
        for (var profession : registry) {
            if (profession.heldJobSite().test(poiHolder)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 消息 Key 路由 ====================

    /**
     * 根据 BindingType 获取对应的消息 key 前缀
     * BED      -> msg.villager.bed
     * WORK_BLOCK -> msg.villager.work_block
     */
    private static String getMessageKey(BindingType type, String suffix) {
        if (type == BindingType.BED) {
            return "msg.villager.bed." + suffix;
        } else if (type == BindingType.WORK_BLOCK) {
            return "msg.villager.work_block." + suffix;
        }
        return "msg.villager." + type.getRequiredToolName() + "." + suffix;
    }
}