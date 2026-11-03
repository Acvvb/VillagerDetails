package com.villagerdetails.handler.entity.villager.trader.refresh;

import com.villagerdetails.util.SendMessengerUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 命名牌「点名刷取」的分帧调度器：<b>真实概率模拟</b>，不塞目标、不改概率。
 *
 * <p>玩法：命名牌写物品 / 附魔 → 建一个任务 → 每 tick 用
 * {@link TradeTableBuilder#build} 按原版抽取规则重掷 1..n 级整表，检查有没有目标交易；
 * 全部落空就下一 tick 继续，直到 {@link #MAX_ATTEMPTS} 次或命中。
 *
 * <p>为什么必须分帧：
 * <ul>
 *     <li><b>不卡主线程</b>：单 tick 最多 {@link #ATTEMPTS_PER_TICK} 次，且额外有
 *     {@link #TICK_BUDGET_NANOS} 的时间阀门（超过就提前收工，剩余次数顺延到下一 tick）；</li>
 *     <li><b>不跨线程碰实体</b>：任务<b>只存 UUID + 维度</b>，每 tick 用
 *     {@code level.getEntity(uuid)} 现查实体；实体卸载 / 死亡 / 换维度立刻取消任务，
 *     绝不强引用 {@link Villager} 实例（否则区块卸载后就是 NPE / 幽灵实体）。</li>
 * </ul>
 *
 * <p>生成的整表全程只存在于任务里，命中那一刻才在主线程写回实体，所以
 * 「模拟」期间村民的交易表一个字节都不会动。
 */
public final class TradeRerollScheduler {

    private static final Logger log = LoggerFactory.getLogger(TradeRerollScheduler.class);

    /**
     * 每 tick 的真实模拟次数上限。
     */
    public static final int ATTEMPTS_PER_TICK = 100;

    /**
     * 单个任务的模拟总次数上限（按 {@link #ATTEMPTS_PER_TICK} 算就是 10 tick）。
     */
    public static final int MAX_ATTEMPTS = 1000;

    /**
     * 单 tick 的时间阀门：再便宜也不该为了刷交易把 50ms 的预算吃掉一大半。
     * 超过这个时间就提前结束本 tick 的模拟，剩余次数顺延。
     */
    private static final long TICK_BUDGET_NANOS = 8_000_000L;

    /**
     * 同时进行的任务上限，防刷屏式命名把内存/CPU 顶满。
     */
    public static final int MAX_ACTIVE_TASKS = 64;

    private static final Map<UUID, State> TASKS = new LinkedHashMap<>();

    private TradeRerollScheduler() {
    }

    // ==============================================================
    // 任务描述
    // ==============================================================

    /**
     * 命中后要把结果升级成「满级附魔」时用（附魔书路线）。
     *
     * <p>存的是 id + 等级，<b>不存 {@code Holder}</b>：任务可能跨 tick 存在，
     * 期间要是发生数据包重载，旧的 Holder 实例就作废了；命中时再按 id 现查。
     */
    public record Upgrade(Identifier enchantmentId, int maxLevel) {
    }

    /**
     * 一个待执行的刷新任务。所有字段都是不可变值 / 谓词，<b>不含实体引用</b>。
     *
     * @param villager      村民 UUID（每 tick 现查实体）
     * @param dimension     村民所在维度
     * @param player        发起刷新的玩家 UUID（离线就静默）
     * @param label         玩家输入的原样名字，只用于提示
     * @param requiredLevel 目标最低可出等级；命中后村民等级会被固定到这一级
     * @param tests         命中条件：全部满足才算中（陶瓦要「普通 + 釉陶」两个）
     * @param upgrade       命中后的结果升级，可为 {@code null}
     */
    public record Task(UUID villager,
                       ResourceKey<Level> dimension,
                       UUID player,
                       String label,
                       int requiredLevel,
                       List<Predicate<ItemStack>> tests,
                       Upgrade upgrade) {

        public Task {
            tests = tests == null ? List.of() : List.copyOf(tests);
        }
    }

    /**
     * 提交结果。
     */
    public enum Submit {
        /** 任务已排队（同一村民重复命名会覆盖旧任务）。 */
        QUEUED,
        /** 同时进行的任务太多。 */
        TOO_MANY
    }

    private static final class State {
        final Task task;
        int attempts;

        State(Task task) {
            this.task = task;
        }
    }

    // ==============================================================
    // 入口
    // ==============================================================

    /**
     * 提交一个刷新任务。必须在主线程调用。
     */
    public static Submit submit(Task task) {
        if (task == null || task.villager() == null || task.tests().isEmpty()) {
            return Submit.TOO_MANY;
        }
        if (!TASKS.containsKey(task.villager()) && TASKS.size() >= MAX_ACTIVE_TASKS) {
            return Submit.TOO_MANY;
        }
        // 同一村民再命名：直接换成新任务（旧任务作废，不叠加）
        TASKS.put(task.villager(), new State(task));
        return Submit.QUEUED;
    }

    public static int activeTaskCount() {
        return TASKS.size();
    }

    public static void clear() {
        TASKS.clear();
    }

    // ==============================================================
    // 每 tick 推进
    // ==============================================================

    /**
     * {@code END_SERVER_TICK} 调用：给每个任务跑最多 {@link #ATTEMPTS_PER_TICK} 次真实模拟。
     */
    public static void tick(MinecraftServer server) {
        if (TASKS.isEmpty() || server == null) return;

        long startedAt = Util.getNanos();
        int attemptsBefore = 0;
        for (State state : new ArrayList<>(TASKS.values())) {
            attemptsBefore += state.attempts;
        }
        for (State state : new ArrayList<>(TASKS.values())) {
            // 前一任务可能已经清掉了它 / 被玩家重新命名覆盖
            if (TASKS.get(state.task.villager()) != state) continue;

            ServerLevel level = server.getLevel(state.task.dimension());
            if (level == null) {
                finishLost(server, state);
                continue;
            }
            if (!(level.getEntity(state.task.villager()) instanceof Villager villager)
                    || villager.isRemoved() || !villager.isAlive()) {
                finishLost(server, state);
                continue;
            }

            if (runSlice(server, level, villager, state, startedAt)) {
                logSlice(startedAt, attemptsBefore);
                return;                     // 命中后本次 tick 不再处理其它任务（保持主线程轻量）
            }
        }
        logSlice(startedAt, attemptsBefore);
    }

    /**
     * 只在 DEBUG 下记一行：任务数 / 本 tick 模拟次数 / 耗时，方便调 {@link #ATTEMPTS_PER_TICK}。
     */
    private static void logSlice(long startedAt, int attemptsBefore) {
        if (!log.isDebugEnabled()) return;
        int after = 0;
        for (State state : TASKS.values()) after += state.attempts;
        log.debug("分帧刷新：剩余任务 {}，本 tick 模拟 {} 次，耗时 {} µs",
                TASKS.size(), Math.max(0, after - attemptsBefore), (Util.getNanos() - startedAt) / 1000L);
    }

    /**
     * 一个 tick 的模拟切片。
     *
     * @return {@code true} 表示命中并已写回（调用方可以直接结束本 tick）
     */
    private static boolean runSlice(MinecraftServer server, ServerLevel level, Villager villager,
                                    State state, long tickStartedAt) {
        Task task = state.task;
        int budget = ATTEMPTS_PER_TICK;

        while (budget-- > 0 && state.attempts < MAX_ATTEMPTS) {
            state.attempts++;
            MerchantOffers offers = TradeTableBuilder.build(level, villager, task.requiredLevel(),
                    RandomSource.create(TradeTableBuilder.seedFor(task.villager(), state.attempts)));

            if (!offers.isEmpty() && TradeTableBuilder.containsAll(offers, task.tests())) {
                applyHit(server, level, villager, task, offers, state.attempts);
                return true;
            }

            if (Util.getNanos() - tickStartedAt > TICK_BUDGET_NANOS) {
                break;                      // 时间阀门：剩下的顺延到下一 tick
            }
        }

        if (state.attempts >= MAX_ATTEMPTS) {
            finishFailed(server, state);
        }
        return false;
    }

    /**
     * 命中：主线程写回（附魔满级覆写 → 整表写回 → 锁等级 → 发包 → 刷索引 → 提示）。
     */
    private static void applyHit(MinecraftServer server, ServerLevel level, Villager villager,
                                 Task task, MerchantOffers offers, int attempts) {
        TASKS.remove(task.villager());

        // 附魔书：命中那笔的附魔等级强制覆写到满级
        if (task.upgrade() != null) {
            upgradeOffers(offers, task.tests().getFirst(), task.upgrade(), level);
        }

        AutoVillagerTrader.commit(level, villager, offers, task.requiredLevel(), true);
        notifyPlayer(server, task.player(), Component.translatable(
                "msg.auto_trader.target_success", attempts, task.label()));
    }

    /**
     * 把命中的那笔附魔书交易的附魔等级改成满级（其它附魔、价格、交易次数都不动）。
     */
    private static void upgradeOffers(MerchantOffers offers, Predicate<ItemStack> test, Upgrade upgrade,
                                      ServerLevel level) {
        int index = TradeTableBuilder.indexOfMatch(offers, test);
        if (index < 0) return;

        Enchantment enchantment = level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getValue(upgrade.enchantmentId());
        if (enchantment == null) {
            log.warn("命中后找不到附魔 {}，跳过满级覆写（数据包可能刚被重载）", upgrade.enchantmentId());
            return;
        }

        MerchantOffer offer = offers.get(index);
        ItemStack result = offer.getResult();
        if (result == null || !result.is(Items.ENCHANTED_BOOK)) return;

        ItemEnchantments stored = result.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored == null || stored.isEmpty()) return;

        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(stored);
        boolean touched = false;
        List<Holder<Enchantment>> holders = new ArrayList<>(stored.keySet());
        for (Holder<Enchantment> holder : holders) {
            if (holder.value() != enchantment) continue;
            int newLevel = Math.max(upgrade.maxLevel(), enchantment.getMaxLevel());
            if (stored.getLevel(holder) < newLevel) {
                mutable.set(holder, newLevel);
                touched = true;
            }
        }
        if (!touched) return;

        ItemStack upgraded = result.copy();
        upgraded.set(DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable());
        offers.set(index, new MerchantOffer(offer.getItemCostA(), offer.getItemCostB(), upgraded,
                offer.getMaxUses(), offer.getXp(), offer.getPriceMultiplier()));
    }

    // ==============================================================
    // 收尾
    // ==============================================================

    private static void finishFailed(MinecraftServer server, State state) {
        TASKS.remove(state.task.villager());
        notifyPlayer(server, state.task.player(), Component.translatable(
                "msg.auto_trader.no_hit", state.attempts));
    }

    private static void finishLost(MinecraftServer server, State state) {
        TASKS.remove(state.task.villager());
        notifyPlayer(server, state.task.player(),
                Component.translatable("msg.auto_trader.task_cancelled"));
    }

    private static void notifyPlayer(MinecraftServer server, UUID playerId, Component message) {
        if (playerId == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;             // 玩家下线：静默
        SendMessengerUtils.sendOverlayOrBroadcast(player, message);
    }
}
