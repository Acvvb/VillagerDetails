package com.villagerdetails.handler.entity.villager.trader.refresh;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import com.villagerdetails.util.ParseUtils;
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
import net.minecraft.world.entity.Entity;
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
 * 村民交易重刷的分帧调度器：<b>所有</b>重刷（点名刷物品/附魔、陶瓦成对、331a/331b 神装工具）
 * 都走同一个队列、同一套「真实概率模拟」。
 *
 * <p>规则很硬：
 * <ul>
 *     <li>每个 tick、每个任务最多跑「每 tick 刷新次数」档位对应的次数
 *     （规则 {@code VillagerOneTickTraderCount}：50 / 100 / 500 / 1000，默认 500），
 *     跑完就 {@code break} 去处理下一个任务，剩下的次数留到下一 tick；</li>
 *     <li><b>没有时间阀门</b>：跑满本 tick 的预算才停，不会因为某一次模拟慢就提前收工；</li>
 *     <li>任务<b>只存 UUID + 维度 + 谓词 + 随机盐值</b>，每 tick 用 {@code level.getEntity(uuid)} 现查实体；
 *     实体卸载 / 死亡 / 换维度立刻取消（绝不强引用 {@link Villager}）；</li>
 *     <li>同一村民再次发起：旧任务<b>立刻取消</b>，只留新的一个。</li>
 * </ul>
 *
 * <p>生成的整表只存在于任务里，命中那一刻才在主线程写回实体，所以「模拟」期间村民的
 * 交易表一个字节都不会动。
 */
public final class TradeRerollScheduler {

    private static final Logger log = LoggerFactory.getLogger(TradeRerollScheduler.class);

    /**
     * 点名刷物品 / 附魔：单任务上限。
     */
    public static final int MAX_ATTEMPTS = 1000;

    /**
     * 331a/331b 三件套：等级 5 的候选池小、命中率高一些，500 次足够。
     */
    public static final int COMBO_MAX_ATTEMPTS = 500;

    /**
     * 同时进行的任务上限，防刷屏式命名把内存 / CPU 顶满。
     */
    public static final int MAX_ACTIVE_TASKS = 64;

    private static final Map<UUID, State> TASKS = new LinkedHashMap<>();

    private TradeRerollScheduler() {
    }

    /**
     * 每 tick 每个任务的模拟次数：读规则 {@code VillagerOneTickTraderCount}
     * （档位 50 / 100 / 500 / 1000，默认 500），并夹到 1~1000。
     *
     * <p>每 tick 只读一次（见 {@link #tick(MinecraftServer)}）：规则状态在 tick 内不会变，
     * 循环里反复读没有意义。
     */
    private static int attemptsPerTick() {
        int v = ParseUtils.toInt(RuleCache.getState(RuleType.VILLAGER_ONE_TICK_TRADER_COUNT), 500);
        return Math.clamp(v, 1, 1000);
    }

    // ==============================================================
    // 任务描述
    // ==============================================================

    /**
     * 命中后要把结果升级成「满级附魔」时用（附魔书路线）。
     */
    public record Upgrade(Identifier enchantmentId, int maxLevel) {
    }

    /**
     * 命中后、写回前对整张表做一次加工（331 的「补齐三件套 + 三附魔」就在这里）。
     */
    public interface Finisher {
        void finish(MerchantOffers offers, ServerLevel level);
    }

    /**
     * 一个待执行的刷新任务。所有字段都是不可变值 / 谓词，<b>不含实体引用</b>。
     *
     * @param randomSalt      触发瞬间抓取的随机盐值，打破固定种子序列
     */
    public record Task(UUID villager,
                       ResourceKey<Level> dimension,
                       UUID player,
                       String label,
                       int requiredLevel,
                       int maxAttempts,
                       List<Predicate<ItemStack>> tests,
                       Upgrade upgrade,
                       Finisher finisher,
                       boolean commitOnExhaust,
                       String successKey,
                       long randomSalt) {

        public Task {
            tests = tests == null ? List.of() : List.copyOf(tests);
            if (maxAttempts <= 0) maxAttempts = MAX_ATTEMPTS;
            if (successKey == null) successKey = "msg.auto_trader.target_success";
        }
    }

