package com.villagerdetails.handler.craft;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 「盒子合成」规则：把「装满同一物品的潜影盒」当作 27 个满堆（= 27 × 该物品堆叠上限）来参与工作台合成。
 * <p>
 * 例如：1 盒铁块（27 堆 = 1728 个铁块）按「铁块 → 9 铁锭」合成，产出 15552 个铁锭，正好是 9 盒。
 * 盒子数量前后守恒：输入盒子的壳会复用到输出上；输出盒子不够时消耗背包里的空潜影盒；
 * 空盒不够时直接把所有产物（不装箱）塞进背包，溢出掉落（与原版一致）。
 */
public final class BoxCraftingHandler {

    private BoxCraftingHandler() {
    }

    /** 潜影盒的槽位数。 */
    private static final int BOX_SLOTS = 27;

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

    /** 把潜影盒清空（移除容器组件）。 */
    private static ItemStack clearBox(ItemStack box) {
        box.remove(DataComponents.CONTAINER);
        return box;
    }

    /** 用 item 的满堆填满一个潜影盒。 */
    private static ItemStack fillBox(ItemStack box, Item item, int perSlot) {
        List<ItemStack> stacks = new ArrayList<>(BOX_SLOTS);
        for (int i = 0; i < BOX_SLOTS; i++) {
            stacks.add(new ItemStack(item, perSlot));
        }
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(stacks));
        return box;
    }

    /**
     * 若 stack 是「装满同一物品」的潜影盒，返回该物品的 1 个样本；否则返回 {@link ItemStack#EMPTY}。
     */
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

    /**
     * 计算盒子合成方案；不满足条件（规则关闭 / 有非潜影盒的空格 / 盒内物品数量不一致 / 无配方）时返回 null。
     */
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
            if (content.isEmpty()) {
                // 非空的格子必须是「装满同一物品」的潜影盒，否则整条规则不参与
                return null;
            }
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

        return new BoxCraftPlan(boxedSlots, boxItemCount, outputBoxes, remainder, totalOutput, outItem, outMax, perRun.getCount());
    }

    /** 计算一次合成方案。 */
    public static final class BoxCraftPlan {
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

        /** 结果槽预览：有产物盒时显示 1 盒，否则显示 1 堆散货。 */
        public ItemStack preview() {
            if (outputBoxes > 0) {
                return fillBox(new ItemStack(Items.SHULKER_BOX), outItem, outMax);
            }
            return new ItemStack(outItem, Math.min(totalOutput, outMax));
        }

        /**
         * 执行合成：尽量把产物装进盒子（先用输入盒的壳，再消耗背包空盒），
         * 装不下的产物以散货形式给出（溢出掉落），并消耗输入盒与用掉的背包空盒。
         */
        public void execute(ServerPlayer player, CraftingContainer container) {
            List<ItemStack> grid = container.getItems();

            // 收集输入盒（用于复用的壳）
            List<ItemStack> inputBoxes = new ArrayList<>();
            for (ItemStack s : grid) {
                if (isShulkerBox(s)) {
                    inputBoxes.add(s);
                }
            }

            int emptyNeeded = Math.max(0, outputBoxes - boxedSlots);

            // 从背包找空潜影盒（最多找 emptyNeeded 个）
            List<ItemStack> emptyBoxes = new ArrayList<>();
            if (emptyNeeded > 0) {
                Inventory inv = player.getInventory();
                for (int i = 0; i < inv.getContainerSize() && emptyBoxes.size() < emptyNeeded; i++) {
                    ItemStack s = inv.getItem(i);
                    if (isShulkerBoxEmpty(s)) {
                        emptyBoxes.add(s);
                    }
                }
            }

            // 壳：先输入盒，再背包空盒
            List<ItemStack> shells = new ArrayList<>(inputBoxes);
            shells.addAll(emptyBoxes);

            // 能装多少盒就装多少盒
            int filledBoxes = Math.min(outputBoxes, shells.size());

            List<ItemStack> output = new ArrayList<>();
            for (int i = 0; i < filledBoxes; i++) {
                output.add(fillBox(shells.get(i).copy(), outItem, outMax));
            }
            // 多余的壳作为空盒返回（输入盒数量大于产物盒数量时，如 2 合 1）
            for (int i = filledBoxes; i < shells.size(); i++) {
                output.add(clearBox(shells.get(i).copy()));
            }
            // 装不下的产物 + 余数 → 散货
            int boxCapacity = BOX_SLOTS * outMax;
            int unboxed = totalOutput - filledBoxes * boxCapacity;
            addRawItems(output, outItem, unboxed, outMax);

            // 给予产物（溢出掉落）
            for (ItemStack out : output) {
                if (!player.getInventory().add(out)) {
                    player.drop(out, false);
                }
            }

            // 消耗输入盒（清空格子）
            for (int i = 0; i < grid.size(); i++) {
                if (isShulkerBox(grid.get(i))) {
                    container.setItem(i, ItemStack.EMPTY);
                }
            }

            // 消耗背包里被用掉的空盒
            int consumeEmpty = Math.max(0, filledBoxes - boxedSlots);
            for (int i = 0; i < consumeEmpty; i++) {
                emptyBoxes.get(i).setCount(0);
            }
        }
    }

    /** 按 item 的堆叠上限把 count 拆成若干堆。 */
    private static void addRawItems(List<ItemStack> output, Item item, int count, int max) {
        int remaining = count;
        while (remaining > 0) {
            int n = Math.min(remaining, max);
            output.add(new ItemStack(item, n));
            remaining -= n;
        }
    }
}
