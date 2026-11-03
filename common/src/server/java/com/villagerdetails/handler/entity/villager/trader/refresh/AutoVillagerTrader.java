package com.villagerdetails.handler.entity.villager.trader.refresh;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.IdTranslationConfig;
import com.villagerdetails.handler.entity.villager.trade.VillagerTradePipeline;
import com.villagerdetails.mixin.entity.villager.trader.autoTrader.AbstractVillagerInterfaceMixin;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.VillagerTrade;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static com.villagerdetails.rule.type.RuleType.*;

/**
 * 命名牌触发的村民交易刷新。
 *
 * <p>两条路线：
 * <ul>
 *     <li><b>点名刷取（分帧真实模拟）</b>：命名牌写「绿宝石 / mending / 白釉陶瓦」这类物品或附魔，
 *     先静态判断「这个职业最低几级能刷出它」（{@link TradePoolChecker#requiredLevel}），
 *     然后<b>按原版概率逐次重掷整表</b>，交给 {@link TradeRerollScheduler} 分帧执行；
 *     命中后在主线程写回、锁等级、发包、刷索引。</li>
 *     <li><b>固定组合（同步）</b>：{@code 331a} / {@code 331b} 是「三种附魔钻石工具」这种
 *     复杂组合，仍旧同步跑拒绝采样 + 事后补齐三件工具。</li>
 * </ul>
 *
 * <p>两条路线的随机数都由 {@link TradeTableBuilder#seedFor} 按
 * 「村民 UUID + 第几次尝试」自播种，<b>不碰世界随机序列</b>（{@code level.dat} 里的
 * {@code RandomSequences}），同一村民同一尝试结果可复现，也不再需要
 * {@code setVillagerData} + {@code updateTrades} 那套「临时改等级」的写法。
 */
public class AutoVillagerTrader {

    private static final int TERRACOTTA_MAX_LEVEL = 4;
    private static final int COMBO_LEVEL = 5;

    /**
     * 兜底最大使用次数
     */
    private static final int FALLBACK_MAX_USES = 3;
    /**
     * 兜底交易经验
     */
    private static final int FALLBACK_XP = 30;

    /**
     * 331 工具刷新 —— 工具匠。
     */
    private static final Set<ResourceKey<VillagerProfession>> GOD_TOOLS_PROFESSIONS = Set.of(
            VillagerProfession.WEAPONSMITH
    );

    // ==============================================================
    // 名字支持判定
    // ==============================================================

    /**
     * 是否是 {@code 331a} / {@code 331b} 这类「固定流程」的关键字。
     */
    public static boolean isSpecialName(String raw) {
        if (raw == null) return false;
        String key = normalize(raw);
        return key.equals("331a") || key.equals("331b");
    }

    private static String normalize(String raw) {
        return raw.replace('\u3000', ' ').trim().toLowerCase();
    }

    /**
     * 能否解析成「点名」目标：注册表里查得到的物品 / 附魔，或固定关键字。
     *
     * <p>解析不出来的名字直接放行（不弹消息、不动交易），避免把玩家起的中文名
     * 当成刷新指令。
     */
    public static boolean isSupportedName(Villager villager, String raw) {
        if (raw == null) return false;
        if (isSpecialName(raw)) return true;
        return IdTranslationConfig.resolveId(raw) != null;
    }

    // ==============================================================
    // 路线与目标
    // ==============================================================

    public enum Route {
        /** 附魔书：命中条件是「书里带这个附魔」，命中后等级强制覆写到满级。 */
        ENCHANT,
        /** 普通物品。 */
        NORMAL,
        /** 陶瓦：普通陶瓦 + 对应釉陶瓦必须成对出现。 */
        TERRACOTTA
    }

    /**
     * 解析出来的刷新目标。
     *
     * @param requiredLevel 最低可出等级（1~5），{@link TradePoolChecker#NO_LEVEL} 表示刷不出
     * @param tests         命中条件：全部满足才算中
     * @param upgrade       命中后的结果升级（附魔书满级），可为 {@code null}
     */
    public record Target(Route route,
                         int requiredLevel,
                         List<Predicate<ItemStack>> tests,
                         TradeRerollScheduler.Upgrade upgrade) {
    }

    // ==============================================================
    // 入口
    // ==============================================================

