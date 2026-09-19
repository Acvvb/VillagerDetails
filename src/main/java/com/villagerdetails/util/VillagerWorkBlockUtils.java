package com.villagerdetails.util;

import com.villagerdetails.cache.SelectionState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.UUID;

public class VillagerWorkBlockUtils {

    private static final Logger log = LogManager.getLogger(VillagerWorkBlockUtils.class);
    private static final String NOT_WORK_BLOCK_KEY = "msg.villager_bed.work_block.not_work_block";
    private static final String CROSS_DIM_KEY = "msg.villager_bed.work_block.cross_dimension";

    /**
     * 绑定村民到工作方块
     */
    public static boolean bindVillagerWorkBlock(ServerLevel level, ServerPlayer operator, UUID villagerUuid, BlockPos workBlockPos) {
        Entity entity = level.getEntity(villagerUuid);
        if (!(entity instanceof Villager villager)) {
            SendMessengerUtils.sendOrBroadcastActionBar(operator,
                    Component.translatable("msg.villager_bed.work_block.bind.fail"));
            return false;
        }

        // 复用通用维度校验
        if (!BindingToolUtils.checkSameDimension(villager, level, operator, CROSS_DIM_KEY)) {
            return false;
        }

        // 校验是否是工作方块
        BlockState state = level.getBlockState(workBlockPos);
        Optional<Holder<PoiType>> poiHolder = PoiTypes.forState(state);
        if (poiHolder.isEmpty() || !isWorkPoiType(poiHolder.get())) {
            String blockName = state.getBlock().getName().getString();
            SendMessengerUtils.sendOrBroadcastActionBar(operator,
                    Component.translatable(NOT_WORK_BLOCK_KEY, blockName));
            log.warn("玩家点击的方块不是工作方块，方块名称: {}，位置: {}", blockName, workBlockPos);
            return false;
        }

        // ===== 以下为新增的核心逻辑 =====

        // 1. 已交易过的村民职业锁定，无法更改
        if (villager.getVillagerXp() > 0) {
            SendMessengerUtils.sendOrBroadcastActionBar(operator,
                    Component.translatable("msg.villager_bed.work_block.traded"));
            return false;
        }

        // 2. 根据 POI 类型获取目标职业
        ResourceKey<VillagerProfession> newProfession = getProfessionByPoiType(poiHolder.get());
        if (newProfession == null) {
            return false;
        }

        // 3. 清除村民当前的职业记忆
        villager.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
        villager.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);

        // 4. 释放村民当前绑定的 POI（如果有）
        level.getEntitiesOfClass(Villager.class,
                        new AABB(workBlockPos).inflate(48),  // 搜索半径48格
                        v -> {
                            Optional<GlobalPos> jobSite = v.getBrain().getMemory(MemoryModuleType.JOB_SITE);
                            return jobSite.isPresent() && jobSite.get().pos().equals(workBlockPos);
                        })
                .forEach(oldVillager -> {
                    oldVillager.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
                    oldVillager.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
                    oldVillager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                    log.info("已清除旧村民 {} 对工作方块 {} 的记忆", oldVillager.getUUID(), workBlockPos);
                });

        // 5. 先释放目标方块上的旧占用（release 是幂等的，没有占用时返回 false 不会报错）
        boolean released = level.getPoiManager().release(workBlockPos);
        if (released) {
            log.info("已释放工作方块 {} 上原有的村民占用", workBlockPos);
        }

        // 6. 设置新职业
        villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), newProfession));

        // 10. 【关键】将工作方块注册为当前村民的POI
        level.getPoiManager().add(workBlockPos, poiHolder.get());

        // 11. 【关键】设置村民大脑记忆（使用 setMemory 而非 remember）
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                Optional.of(GlobalPos.of(level.dimension(), workBlockPos)));
        villager.getBrain().setMemory(MemoryModuleType.POTENTIAL_JOB_SITE,
                Optional.of(GlobalPos.of(level.dimension(), workBlockPos)));

        // 7. 刷新交易列表（新增！）
        villager.refreshBrain(level);

        // 8. 更新自定义缓存
        SelectionState.setSelectedWorkBlock(villagerUuid, workBlockPos);

        // 9. 发送绿色粒子效果
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                villager.getX(), villager.getY() + 1, villager.getZ(),
                10, 0.5, 0.5, 0.5, 0.1);

        log.info("玩家 {} 成功将村民 {} 的职业绑定为 {}，工作方块位置: {}",
                operator.getUUID(), villagerUuid, newProfession, workBlockPos);

        SendMessengerUtils.sendOrBroadcastActionBar(operator,
                Component.translatable("msg.villager_bed.work_block.bind.success",
                        villagerUuid.toString().substring(0, 8),
                        workBlockPos.toShortString()));

        return true;
    }

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
        var registry = net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION;
        for (var profession : registry) {
            if (profession.heldJobSite().test(poiHolder)) return true;
        }
        return false;
    }
}