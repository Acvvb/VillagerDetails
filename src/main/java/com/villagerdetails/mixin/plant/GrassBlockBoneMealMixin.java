package com.villagerdetails.mixin.plant;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.VegetationFeatures;
import net.minecraft.data.worldgen.placement.VegetationPlacements;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.GrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Mixin(GrassBlock.class)
public abstract class GrassBlockBoneMealMixin {

    /** ★ 主世界花丛配置——想加更多花丛往这里加 */
    @Unique
    private static final List<ResourceKey<ConfiguredFeature<?, ?>>> OVERWORLD_FLOWER_KEYS = List.of(
            VegetationFeatures.FLOWER_DEFAULT,          // 蒲公英 / 虞美人
            VegetationFeatures.FLOWER_FLOWER_FOREST,    // 繁花森林（多种花）
            VegetationFeatures.FLOWER_SWAMP,            // 沼泽（兰花）
            VegetationFeatures.FLOWER_PLAIN,           // 平原（郁金香 / 矢车菊 / 滨菊）
            VegetationFeatures.FLOWER_MEADOW            // 草甸（矢车菊 / 雏菊 / 铃兰）
    );

    // ==============================================================
    // HEAD 拦截
    // ==============================================================

    @Inject(method = "performBonemeal", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$boneMealOverride(ServerLevel level, RandomSource random,
                                                  BlockPos pos, BlockState state,
                                                  CallbackInfo ci) {
        // 规则未开 → 走原版
        if (!RuleCache.isEnabled(RuleType.VILLAGER_BONE_MEAL_FLOWER)) return;

        // 非主世界 → 走原版
        if (level.dimension() != Level.OVERWORLD) return;

        // 拦截原版，用自己的实现
        ci.cancel();
        performOverworldBonemeal(level, random, pos);
    }


    //原版刷花逻辑
    @Unique
    private static void performOverworldBonemeal(ServerLevel level, RandomSource random, BlockPos pos) {
        BlockPos above = pos.above();
        BlockState grass = Blocks.SHORT_GRASS.defaultBlockState();
        Optional<Holder.Reference<PlacedFeature>> grassFeature = level.registryAccess()
                .lookupOrThrow(Registries.PLACED_FEATURE)
                .get(VegetationPlacements.GRASS_BONEMEAL);

        // 主世界花列表
        List<ConfiguredFeature<?, ?>> flowers = resolveOverworldFlowers(level);

        label48:
        for (int j = 0; j < 128; ++j) {
            BlockPos testPos = above;

            for (int i = 0; i < j / 16; ++i) {
                testPos = testPos.offset(
                        random.nextInt(3) - 1,
                        (random.nextInt(3) - 1) * random.nextInt(3) / 2,
                        random.nextInt(3) - 1);

                if (!level.getBlockState(testPos.below()).is(Blocks.GRASS_BLOCK)
                        || level.getBlockState(testPos).isCollisionShapeFullBlock(level, testPos)) {
                    continue label48;
                }
            }

            BlockState testState = level.getBlockState(testPos);

            if (testState.is(grass.getBlock()) && random.nextInt(10) == 0) {
                BonemealableBlock unrepealable = (BonemealableBlock) grass.getBlock();
                if (unrepealable.isValidBonemealTarget(level, testPos, testState)) {
                    unrepealable.performBonemeal(level, random, testPos, testState);
                }
            }

            // 该位置是空气 → 生成花或草
            if (testState.isAir() && !level.isOutsideBuildHeight(testPos)) {
                if (random.nextInt(8) == 0) {
                    // ★★★ 这一句换成固定主世界花列表
                    if (!flowers.isEmpty()) {
                        ConfiguredFeature<?, ?> feature = Util.getRandom(flowers, random);
                        feature.place(level, level.getChunkSource().getGenerator(), random, testPos);
                    }
                } else if (grassFeature.isPresent()) {
                    grassFeature.get().value().place(
                            level, level.getChunkSource().getGenerator(), random, testPos);
                }
            }
        }
    }

    @Unique
    private static List<ConfiguredFeature<?, ?>> resolveOverworldFlowers(ServerLevel level) {
        var registry = level.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE);
        List<ConfiguredFeature<?, ?>> list = new ArrayList<>();

        for (ResourceKey<ConfiguredFeature<?, ?>> key : OVERWORLD_FLOWER_KEYS) {
            registry.get(key).ifPresent(holder -> list.add(holder.value()));
        }
        return list;
    }
}