    /**
     * 命名牌触发入口：<b>所有</b>路线（点名刷物品/附魔、陶瓦成对、331a/331b）都只是
     * 「建一个任务丢给 {@link TradeRerollScheduler}」，模拟与写回全在调度器里分帧完成。
     *
     * @return 任务已排队时返回该任务的<b>模拟次数上限</b>（{@code >0}，方便提示玩家）；
     * 负数约定：{@code -1} 已锁定 / 小孩 / 无工作站、{@code -2} 名字解析不出、
     * {@code -3} 规则未开、{@code -4} 职业不支持、{@code -7} 这个职业根本刷不出、
     * {@code -8} 任务过多
     */
    public static int tickByName(Villager villager, String raw, ServerPlayer player) {
        if (raw == null) return -2;
        String key = normalize(raw);
        if (!(villager.level() instanceof ServerLevel level)) return -1;

        // ===== 331a（精准）/ 331b（时运）：三件套 + 三附魔，同样走调度器 =====
        if (key.equals("331a") || key.equals("331b")) {
            if (!RuleCache.isEnabled(VILLAGER_GOD_TOOLS)) return -3;
            if (isProfessionAllowed(villager, GOD_TOOLS_PROFESSIONS)) return -4;
            if (isRerollable(villager)) return -1;
            return enqueueCombo(level, villager, player, raw.trim(), key.equals("331a"));
        }

        // ===== 点名：物品 / 附魔书 / 陶瓦 =====
        if (!RuleCache.isEnabled(VILLAGER_AUTO_TRADER)) return -3;

        Target target = resolveTarget(villager, raw);
        if (target == null) return -2;
        if (target.requiredLevel() == TradePoolChecker.NO_LEVEL) return -7;
        if (isRerollable(villager)) return -1;
        if (target.route() == Route.TERRACOTTA && !RuleCache.isEnabled(VILLAGER_TERRACOTTA_TRADER)) {
            return -3;
        }

        int attempts = target.route() == Route.TERRACOTTA
                ? TradeRerollScheduler.TERRACOTTA_MAX_ATTEMPTS
                : TradeRerollScheduler.MAX_ATTEMPTS;
        TradeRerollScheduler.Submit submit = TradeRerollScheduler.submit(
                new TradeRerollScheduler.Task(
                        villager.getUUID(),
                        level.dimension(),
                        player == null ? null : player.getUUID(),
                        raw.trim(),
                        target.requiredLevel(),
                        attempts,
                        target.tests(),
                        target.upgrade(),
                        null,
                        false,
                        "msg.auto_trader.target_success"));
        return submit == TradeRerollScheduler.Submit.QUEUED ? attempts : -8;
    }

    /**
     * 331a/331b：命中条件是「三件钻石工具齐全」，命中 / 次数用尽后由 finisher
     * 统一改写三附魔并补齐缺件，所以最终一定拿得到完整套装。
     */
    private static int enqueueCombo(ServerLevel level, Villager villager, ServerPlayer player,
                                    String label, boolean silkTouch) {
        TradeRerollScheduler.Submit submit = TradeRerollScheduler.submit(
                new TradeRerollScheduler.Task(
                        villager.getUUID(),
                        level.dimension(),
                        player == null ? null : player.getUUID(),
                        label,
                        COMBO_LEVEL,
                        TradeRerollScheduler.COMBO_MAX_ATTEMPTS,
                        List.of(stack -> stack.is(Items.DIAMOND_PICKAXE),
                                stack -> stack.is(Items.DIAMOND_SHOVEL),
                                stack -> stack.is(Items.DIAMOND_AXE)),
                        null,
                        godToolsFinisher(silkTouch),
                        true,
                        "msg.auto_trader.combo_success"));
        return submit == TradeRerollScheduler.Submit.QUEUED
                ? TradeRerollScheduler.COMBO_MAX_ATTEMPTS : -8;
    }

    // ==============================================================
    // 目标解析
    // ==============================================================

    /**
     * 名字 → 刷新目标。查不到「这是物品还是附魔」时返回 {@code null}。
     *
     * <p>顺序沿用原实现：<b>先当附魔</b>（{@code mending} / {@code 经验修补}），
     * 再当物品；陶瓦另外识别成「普通 + 釉陶」成对目标。
     */
    public static Target resolveTarget(Villager villager, String raw) {
        if (!(villager.level() instanceof ServerLevel level)) return null;
        Identifier id = IdTranslationConfig.resolveId(raw);
        if (id == null) return null;

        RegistryAccess registryAccess = level.registryAccess();
        Holder<VillagerProfession> profession = villager.getVillagerData().profession();

        // ── 附魔书 ──
        Registry<Enchantment> enchantments = registryAccess.lookupOrThrow(Registries.ENCHANTMENT);
        Enchantment enchantment = enchantments.getValue(id);
        if (enchantment != null) {
            Predicate<VillagerTrade> matcher = TradePoolChecker.givesEnchantment(enchantment);
            int requiredLevel = TradePoolChecker.requiredLevel(profession, registryAccess, matcher);
            if (requiredLevel == TradePoolChecker.NO_LEVEL) {
                return new Target(Route.ENCHANT, TradePoolChecker.NO_LEVEL, List.of(), null);
            }
            return new Target(Route.ENCHANT, requiredLevel,
                    List.of(stack -> isBookWith(stack, enchantment)),
                    // 命中后把这本书的附魔等级覆写到满级
                    new TradeRerollScheduler.Upgrade(enchantments.getKey(enchantment),
                            enchantment.getMaxLevel()));
        }

        // ── 物品 ──
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == Items.AIR) return null;

