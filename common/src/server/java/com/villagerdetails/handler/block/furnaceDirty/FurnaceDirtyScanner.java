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
 * 「脏熔炉」判定：扫一个区块里已加载的方块实体，算出哪些该高亮。
 *
 * <p>只判定输入槽（槽 0）：<b>非空、但按这个熔炉自己的配方表找不到能烧它的配方</b>就高亮。
 * 燃料槽与输出槽都不参与（燃料位不检测）。
 * 配方表按方块实体类型分派：普通熔炉 {@code SMELTING}、高炉 {@code BLASTING}、烟熏炉 {@code SMOKING}。
 *
 * <p><b>扫描粒度是区块</b>：{@link #scanChunk} 只遍历这个区块自己的方块实体表，
 * 成本只和「区块里的方块实体数」有关。谁负责调、多久调一次、结果怎么下发，
 * 全在 {@link FurnaceDirtyTracker}（全局脏表 + 按视距增量同步）里。
 */
public final class FurnaceDirtyScanner {

    /**
     * 输入槽（私有：只有本类的判定用得到）
     */
    private static final int SLOT_INPUT = 0;

    private FurnaceDirtyScanner() {
    }

    /**
     * 扫一个区块里的脏熔炉。
     *
     * @param level 区块所在的服务端世界（查配方要用）
     * @param chunk 已加载的区块；{@code null} 返回空
     * @return 脏熔炉坐标；按坐标排序，保证同样的内容每次生成的包字节稳定
     */
    public static List<BlockPos> scanChunk(ServerLevel level, LevelChunk chunk) {
        if (level == null || chunk == null) {
            return List.of();
        }
        var blockEntities = chunk.getBlockEntities();
        if (blockEntities.isEmpty()) {
            return List.of();
        }
        List<BlockPos> dirty = new ArrayList<>();
        for (BlockEntity blockEntity : blockEntities.values()) {
            if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace) || blockEntity.isRemoved()) {
                continue;
            }
            if (isInputDirty(level, furnace)) {
                dirty.add(blockEntity.getBlockPos());
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
        return switch (furnace) {
            case FurnaceBlockEntity _ -> !hasRecipe(level, RecipeType.SMELTING, input);
            case BlastFurnaceBlockEntity _ -> !hasRecipe(level, RecipeType.BLASTING, input);
            case SmokerBlockEntity _ -> !hasRecipe(level, RecipeType.SMOKING, input);
            default -> false;
        };
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
