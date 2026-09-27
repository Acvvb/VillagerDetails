package com.villagerdetails.handler.villager.trader;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.mixin.AbstractVillagerMixin;
import com.villagerdetails.mixin.VillagerInterfaceTradesMixin;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.function.Predicate;

import static com.villagerdetails.rule.type.RuleType.VILLAGER_AUTO_LOCK_HIT_TRADER;

public class AutoVillagerTrader {

    private static final int TERRACOTTA_MAX_LEVEL = 4;

    public enum Route {
        ENCHANT,     // 附魔书：只刷当前等级
        NORMAL,      // 普通物品：只刷当前等级
        TERRACOTTA   // 陶瓦：累积 1~4 级
    }

    public record WantedResult(Predicate<ItemStack> predicate,
                               Predicate<ItemStack> glazedPredicate,
                               Route route) {
        public static WantedResult enchant(Predicate<ItemStack> p) {
            return new WantedResult(p, null, Route.ENCHANT);
        }

        public static WantedResult normal(Predicate<ItemStack> p) {
            return new WantedResult(p, null, Route.NORMAL);
        }

        public static WantedResult terracotta(Predicate<ItemStack> t, Predicate<ItemStack> g) {
            return new WantedResult(t, g, Route.TERRACOTTA);
        }
    }

    public static int tickByName(Villager villager, String raw, int maxAttempts) {
        return tickByName(villager, raw, maxAttempts, true);
    }

    public static int tickByName(Villager villager, String raw, int maxAttempts, boolean doLock) {
        WantedResult wanted = parseWanted(villager, raw);
        if (wanted == null) return -2;

        return switch (wanted.route()) {
            case TERRACOTTA -> tickTerracotta(villager, maxAttempts,
                    wanted.predicate(), wanted.glazedPredicate(), doLock);
            case ENCHANT -> tickEnchant(villager, maxAttempts, wanted.predicate(), doLock);
            case NORMAL -> tickNormalItem(villager, maxAttempts, wanted.predicate(), doLock);
        };
    }

    public static WantedResult parseWanted(Villager villager, String raw) {
        if (!(villager.level() instanceof ServerLevel level)) return null;

        Identifier id = IdTranslation.resolveEnchantId(raw);
        if (id == null) return null;

        // ===== 附魔 =====
        Registry<Enchantment> enchRegistry = level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT);
        Enchantment enchantment = enchRegistry.getValue(id);
        if (enchantment != null) {
            if (!TradePoolChecker.canProduceEnchantmentAtAnyLevel(villager, enchantment)) return null;

            return WantedResult.enchant(stack -> {
                if (!stack.is(Items.ENCHANTED_BOOK)) return false;
                ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
                if (stored == null) return false;
                for (Holder<Enchantment> h : stored.keySet()) {
                    if (h.value() != enchantment) continue;
                    return stored.getLevel(h) >= h.value().getMaxLevel();
                }
                return false;
            });
        }

