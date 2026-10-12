package com.villagerdetails.handler.craft;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * 「盒子合成」规则：
 * 把「装满同一物品的潜影盒」当作 27 个满堆来参与合成。
 * <p>
 * 本类只负责：识别、计算方案（{@link #computePlan}）、提供共享工具方法。
 * 具体的一次合成执行在 {@link BoxCraftPlan} 里。
 */
public final class BoxCraftingHandler {

    private BoxCraftingHandler() {
    }

    /**
     * 潜影盒槽位数。package-private，供 BoxCraftPlan 访问。
     */
    static final int BOX_SLOTS = 27;

    public static boolean isEnabled() {
        return RuleCache.isEnabled(RuleType.BOX_CRAFTING);
    }

    public static boolean isShulkerBox(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getItem() instanceof BlockItem bi
                && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    public static boolean isShulkerBoxEmpty(ItemStack box) {
        if (!isShulkerBox(box)) return false;
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        return contents == null || contents.nonEmptyItemCopyStream().findAny().isEmpty();
    }

    // ============================================================
    // 内部工具（package-private，供 BoxCraftPlan 使用）
    // ============================================================

    static ItemStack clearBox(ItemStack box) {
        box.remove(DataComponents.CONTAINER);
        return box;
    }

    static ItemStack fillBox(ItemStack box, Item item, int perSlot) {
        List<ItemStack> stacks = new ArrayList<>(BOX_SLOTS);
        for (int i = 0; i < BOX_SLOTS; i++) {
            stacks.add(new ItemStack(item, perSlot));
        }
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(stacks));
        return box;
    }

    private static ItemStack readFullSingleItem(ItemStack box) {
        if (!isShulkerBox(box)) return ItemStack.EMPTY;
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        if (contents == null) return ItemStack.EMPTY;

        List<ItemStack> stacks = contents.nonEmptyItemCopyStream().toList();
        if (stacks.size() != BOX_SLOTS) return ItemStack.EMPTY;

        Item item = null;
        for (ItemStack s : stacks) {
            if (s.isEmpty()) return ItemStack.EMPTY;
            if (s.getCount() != s.getItem().getDefaultMaxStackSize()) return ItemStack.EMPTY;
            if (item == null) {
                item = s.getItem();
            } else if (s.getItem() != item) {
                return ItemStack.EMPTY;
            }
        }
        return new ItemStack(item);
    }

    static List<ItemStack> collectInputBoxes(List<ItemStack> grid) {
        List<ItemStack> inputBoxes = new ArrayList<>();
        for (ItemStack s : grid) {
            if (isShulkerBox(s)) inputBoxes.add(s);
        }
        return inputBoxes;
    }

    static List<ItemStack> findEmptyBoxes(Container inv, int needed) {
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize() && result.size() < needed; i++) {
            ItemStack s = inv.getItem(i);
            if (isShulkerBoxEmpty(s)) result.add(s);
        }
        return result;
    }

    static void clearInputBoxes(List<ItemStack> grid, BiConsumer<Integer, ItemStack> setter) {
        for (int i = 0; i < grid.size(); i++) {
            if (isShulkerBox(grid.get(i))) {
                setter.accept(i, ItemStack.EMPTY);
            }
        }
    }

    static void consumeEmptyBoxes(List<ItemStack> emptyBoxes, int count) {
        for (int i = 0; i < count && i < emptyBoxes.size(); i++) {
            emptyBoxes.get(i).setCount(0);
        }
    }

    static void addRawItems(List<ItemStack> output, Item item, int count, int max) {
        int remaining = count;
        while (remaining > 0) {
            int n = Math.min(remaining, max);
            output.add(new ItemStack(item, n));
            remaining -= n;
        }
    }

    static Container getContainerAt(ServerLevel level, BlockPos pos) {
        var be = level.getBlockEntity(pos);
        return be instanceof Container c ? c : null;
    }

    static ItemStack insertIntoContainer(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();

        // 1. 先合并到已有同类堆
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(slot, remaining)) continue;
            int limit = Math.min(slot.getMaxStackSize(), container.getMaxStackSize(slot));
            int space = limit - slot.getCount();
            if (space <= 0) continue;
            int toAdd = Math.min(space, remaining.getCount());
            slot.grow(toAdd);
            remaining.shrink(toAdd);
        }

        // 2. 再找空槽
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty()) continue;
            if (!container.canPlaceItem(i, remaining)) continue;
            int limit = Math.min(remaining.getMaxStackSize(), container.getMaxStackSize(remaining));
            int toAdd = Math.min(limit, remaining.getCount());
            container.setItem(i, remaining.copyWithCount(toAdd));
            remaining.shrink(toAdd);
        }

        return remaining;
    }

    // ============================================================
    // 计算方案
    // ============================================================

    public static BoxCraftPlan computePlan(Level level, CraftingContainer container) {
        if (!isEnabled()) return null;

        List<ItemStack> grid = container.getItems();
        int width = container.getWidth();
        int height = container.getHeight();

        List<ItemStack> virtual = new ArrayList<>(grid.size());
        int boxedSlots = 0;
        int boxItemCount = -1;
        boolean anyBox = false;

        for (ItemStack s : grid) {
            if (s.isEmpty()) {
                virtual.add(ItemStack.EMPTY);
                continue;
            }
            ItemStack content = readFullSingleItem(s);
            if (content.isEmpty()) return null;
            anyBox = true;
            boxedSlots++;
            int count = BOX_SLOTS * content.getItem().getDefaultMaxStackSize();
            if (boxItemCount == -1) {
                boxItemCount = count;
            } else if (boxItemCount != count) {
                return null;
            }
            virtual.add(content);
        }
        if (!anyBox) return null;

        MinecraftServer server = level.getServer();
        if (server == null) return null;
        RecipeManager recipes = server.getRecipeManager();

        CraftingInput input = CraftingInput.of(width, height, virtual);
        Optional<RecipeHolder<CraftingRecipe>> holder = recipes.getRecipeFor(RecipeType.CRAFTING, input, level);
        if (holder.isEmpty()) return null;

        ItemStack perRun = holder.get().value().assemble(input);
        if (perRun.isEmpty()) return null;

        Item outItem = perRun.getItem();
        int outMax = outItem.getDefaultMaxStackSize();
        int boxCapacity = BOX_SLOTS * outMax;
        int totalOutput = perRun.getCount() * boxItemCount;
        int outputBoxes = totalOutput / boxCapacity;
        int remainder = totalOutput % boxCapacity;

        return new BoxCraftPlan(boxedSlots, boxItemCount, outputBoxes, remainder,
                totalOutput, outItem, outMax, perRun.getCount());
    }
}