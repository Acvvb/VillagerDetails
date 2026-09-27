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
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.function.Predicate;

import static com.villagerdetails.rule.type.RuleType.*;

public class AutoVillagerTrader {

    private static final int TERRACOTTA_MAX_LEVEL = 4;
    private static final int COMBO_LEVEL = 5;

    /** 兜底价格：所有钻石工具交易都借不到时用 */
    private static final int FALLBACK_PRICE = 10;
    /** 兜底最大使用次数 */
    private static final int FALLBACK_MAX_USES = 12;
    /** 兜底交易经验 */
    private static final int FALLBACK_XP = 30;


    /**
     * 名字是否属于支持的三类：331 / 附魔 / 16 色陶瓦。
     * 其余名字直接放行。
     */
    public static boolean isSupportedName(Villager villager, String raw) {
        if (raw == null) return false;
        String key = raw.replace('\u3000', ' ').trim().toLowerCase();

        // 1) 331 触发词：只认 331a / 331b
        if (key.equals("331a") || key.equals("331b")) return true;

        // 2) 需要 registry 才能判断
        if (!(villager.level() instanceof ServerLevel level)) return false;
        Identifier id = IdTranslation.resolveEnchantId(raw);
        if (id == null) return false;

        // 3) 附魔
        Registry<Enchantment> enchantReg = level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT);
        if (enchantReg.getValue(id) != null) return true;

