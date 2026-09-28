package com.villagerdetails.handler.villager.trader.decompose;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 村民交易的物品自动制备。
 * <p>Recipe 定义：{@code target}（产出的目标物品）、{@code targetPerCraft}（一份配方产出几个）、
 * {@code ingredients}（消耗的原料）。
 */
public final class ItemProvisioner {

    private ItemProvisioner() {}

    /** 制备配方 */
    public record Recipe(Item target, int targetPerCraft,
                         List<Ingredient> ingredients, RuleType rule) {}

    /** 原料 */
    public record Ingredient(Predicate<ItemStack> matcher, int count) {}

    private static final Map<Item, Recipe> RECIPES = new HashMap<>();

    static {
        // ---- 绿宝石：1 绿宝石块 → 9 绿宝石 ----
        register(new Recipe(
                Items.EMERALD,
                9,
                List.of(new Ingredient(s -> s.is(Items.EMERALD_BLOCK), 1)),
                RuleType.VILLAGER_EMERALD_DECOMPOSE
        ));

        // ---- 书：1 书架 → 3 本书 ----
        register(new Recipe(
                Items.BOOK,
                3,
                List.of(new Ingredient(s -> s.is(Items.BOOKSHELF), 1)),
                RuleType.VILLAGER_BOOKSHELF_DECOMPOSE
        ));
    }

    public static void register(Recipe recipe) {
        RECIPES.put(recipe.target(), recipe);
    }

    public static boolean hasRecipe(Item target) {
        return RECIPES.containsKey(target);
    }

    /**
     * 尝试从玩家背包制备 needCount 个目标物品，成品加到背包。
     */
    public static void provision(Player player, Item target, int needCount) {
        Recipe recipe = RECIPES.get(target);
        if (recipe == null) return;

        // ★ 规则没开 → 跳过
        if (!isRuleEnabled(player, recipe.rule())) return;

        Inventory inv = player.getInventory();

        // 1. 计算最多能制备几份（所有原料里最少的那种决定）
        int maxCrafts = Integer.MAX_VALUE;
        for (Ingredient ingredient : recipe.ingredients()) {
            int available = countMatching(inv, ingredient.matcher());
            int canCraft = available / ingredient.count();
            maxCrafts = Math.min(maxCrafts, canCraft);
        }
        if (maxCrafts <= 0) return;

        // 2. 需要几份
        int craftsNeeded = (needCount + recipe.targetPerCraft() - 1) / recipe.targetPerCraft();
        int crafts = Math.min(maxCrafts, craftsNeeded);
        if (crafts <= 0) return;

        // 3. 扣原料
        for (Ingredient ingredient : recipe.ingredients()) {
            consumeMatching(inv, ingredient.matcher(), ingredient.count() * crafts);
        }

        // 4. 产出到背包
        int produced = recipe.targetPerCraft() * crafts;
        ItemStack result = new ItemStack(target, produced);
        if (!inv.add(result)) {
            player.drop(result, false);
        }

    }

    /** 规则是否启用 */
    private static boolean isRuleEnabled(Player player, RuleType rule) {
        if (player instanceof ServerPlayer sp) {
            return RuleCache.isEnabled(sp, rule);
        }
        return false;
    }

    private static int countMatching(Inventory inv, Predicate<ItemStack> matcher) {
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && matcher.test(s)) count += s.getCount();
        }
        return count;
    }

    private static void consumeMatching(Inventory inv, Predicate<ItemStack> matcher, int amount) {
        int remaining = amount;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !matcher.test(s)) continue;
            int take = Math.min(s.getCount(), remaining);
            s.shrink(take);
            remaining -= take;
        }
    }
}