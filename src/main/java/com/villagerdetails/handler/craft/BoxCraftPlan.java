package com.villagerdetails.handler.craft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
//? if >=26.3 {
/*import net.minecraft.util.Prediction;
*///?}
import net.minecraft.world.Container;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次盒子合成的方案：记录输入盒数、总产出、产物盒数等，
 * 并负责执行（工作台 / 合成器两条路径）。
 * <p>
 * 工具方法（fillBox / clearBox / addRawItems / findEmptyBoxes 等）由
 * {@link BoxCraftingHandler} 提供，同包下直接调用。
 */
public final class BoxCraftPlan {

    public final int boxedSlots;
    public final int boxItemCount;
    public final int outputBoxes;
    public final int remainder;
    public final int totalOutput;
    public final Item outItem;
    public final int outMax;
    public final int perRunCount;

    BoxCraftPlan(int boxedSlots, int boxItemCount, int outputBoxes, int remainder,
                 int totalOutput, Item outItem, int outMax, int perRunCount) {
        this.boxedSlots = boxedSlots;
        this.boxItemCount = boxItemCount;
        this.outputBoxes = outputBoxes;
        this.remainder = remainder;
        this.totalOutput = totalOutput;
        this.outItem = outItem;
        this.outMax = outMax;
        this.perRunCount = perRunCount;
    }

    /**
     * 结果槽预览：有产物盒时显示 1 盒，否则显示 1 堆散货。
     */
    public ItemStack preview() {
        if (outputBoxes > 0) {
            return BoxCraftingHandler.fillBox(new ItemStack(Items.SHULKER_BOX), outItem, outMax);
        }
        return new ItemStack(outItem, Math.min(totalOutput, outMax));
    }

    // ============================================================
    // 工作台版本
    // ============================================================

    public void execute(ServerPlayer player, CraftingContainer container) {
        List<ItemStack> grid = container.getItems();
        List<ItemStack> inputBoxes = BoxCraftingHandler.collectInputBoxes(grid);

        int emptyNeeded = Math.max(0, outputBoxes - boxedSlots);
        List<ItemStack> emptyBoxes = emptyNeeded > 0
                ? BoxCraftingHandler.findEmptyBoxes(player.getInventory(), emptyNeeded)
                : List.of();

        CraftResult result = buildOutput(inputBoxes, emptyBoxes);

        // 产物给玩家
        for (ItemStack out : result.output()) {
            if (!player.getInventory().add(out)) {
                //? if >=26.3 {
                /*player.drop(out, false, Prediction.SERVER_ONLY);
                *///?} else {
                player.drop(out, false);
                //?}
            }
        }

        // 清空输入盒
        BoxCraftingHandler.clearInputBoxes(grid, container::setItem);

        // 消耗背包里被用掉的空盒
        BoxCraftingHandler.consumeEmptyBoxes(emptyBoxes, result.consumedEmptyBoxes());
    }

    // ============================================================
    // 合成器版本
    // ============================================================

    public void executeForCrafter(ServerLevel level, BlockPos targetPos, CrafterBlockEntity crafter) {
        List<ItemStack> grid = crafter.getItems();
        List<ItemStack> inputBoxes = BoxCraftingHandler.collectInputBoxes(grid);

        Container targetContainer = BoxCraftingHandler.getContainerAt(level, targetPos);

        int emptyNeeded = Math.max(0, outputBoxes - boxedSlots);
        List<ItemStack> emptyBoxes = (emptyNeeded > 0 && targetContainer != null)
                ? BoxCraftingHandler.findEmptyBoxes(targetContainer, emptyNeeded)
                : List.of();

        CraftResult result = buildOutput(inputBoxes, emptyBoxes);

        // 清空 Crafter 槽里的输入盒
        BoxCraftingHandler.clearInputBoxes(grid, crafter::setItem);

        // 消耗从目标容器拉来的空盒
        BoxCraftingHandler.consumeEmptyBoxes(emptyBoxes, result.consumedEmptyBoxes());

        // 产物推向前方容器，溢出掉落
        for (ItemStack out : result.output()) {
            if (targetContainer != null) {
                ItemStack remaining = BoxCraftingHandler.insertIntoContainer(targetContainer, out);
                if (!remaining.isEmpty()) {
                    Block.popResource(level, targetPos, remaining);
                }
            } else {
                Block.popResource(level, targetPos, out);
            }
        }

        if (targetContainer != null) {
            targetContainer.setChanged();
        }
        crafter.setChanged();
    }

    // ============================================================
    // 实例方法：用到 outputBoxes / boxedSlots / outItem / outMax / totalOutput
    // ============================================================

    /**
     * 构建产物列表：
     * 1. 优先用输入盒的壳来装产物；
     * 2. 不够再用 emptyBoxes 的壳；
     * 3. 多余的壳（输入盒 > 产物盒，如 2 合 1）清空后作为空盒返回；
     * 4. 装不下的产物以散货形式追加。
     */
    private CraftResult buildOutput(List<ItemStack> inputBoxes, List<ItemStack> emptyBoxes) {
        List<ItemStack> shells = new ArrayList<>(inputBoxes);
        shells.addAll(emptyBoxes);

        int filledBoxes = Math.min(outputBoxes, shells.size());

        List<ItemStack> output = new ArrayList<>();
        // 装满的产物盒
        for (int i = 0; i < filledBoxes; i++) {
            output.add(BoxCraftingHandler.fillBox(shells.get(i).copy(), outItem, outMax));
        }
        // 多余的壳清空后返回（输入盒 > 产物盒的情况）
        for (int i = filledBoxes; i < shells.size(); i++) {
            output.add(BoxCraftingHandler.clearBox(shells.get(i).copy()));
        }
        // 装不下的产物 + 余数 → 散货
        int boxCapacity = BoxCraftingHandler.BOX_SLOTS * outMax;
        int unboxed = totalOutput - filledBoxes * boxCapacity;
        BoxCraftingHandler.addRawItems(output, outItem, unboxed, outMax);

        // 消耗的空盒 = 填充的盒数 - 输入盒的壳数
        int consumedEmpty = Math.max(0, filledBoxes - boxedSlots);
        return new CraftResult(output, consumedEmpty);
    }

    /**
     * 产物 + 消耗的空盒数。
     */
    private record CraftResult(List<ItemStack> output, int consumedEmptyBoxes) {
    }
}