        // ===== 物品 =====
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == Items.AIR) return null;

        // 陶瓦特判：预检 1~4 级里有没有（累积刷新会覆盖这些等级）
        if (isPlainTerracotta(item)) {
            Item glazedItem = findGlazedCounterpart(item);
            if (glazedItem == null) return null;

            if (canProduceAtAnyLevelUpTo(villager, item)) return null;
            if (canProduceAtAnyLevelUpTo(villager, glazedItem)) return null;

            return WantedResult.terracotta(
                    stack -> stack.is(item),
                    stack -> stack.is(glazedItem)
            );
        }

        // 普通物品
        if (!TradePoolChecker.canProduceAtAnyLevel(villager, item)) return null;
        return WantedResult.normal(stack -> stack.is(item));
    }

    /** 该村民在 [1, maxLevel] 区间内任意等级能否产出某物品 */
    private static boolean canProduceAtAnyLevelUpTo(Villager villager, Item item) {
        if (item == null) return true;
        for (int lv = 1; lv <= AutoVillagerTrader.TERRACOTTA_MAX_LEVEL; lv++) {
            if (TradePoolChecker.canProduceAtLevel(villager, lv, item)) return false;
        }
        return true;
    }

    private static boolean isPlainTerracotta(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        String path = id.getPath();
        return path.endsWith("_terracotta") && !path.contains("_glazed_");
    }

    private static Item findGlazedCounterpart(Item terracotta) {
        Identifier id = BuiltInRegistries.ITEM.getKey(terracotta);
        String path = id.getPath();
        String glazedPath = path.replace("_terracotta", "_glazed_terracotta");
        Identifier glazedId = Identifier.fromNamespaceAndPath(id.getNamespace(), glazedPath);
        Item glazed = BuiltInRegistries.ITEM.getValue(glazedId);
        return glazed == Items.AIR ? null : glazed;
    }

    // ==============================================================
    // 三条路线
    // ==============================================================

    /** 附魔书：只刷当前等级 */
    public static int tickEnchant(Villager villager, int maxAttempts,
                                  Predicate<ItemStack> want, boolean doLock) {
        if (isRerollable(villager)) return -1;
        return tickLevel(villager, null, maxAttempts,
                offers -> hasAny(offers, want), doLock);
    }

    /** 普通物品：只刷当前等级 */
    public static int tickNormalItem(Villager villager, int maxAttempts,
                                     Predicate<ItemStack> want, boolean doLock) {
        return tickEnchant(villager, maxAttempts, want, doLock);
    }

    /** 陶瓦：累积 1~4 级刷新 */
    public static int tickTerracotta(Villager villager, int maxAttempts,
                                     Predicate<ItemStack> terracotta,
                                     Predicate<ItemStack> glazed,
                                     boolean doLock) {
        if (isRerollable(villager)) return -1;
        return tickLevel(villager, TERRACOTTA_MAX_LEVEL, maxAttempts,
                offers -> hasAny(offers, terracotta) && hasAny(offers, glazed),
                doLock);
    }

    /**
     * 通用刷新入口。
     *
     * @param targetLevel 若为 null → 只用当前等级；
     *                    否则 → 累积 1~targetLevel 级生成交易（模拟真实升级累积）。
     *                    命中 → 保持 targetLevel；未命中 → 恢复原等级。
     */
    private static int tickLevel(Villager villager, Integer targetLevel, int maxAttempts,
                                 Predicate<MerchantOffers> matcher, boolean doLock) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) return -1;

        AbstractVillagerMixin acc = (AbstractVillagerMixin) villager;
        VillagerInterfaceTradesMixin tradesInvoker = (VillagerInterfaceTradesMixin) villager;
        VillagerData originalData = villager.getVillagerData();

        for (int i = 0; i < maxAttempts; i++) {
            if (targetLevel == null) {
                // 只刷当前等级
                acc.setOffersField(null);
            } else {
                // 累积 1~targetLevel
                acc.setOffersField(new MerchantOffers());
                for (int lv = 1; lv <= targetLevel; lv++) {
                    villager.setVillagerData(originalData.withLevel(lv));
                    tradesInvoker.invokeUpdateTrades(serverLevel);
                }
            }

            MerchantOffers offers = villager.getOffers();
            if (matcher.test(offers)) {
                doLockIfNeeded(villager, offers, doLock);
                return i + 1;   // 命中：等级保留（targetLevel != null 时为 targetLevel）
            }
        }

        // 未命中：改过等级就恢复
        if (targetLevel != null) {
            villager.setVillagerData(originalData);
        }
        return 0;
    }

    private static boolean hasAny(MerchantOffers offers, Predicate<ItemStack> want) {
        if (offers == null || want == null) return false;
        for (MerchantOffer o : offers) {
            if (want.test(o.getResult())) return true;
        }
        return false;
    }

    private static boolean isRerollable(Villager villager) {
        if (villager.level().isClientSide()) return true;
        if (villager.isBaby()) return true;
        if (villager.getVillagerXp() > 0) return true;
        if (villager.getVillagerData().profession().is(VillagerProfession.NONE)) return true;
        return villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).isEmpty();
    }

    private static void doLockIfNeeded(Villager villager, MerchantOffers offers, boolean doLock) {
        if (doLock && RuleCache.isEnabled(VILLAGER_AUTO_LOCK_HIT_TRADER)) {
            lock(villager, offers);
        }
    }

    private static void lock(Villager villager, MerchantOffers offers) {
        if (offers != null && !offers.isEmpty()) {
            offers.getFirst().increaseUses();
        }
        villager.setVillagerXp(1);
    }
}