        if (isPlainTerracotta(item)) {
            Item glazed = findGlazedCounterpart(item);
            if (glazed == null) return null;
            Predicate<VillagerTrade> plainMatcher = TradePoolChecker.givesItem(item);
            Predicate<VillagerTrade> glazedMatcher = TradePoolChecker.givesItem(glazed);
            int pairLevel = pairLevel(profession, registryAccess, plainMatcher, glazedMatcher);
            if (pairLevel == TradePoolChecker.NO_LEVEL) {
                return new Target(Route.TERRACOTTA, TradePoolChecker.NO_LEVEL, List.of(), null);
            }
            return new Target(Route.TERRACOTTA, pairLevel,
                    List.of(stack -> stack.is(item), stack -> stack.is(glazed)),
                    null);
        }

        Predicate<VillagerTrade> matcher = TradePoolChecker.givesItem(item);
        int requiredLevel = TradePoolChecker.requiredLevel(profession, registryAccess, matcher);
        if (requiredLevel == TradePoolChecker.NO_LEVEL) {
            return new Target(Route.NORMAL, TradePoolChecker.NO_LEVEL, List.of(), null);
        }
        return new Target(Route.NORMAL, requiredLevel,
                List.of(stack -> stack.is(item)), null);
    }

    /**
     * 「普通陶瓦 + 对应釉陶瓦」都能出的最低等级。
     */
    private static int pairLevel(Holder<VillagerProfession> profession,
                                 RegistryAccess registryAccess,
                                 Predicate<VillagerTrade> first,
                                 Predicate<VillagerTrade> second) {
        for (int lv = 1; lv <= TERRACOTTA_MAX_LEVEL; lv++) {
            if (!TradePoolChecker.candidatesAtLevel(profession, registryAccess, lv, first).isEmpty()
                    && !TradePoolChecker.candidatesAtLevel(profession, registryAccess, lv, second).isEmpty()) {
                return lv;
            }
        }
        return TradePoolChecker.NO_LEVEL;
    }

    // ==============================================================
    // 331a / 331b 的命中后加工
    // ==============================================================

    /**
     * 331 的「事后补齐」：三件钻石工具全部改写三附魔，缺哪件就补哪件。
     *
     * <p>为什么还要补齐：工具匠（{@link #GOD_TOOLS_PROFESSIONS}）的交易集里根本没有
     * 钻石镐（`requiredLevel` 矩阵里 {@code weaponsmith -> diamond_pickaxe=-1}），
     * 所以「三件套全齐」这个命中条件对它永远不成立 ——
     * 500 次跑满后用最后一次结果补齐，行为与旧版一致。
     */
    private static TradeRerollScheduler.Finisher godToolsFinisher(boolean silkTouch) {
        return (offers, level) -> {
            boolean hasPickaxe = false, hasShovel = false, hasAxe = false;
            Integer refMaxUses = null;
            Integer refXp = null;

            for (MerchantOffer offer : offers) {
                ItemStack result = offer.getResult();
                boolean isPick = result.is(Items.DIAMOND_PICKAXE);
                boolean isShovel = result.is(Items.DIAMOND_SHOVEL);
                boolean isAxe = result.is(Items.DIAMOND_AXE);
                if (!isPick && !isShovel && !isAxe) continue;

                if (refMaxUses == null) {
                    refMaxUses = offer.getMaxUses();
                    refXp = offer.getXp();
                }
                applyGodEnchants(result, level, silkTouch);
                if (isPick) hasPickaxe = true;
                if (isShovel) hasShovel = true;
                if (isAxe) hasAxe = true;
            }

            int maxUses = refMaxUses != null ? refMaxUses : FALLBACK_MAX_USES;
            int xp = refXp != null ? refXp : FALLBACK_XP;

            if (!hasPickaxe) offers.add(buildGodOffer(Items.DIAMOND_PICKAXE, level, silkTouch, maxUses, xp));
            if (!hasShovel) offers.add(buildGodOffer(Items.DIAMOND_SHOVEL, level, silkTouch, maxUses, xp));
            if (!hasAxe) offers.add(buildGodOffer(Items.DIAMOND_AXE, level, silkTouch, maxUses, xp));
        };
    }

    private static MerchantOffer buildGodOffer(Item tool, ServerLevel level, boolean silkTouch, int maxUses, int xp) {
        ItemStack stack = new ItemStack(tool);
        applyGodEnchants(stack, level, silkTouch);
        int price = fallbackPrice(level);
        ItemCost cost = new ItemCost(Items.EMERALD, price);
        return new MerchantOffer(cost, stack, maxUses, xp, 0.05F);
    }

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

    private static Holder<Enchantment> holder(Registry<Enchantment> reg, String id) {
        Identifier i = Identifier.tryParse(id);
        if (i == null) return null;
        Enchantment e = reg.getValue(i);
        return e == null ? null : reg.wrapAsHolder(e);
    }

    // ==============================================================
    // 写回 / 同步
    // ==============================================================

    /**
     * 写回实体 + 锁等级 + 锁定 + 刷新已打开的交易界面 + 立即更新查询索引。
     *
     * <p><b>必须走 {@code @Accessor("offers")}，不能用 {@code Merchant#overrideOffers}</b>：
     * 26.x 的 {@code AbstractVillager#overrideOffers} 是<b>空实现</b>
     * （{@code overrideXp} 同），调它等于什么都没发生 —— 旧实现之所以一直用
     * {@code setOffersField} 正是这个原因。
     *
     * @param lockLevel 命中后把村民等级固定到这一级（{@code <= 0} 表示不动等级）
     */
    static void commit(ServerLevel level, Villager villager, MerchantOffers offers,
                       int lockLevel, boolean doLock) {
        ((AbstractVillagerInterfaceMixin) villager).setOffersField(offers);
        if (lockLevel > 0) {
            villager.setVillagerData(villager.getVillagerData().withLevel(lockLevel));
        }
        doLockIfNeeded(villager, offers, doLock);
        syncOpenMenu(villager);
        // 交易表刚被换过，索引里的旧快照立刻作废：成功刷新的村民应马上能被查到
        VillagerTradePipeline.captureNow(villager);
    }

    /**
     * 玩家正开着这只村民的交易界面时，把新报价发过去（否则界面里还是旧表）。
     */
    private static void syncOpenMenu(Villager villager) {
        if (!(villager.getTradingPlayer() instanceof ServerPlayer player)) return;
        if (!(player.containerMenu instanceof MerchantMenu menu)) return;
        player.sendMerchantOffers(menu.containerId, villager.getOffers(),
                villager.getVillagerData().level(), villager.getVillagerXp(),
                villager.showProgressBar(), villager.canRestock());
    }

    // ==============================================================
    // 内部工具
    // ==============================================================

    /**
     * 是不是「带这个附魔」的附魔书（等级不限，命中后会覆写到满级）。
     */
    private static boolean isBookWith(ItemStack stack, Enchantment target) {
        if (stack == null || !stack.is(Items.ENCHANTED_BOOK)) return false;
        ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored == null) return false;
        for (Holder<Enchantment> holder : stored.keySet()) {
            if (holder.value() == target) return true;
        }
        return false;
    }

    /**
     * 现版本的锁：第一笔交易用一次（防止被别的玩家先买走）+ 给 1 点经验
     * （{@code getVillagerXp() > 0} 之后不再允许重掷）。
     */
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

    private static boolean isProfessionAllowed(Villager villager,
                                               Set<ResourceKey<VillagerProfession>> whitelist) {
        if (villager == null) return false;
        return villager.getVillagerData().profession()
                .unwrapKey()
                .map(key -> !whitelist.contains(key))   // 不在白名单 → true
                .orElse(true);                           // 无职业 → 禁止
    }

    /**
     * 是否「不可重掷」：客户端 / 小孩 / 已经赚过经验 / 无职业 / 没有工作站。
     * 返回 {@code true} 表示应当跳过。
     */
    private static boolean isRerollable(Villager villager) {
        if (villager.level().isClientSide()) return true;
        if (villager.isBaby()) return true;
        if (villager.getVillagerXp() > 0) return true;
        if (villager.getVillagerData().profession().is(VillagerProfession.NONE)) return true;
        return villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).isEmpty();
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

    /**
     * 兜底价格：原版钻石镐大致在 18~32 绿宝石之间随机
     */
    private static int fallbackPrice(ServerLevel level) {
        return level.getRandom().nextInt(18, 29);
    }
}