        // 4) 陶瓦（16 色普通陶瓦）
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return item != Items.AIR && isPlainTerracotta(item);
    }

    // ==============================================================
    // 路线与结果
    // ==============================================================

    public enum Route { ENCHANT, NORMAL, TERRACOTTA }

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
        if (raw != null) {
            String key = raw.replace('\u3000', ' ').trim().toLowerCase();

            // ===== 331 组合刷新：只认 331a（精准）/ 331b（时运）=====
            if (key.equals("331a")) {
                if (!RuleCache.isEnabled(VILLAGER_GOD_TOOLS)) return -3;
                return tickGodTools(villager, true, doLock);   // 精准套
            }
            if (key.equals("331b")) {
                if (!RuleCache.isEnabled(VILLAGER_GOD_TOOLS)) return -3;
                return tickGodTools(villager, false, doLock);  // 时运套
            }
        }

        WantedResult wanted = parseWanted(villager, raw);
        if (wanted == null) return -2;

        return switch (wanted.route()) {
            case TERRACOTTA -> {
                if (!RuleCache.isEnabled(VILLAGER_TERRACOTTA_TRADER)) yield -3;
                yield tickTerracotta(villager, maxAttempts,
                        wanted.predicate(), wanted.glazedPredicate(), doLock);
            }
            case ENCHANT -> tickEnchant(villager, maxAttempts, wanted.predicate(), doLock);
            case NORMAL -> tickNormalItem(villager, maxAttempts, wanted.predicate(), doLock);
        };
    }

    //331
    private static int tickGodTools(Villager villager, boolean silkTouch, boolean doLock) {
        if (isRerollable(villager)) return -1;
        if (!(villager.level() instanceof ServerLevel sl)) return -1;

        AbstractVillagerMixin acc = (AbstractVillagerMixin) villager;
        VillagerInterfaceTradesMixin tradesInvoker = (VillagerInterfaceTradesMixin) villager;
        VillagerData originalData = villager.getVillagerData();

        // 1) 累积 1~5 级生成原版交易
        acc.setOffersField(new MerchantOffers());
        for (int lv = 1; lv <= COMBO_LEVEL; lv++) {
            villager.setVillagerData(originalData.withLevel(lv));
            tradesInvoker.invokeUpdateTrades(sl);
        }
        villager.setVillagerData(originalData.withLevel(COMBO_LEVEL));

        MerchantOffers offers = villager.getOffers();

        // 2) 遍历已有交易
        boolean hasPickaxe = false, hasShovel = false, hasAxe = false;
        // 参考价格：从任意一条钻石工具交易里借
        Integer refPrice = null;
        Integer refMaxUses = null;
        Integer refXp = null;

        for (MerchantOffer o : offers) {
            ItemStack r = o.getResult();
            boolean isPick = r.is(Items.DIAMOND_PICKAXE);
            boolean isShovel = r.is(Items.DIAMOND_SHOVEL);
            boolean isAxe = r.is(Items.DIAMOND_AXE);
            if (!isPick && !isShovel && !isAxe) continue;
            if (refPrice == null) {
                refPrice = o.getBaseCostA().getCount();
                refMaxUses = o.getMaxUses();
                refXp = o.getXp();
            }
            applyGodEnchants(r, sl, silkTouch);
            if (isPick) hasPickaxe = true;
            if (isShovel) hasShovel = true;
            if (isAxe) hasAxe = true;
        }
        int price = refPrice != null ? refPrice : FALLBACK_PRICE;
        int maxUses = refMaxUses != null ? refMaxUses : FALLBACK_MAX_USES;
        int xp = refXp != null ? refXp : FALLBACK_XP;

        if (!hasPickaxe) offers.add(buildGodOffer(Items.DIAMOND_PICKAXE, sl, silkTouch, price, maxUses, xp));
        if (!hasShovel) offers.add(buildGodOffer(Items.DIAMOND_SHOVEL, sl, silkTouch, price, maxUses, xp));
        if (!hasAxe) offers.add(buildGodOffer(Items.DIAMOND_AXE, sl, silkTouch, price, maxUses, xp));
        doLockIfNeeded(villager, offers, doLock);
        return 1;
    }

    /** 给工具加：效率 III + 耐久 III + 精准采集 I 或 时运 I（清空原有附魔） */
    private static void applyGodEnchants(ItemStack stack, ServerLevel level, boolean silkTouch) {
        Registry<Enchantment> reg = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> efficiency = holder(reg, "minecraft:efficiency");
        Holder<Enchantment> unbreaking = holder(reg, "minecraft:unbreaking");
        Holder<Enchantment> third = holder(reg, silkTouch ? "minecraft:silk_touch" : "minecraft:fortune");
        if (efficiency == null || unbreaking == null || third == null) return;
        ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        m.set(efficiency, 3);
        m.set(unbreaking, 3);
        m.set(third, 1);

        stack.set(DataComponents.ENCHANTMENTS, m.toImmutable());
    }

    /** 构造一个附好魔的钻石工具交易（用于补全原版缺失的） */
    private static MerchantOffer buildGodOffer(Item tool, ServerLevel level, boolean silkTouch, int price, int maxUses, int xp) {
        ItemStack stack = new ItemStack(tool);
        applyGodEnchants(stack, level, silkTouch);
        ItemCost cost = new ItemCost(Items.EMERALD, price);
        return new MerchantOffer(cost, stack, maxUses, xp, 0.05F);
    }

    private static Holder<Enchantment> holder(Registry<Enchantment> reg, String id) {
        Identifier i = Identifier.tryParse(id);
        if (i == null) return null;
        Enchantment e = reg.getValue(i);
        return e == null ? null : reg.wrapAsHolder(e);
    }

    public static WantedResult parseWanted(Villager villager, String raw) {
        if (!(villager.level() instanceof ServerLevel level)) return null;
        Identifier id = IdTranslation.resolveEnchantId(raw);
        if (id == null) return null;
        Registry<Enchantment> enchRegistry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
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
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == Items.AIR) return null;
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
        if (!TradePoolChecker.canProduceAtAnyLevel(villager, item)) return null;
        return WantedResult.normal(stack -> stack.is(item));
    }

    /** 是否在 1~TERRACOTTA_MAX_LEVEL 里至少出现一次 */
    private static boolean canProduceAtAnyLevelUpTo(Villager villager, Item item) {
        if (item == null) return true;
        for (int lv = 1; lv <= TERRACOTTA_MAX_LEVEL; lv++) {
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
    // 三条常规路线
    // ==============================================================

    public static int tickEnchant(Villager villager, int maxAttempts, Predicate<ItemStack> want, boolean doLock) {
        if (isRerollable(villager)) return -1;
        return tickLevel(villager, null, maxAttempts,
                offers -> hasAny(offers, want), doLock);
    }

    public static int tickNormalItem(Villager villager, int maxAttempts, Predicate<ItemStack> want, boolean doLock) {
        return tickEnchant(villager, maxAttempts, want, doLock);
    }

    public static int tickTerracotta(Villager villager, int maxAttempts, Predicate<ItemStack> terracotta, Predicate<ItemStack> glazed, boolean doLock) {
        if (isRerollable(villager)) return -1;
        return tickLevel(villager, TERRACOTTA_MAX_LEVEL, maxAttempts,
                offers -> hasAny(offers, terracotta) && hasAny(offers, glazed),
                doLock);
    }

    // ==============================================================
    // 内部统一实现
    // ==============================================================

    private static int tickLevel(Villager villager, Integer targetLevel, int maxAttempts, Predicate<MerchantOffers> matcher, boolean doLock) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) return -1;
        AbstractVillagerMixin acc = (AbstractVillagerMixin) villager;
        VillagerInterfaceTradesMixin tradesInvoker = (VillagerInterfaceTradesMixin) villager;
        VillagerData originalData = villager.getVillagerData();
        for (int i = 0; i < maxAttempts; i++) {
            if (targetLevel == null) {
                acc.setOffersField(null);
            } else {
                acc.setOffersField(new MerchantOffers());
                for (int lv = 1; lv <= targetLevel; lv++) {
                    villager.setVillagerData(originalData.withLevel(lv));
                    tradesInvoker.invokeUpdateTrades(serverLevel);
                }
            }
            MerchantOffers offers = villager.getOffers();
            if (matcher.test(offers)) {
                doLockIfNeeded(villager, offers, doLock);
                return i + 1;
            }
        }
        if (targetLevel != null) {
            villager.setVillagerData(originalData);
        }
        return 0;
    }

    // ==============================================================
    // 内部工具
    // ==============================================================

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