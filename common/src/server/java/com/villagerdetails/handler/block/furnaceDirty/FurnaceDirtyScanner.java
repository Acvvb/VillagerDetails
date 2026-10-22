package com.villagerdetails.handler.block.furnaceDirty;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 「脏熔炉」判定：服务端扫描玩家附近已加载区块里的熔炉，算出哪些该高亮。
 *
 * <p>只判定输入槽（槽 0）：<b>非空、但按这个熔炉自己的配方表找不到能烧它的配方</b>就高亮。
 * 燃料槽与输出槽都不参与（燃料位不检测）。
 * 配方表按方块实体类型分派：普通熔炉 {@code SMELTING}、高炉 {@code BLASTING}、烟熏炉 {@code SMOKING}。
 *
 * <p><b>扫描方式</b>：只取玩家附近<b>已加载</b>的区块（{@code getChunkNow}，不加载、不生成），
 * 直接遍历区块自己的方块实体表 —— 不按方块坐标遍历，成本只和「半径内的方块实体数」有关。
 *
 * <p>不缓存任何东西：每次调用都是一次独立快照，规则关闭时不调用（事件里就 return 了）。
 */
public final class FurnaceDirtyScanner {

    /**
     * 输入槽
     */
    public static final int SLOT_INPUT = 0;

    private FurnaceDirtyScanner() {
    }

    /**
     * 扫一遍玩家附近的脏熔炉。
     *
     * @param level  玩家所在的服务端世界
     * @param center 扫描中心（一般传玩家脚下坐标）
     * @param radius 半径（格），只在已加载区块里找
     * @return 脏熔炉坐标；按坐标排序，保证同样的内容每次生成的包字节稳定
     */
    public static List<BlockPos> scan(ServerLevel level, BlockPos center, int radius) {
        int minChunkX = Math.floorDiv(center.getX() - radius, 16);
        int maxChunkX = Math.floorDiv(center.getX() + radius, 16);
        int minChunkZ = Math.floorDiv(center.getZ() - radius, 16);
        int maxChunkZ = Math.floorDiv(center.getZ() + radius, 16);

        List<BlockPos> dirty = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                // 只查已加载的区块：玩家没去过的地方不会因为扫描而被加载 / 生成
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace) || blockEntity.isRemoved()) {
                        continue;
                    }
                    if (!blockEntity.getBlockPos().closerThan(center, radius)) {
                        continue;
                    }
                    if (isInputDirty(level, furnace)) {
                        dirty.add(blockEntity.getBlockPos());
                    }
                }
            }
        }
        dirty.sort(Comparator.comparingLong(BlockPos::asLong));
        return dirty;
    }

    /**
     * 输入槽里有东西，但这条熔炉的配方表里找不到能烧它的配方。
     *
     * <p>只认原版三个熔炉方块实体类型：其它 {@code AbstractFurnaceBlockEntity} 子类（模组熔炉）
     * 配方表不一定挂在这三个 {@code RecipeType} 上，猜一个反而会误报，所以直接跳过。
     */
    private static boolean isInputDirty(ServerLevel level, AbstractFurnaceBlockEntity furnace) {
        ItemStack input = furnace.getItem(SLOT_INPUT);
        if (input.isEmpty()) {
            return false;
        }
        if (furnace instanceof FurnaceBlockEntity) {
            return !hasRecipe(level, RecipeType.SMELTING, input);
        }
        if (furnace instanceof BlastFurnaceBlockEntity) {
            return !hasRecipe(level, RecipeType.BLASTING, input);
        }
        if (furnace instanceof SmokerBlockEntity) {
            return !hasRecipe(level, RecipeType.SMOKING, input);
        }
        return false;
    }

    /**
     * 输入物品在这类熔炉的配方表里有没有配方。
     *
     * <p>泛型收成 {@code Recipe<SingleRecipeInput>}：三个烹饪配方类型都实现这个输入形态，
     * 这样不用为每种配方写一遍 {@code getRecipeFor} 的显式类型参数。
     */
    private static <T extends Recipe<SingleRecipeInput>> boolean hasRecipe(
            ServerLevel level, RecipeType<T> type, ItemStack input) {
        return level.recipeAccess().getRecipeFor(type, new SingleRecipeInput(input), level).isPresent();
    }
}