    /**
     * 提交结果。
     */
    public enum Submit {
        /** 任务已排队（同一村民的旧任务会被立刻取消）。 */
        QUEUED,
        /** 同时进行的任务太多。 */
        TOO_MANY
    }

    private static final class State {
        final Task task;
        int attempts;
        MerchantOffers lastOffers;

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
        if (TASKS.remove(task.villager()) != null) {
            log.debug("村民 {} 已有刷新任务，已取消旧任务并替换为新任务（{}）",
                    task.villager(), task.label());
        }
        TASKS.put(task.villager(), new State(task));
        return Submit.QUEUED;
    }

    /**
     * 停服清空队列（{@code SERVER_STOPPING} 调用），不会抛异常。
     */
    public static void clear() {
        try {
            TASKS.clear();
        } catch (Throwable t) {
            log.warn("清空分帧刷新队列时出错（已忽略）", t);
        }
    }

    // ==============================================================
    // 每 tick 推进
    // ==============================================================

    /**
     * {@code END_SERVER_TICK} 调用：每个任务本 tick 最多跑 {@link #attemptsPerTick()} 次模拟。
     */
    public static void tick(MinecraftServer server) {
        if (TASKS.isEmpty() || server == null) return;

        // 每 tick 只读一次规则：同一个 tick 里所有任务共用一个预算
        int perTick = attemptsPerTick();

        for (State state : new ArrayList<>(TASKS.values())) {
            // 同一 tick 里前面的任务可能已经清掉 / 覆盖了它
            if (TASKS.get(state.task.villager()) != state) continue;

            Task task = state.task;
            ServerLevel level = server.getLevel(task.dimension());
            Villager villager = level == null ? null : findVillager(level, task.villager());
            if (villager == null) {
                finishLost(server, state);
                continue;
            }

            if (runSlice(server, level, villager, state, perTick)) continue;

            // 次数用尽：331 这类「允许事后补齐」的任务仍然写回最后一次结果
            if (state.attempts >= task.maxAttempts()) {
                if (task.commitOnExhaust() && state.lastOffers != null) {
                    apply(server, level, villager, state, state.lastOffers);
                } else {
                    finishFailed(server, state);
                }
            }
        }
    }

    /**
     * 一个任务在本 tick 的模拟切片（最多 {@code perTick} 次）。
     *
     * @param perTick 本 tick 的模拟次数预算（由规则 {@code VillagerOneTickTraderCount} 决定）
     * @return {@code true} 表示命中并已写回
     */
    private static boolean runSlice(MinecraftServer server, ServerLevel level, Villager villager,
                                    State state, int perTick) {
        Task task = state.task;
        for (int i = 0; i < perTick && state.attempts < task.maxAttempts(); i++) {
            state.attempts++;

            RandomSource random = RandomSource.create(task.randomSalt() + state.attempts);

            MerchantOffers offers = TradeTableBuilder.build(level, villager, task.requiredLevel(), random);
            if (offers.isEmpty()) continue;

            state.lastOffers = offers;
            if (TradeTableBuilder.containsAll(offers, task.tests())) {
                apply(server, level, villager, state, offers);
                return true;
            }
        }
        return false;
    }

    private static Villager findVillager(ServerLevel level, UUID uuid) {
        Entity entity = level.getEntity(uuid);
        if (!(entity instanceof Villager villager)) return null;
        if (villager.isRemoved() || !villager.isAlive()) return null;
        return villager;
    }

    /**
     * 命中 / 补齐：主线程加工（补齐三件套、满级覆写）→ 写回实体 → 提示。
     */
    private static void apply(MinecraftServer server, ServerLevel level, Villager villager,
                              State state, MerchantOffers offers) {
        Task task = state.task;
        TASKS.remove(task.villager());

        if (task.finisher() != null) {
            task.finisher().finish(offers, level);
        }
        if (task.upgrade() != null) {
            upgradeOffers(offers, task.tests().getFirst(), task.upgrade(), level);
        }

        AutoVillagerTrader.commit(villager, offers, task.requiredLevel());
        notifyPlayer(server, task.player(), Component.translatable(
                task.successKey(), state.attempts, task.label()));
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
        if (!result.is(Items.ENCHANTED_BOOK)) return;

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