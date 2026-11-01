package com.villagerdetails.handler.entity.happyghast;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import com.villagerdetails.util.ParseUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「乐魂飞行速度」的唯一实现处。
 *
 * <p>为什么从 Mixin 里搬出来：速度这套逻辑一共有<b>三个</b>入口 ——
 * <ol>
 *     <li>上坐 / 下坐（{@code HappyGhast#addPassenger} / {@code removePassenger}，见
 *         {@code HappyGhastSpeedMixin}）；</li>
 *     <li>周期巡检（{@link #tick(MinecraftServer)}，每 20 tick）——
 *         兜住 still-timeout、挽具被摘、控制者变成非玩家这些"不再触发上下坐"的残留加速；</li>
 *     <li>规则变更（{@code HappyGhastSpeedSectionManager} 里的 RuleCache 监听器）。</li>
 * </ol>
 * 三处必须算同一个"期望值"，放在一个普通类里就不必让外部代码去够 Mixin 的 {@code @Unique} 方法。
 *
 * <p>所有方法都<b>只在主线程</b>调用，内部直接写 {@link AttributeInstance}；
 * 调度入口见 {@code HappyGhastSpeedSectionManager}。
 */
public final class HappyGhastSpeedHandler {

    private static final Logger log = LogManager.getLogger(HappyGhastSpeedHandler.class);

    /**
     * 规则值上限：原版默认速度的 100 倍。
     *
     * <p>手滑写出 {@code /ec HappyGhastSpeed 1000} 这类值时，乐魂会以远超区块加载速度的位移
     * 飞出已加载边界，把乘客甩进未加载区块 / 触发区块加载风暴。
     */
    private static final double MAX_SPEED = 5.0;

    /**
     * 规则值下限：0 表示原地不动（允许，等价于把乐魂钉住），不允许负值。
     */
    private static final double MIN_SPEED = 0.0;

    /**
     * 读不到乐魂默认速度时的兜底值（= 原版 26.2 / 26.3 的 {@code HappyGhast.createAttributes()} 里那个数）。
     *
     * <p>这个常量<b>不是</b>平时用的默认值 —— 默认值一律走 {@link #defaultSpeed()} 现算。
     */
    private static final double FALLBACK_DEFAULT_SPEED = 0.05;

    private static final double EPSILON = 1.0E-6;

    /**
     * 巡检间隔：20 tick = 1 秒一次。
     *
     * <p>不每 tick 全量遍历；而且集合为空时直接返回，不碰实体表。
     */
    private static final int SWEEP_INTERVAL_TICKS = 20;

    /**
     * 被我们<b>写过自定义速度</b>、还没回滚掉的乐魂。
     *
     * <p>巡检只认这个集合，而不是"baseValue 不等于默认值就改回默认" ——
     * 后者会把其它模组给乐魂设的飞行速度每秒改回去一次。集合为空（常态）时巡检零成本。
     */
    private static final Set<UUID> TOUCHED = ConcurrentHashMap.newKeySet();

    /**
     * 已经提示过「速度调高了但 GlobalSpeedBypass 没开」的乐魂。
     *
     * <p>每个乐魂只提示一次；集合本身有上限，避免长期运行的服务器被 UUID 撑大。
     */
    private static final Set<UUID> BYPASS_WARNED = ConcurrentHashMap.newKeySet();

    private static final int BYPASS_WARNED_LIMIT = 512;

    /**
     * {@link #defaultSpeed()} 的缓存；{@code NaN} = 还没算过。
     *
     * <p>必须<b>懒</b>算：{@code HappyGhast.createAttributes()} 会碰 {@code Attributes} 的注册表
     * 持有者，在 Mixin / 类加载早期（注册表还没冻结时）调用是不安全的。
     */
    private static volatile double cachedDefaultSpeed = Double.NaN;

    private static int sweepTicker;

    private HappyGhastSpeedHandler() {
    }

    // ==============================================================
    // 默认值
    // ==============================================================

    /**
     * 乐魂的<b>原版</b>默认飞行速度，直接从实体自己的属性表里读，不硬编码。
     *
     * <p><b>为什么不写 {@code Attributes.FLYING_SPEED.value().getDefaultValue()}：</b>
     * 那个是属性类型的全局默认值 {@code 0.4}（玩家/其它飞行生物用的），
     * 乐魂在 {@code HappyGhast.createAttributes()} 里把它<b>逐实体</b>覆盖成了 {@code 0.05}。
     * 取属性类型的默认值等于把乐魂速度调成原版的 8 倍 —— 这是本条规则唯一的"数字来源"，
     * 所以宁可多写一行从实体侧取，也不要在代码里散落 {@code 0.05}。
     */
    public static double defaultSpeed() {
        double cached = cachedDefaultSpeed;
        if (!Double.isNaN(cached)) {
            return cached;
        }
        double resolved = resolveDefaultSpeed();
        cachedDefaultSpeed = resolved;
        return resolved;
    }

    private static double resolveDefaultSpeed() {
        try {
            double value = HappyGhast.createAttributes().build().getValue(Attributes.FLYING_SPEED);
            if (Double.isFinite(value) && value > 0.0) {
                return value;
            }
            log.warn("[HappyGhastSpeed] 乐魂默认飞行速度读出来是 {}，回落 {}", value, FALLBACK_DEFAULT_SPEED);
        } catch (Throwable t) {
            // 注册表未就绪 / 属性表结构变化：宁可回落常量，也不要让速度规则把服务器带崩
            log.warn("[HappyGhastSpeed] 读取乐魂默认飞行速度失败，回落 {}：{}",
                    FALLBACK_DEFAULT_SPEED, String.valueOf(t));
        }
        return FALLBACK_DEFAULT_SPEED;
    }

    // ==============================================================
    // 写入
    // ==============================================================

    /**
     * 按当前规则 + 骑乘状态写一次速度。
     *
     * <p>给上坐 / 下坐注入用，语义与历史实现一致：<b>不看</b>自己有没有写过，直接对齐到期望值。
     */
    public static void apply(HappyGhast ghast) {
        AttributeInstance attribute = ghast.getAttribute(Attributes.FLYING_SPEED);
        if (attribute == null) return;
        writeBase(ghast, attribute, desiredSpeed(ghast));
    }

    /**
     * 期望的 {@code FLYING_SPEED} 基值。
     *
     * <ul>
     *     <li>规则关闭 → 原版；</li>
     *     <li>控制者不是玩家（无人骑乘 / still-timeout / 挽具被摘 / 被生物骑乘）→ 原版；</li>
     *     <li>否则 → 规则值，收进 {@code [MIN_SPEED, MAX_SPEED]}。</li>
     * </ul>
     */
    public static double desiredSpeed(HappyGhast ghast) {
        double vanilla = defaultSpeed();
        if (!RuleCache.isEnabled(RuleType.HAPPY_GHAST_SPEED)) {
            return vanilla;
        }
        if (!(ghast.getControllingPassenger() instanceof Player)) {
            return vanilla;
        }
        double parsed = ParseUtils.toDouble(
                RuleCache.getState(RuleType.HAPPY_GHAST_SPEED),
                vanilla
        );
        return clampSpeed(parsed);
    }

    /**
     * 把规则里的数值收进 {@code [MIN_SPEED, MAX_SPEED]}。
     *
     * <p><b>非有限值（NaN / ±Infinity）必须单独挡掉</b>：{@code Double.parseDouble("NaN")} 是成功的，
     * 而 {@code Math.min(NaN, 5.0)} 仍然返回 NaN —— 只靠上下限 clamp 会把 NaN 原样写进
     * {@code AttributeInstance}，之后乐魂坐标整片变 NaN。这类值一律回落原版速度。
     *
     * <p>解析失败（如 {@code "abc"}）在 {@link ParseUtils#toDouble} 里已经回落默认值，这里只管数值超限。
     */
    public static double clampSpeed(double parsed) {
        if (!Double.isFinite(parsed)) {
            return defaultSpeed();
        }
        return Math.clamp(parsed, MIN_SPEED, MAX_SPEED);
    }

    private static void writeBase(HappyGhast ghast, AttributeInstance attribute, double desired) {
        // 只在对不上时才写：setBaseValue 会 setDirty + 重算属性修改器，没必要每 tick 都碰
        if (Math.abs(attribute.getBaseValue() - desired) > EPSILON) {
            attribute.setBaseValue(desired);
        }
        if (Math.abs(desired - defaultSpeed()) <= EPSILON) {
            TOUCHED.remove(ghast.getUUID());
        } else {
            TOUCHED.add(ghast.getUUID());
            warnIfBypassMissing(ghast, desired);
        }
    }

    /**
     * 速度被调高、但 {@link RuleType#GLOBAL_SPEED_BYPASS} 没开时提示一次。
     *
     * <p>这两条规则是<b>独立</b>的：只开速度不开 bypass，速度照样生效，但移速过快时仍会被
     * 服务端拉回、并刷 {@code moved too quickly} 日志 —— 管理员很容易以为是自己配错了数值。
     * 这里只提示，<b>绝不</b>替人把 bypass 打开（那会把位移校验悄悄关掉）。
     */
    private static void warnIfBypassMissing(HappyGhast ghast, double desired) {
        if (RuleCache.isEnabled(RuleType.GLOBAL_SPEED_BYPASS)) {
            return;
        }
        UUID uuid = ghast.getUUID();
        if (BYPASS_WARNED.size() >= BYPASS_WARNED_LIMIT || !BYPASS_WARNED.add(uuid)) {
            return;
        }
        log.warn("[HappyGhastSpeed] 乐魂 {} 的飞行速度已设为 {}（原版 {}），但 GlobalSpeedBypass 未开启："
                        + "移速过快时仍可能被服务端拉回并刷 moved too quickly 日志。"
                        + "建议同时执行 /ec GlobalSpeedBypass true（本提示每个乐魂只打一次）。",
                uuid, desired, defaultSpeed());
    }

    // ==============================================================
    // 规则变更后立即重算
    // ==============================================================

    /**
     * 规则变更后立刻重算（骑乘中改规则不必下坐再上坐）。
     *
     * <p>只动两种乐魂：<b>正被玩家骑着的</b>（要马上应用新速度），以及<b>我们写过自定义值、
     * 还没回滚的</b>（规则被关掉时要立刻还原）。其余的乐魂一律不碰 —— 它们的飞行速度可能是
     * 别的模组设的，不能因为改了一条规则就被人改回去。
     */
    public static void refreshAll(MinecraftServer server) {
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof HappyGhast ghast)) continue;
                boolean riddenByPlayer = ghast.getControllingPassenger() instanceof Player;
                if (!riddenByPlayer && !TOUCHED.contains(ghast.getUUID())) continue;

                AttributeInstance attribute = ghast.getAttribute(Attributes.FLYING_SPEED);
                if (attribute == null) continue;
                writeBase(ghast, attribute, desiredSpeed(ghast));
            }
        }
    }

    // ==============================================================
    // 周期巡检（由 SectionManager 注册到 END_SERVER_TICK）
    // ==============================================================

    public static void tick(MinecraftServer server) {
        if (server == null) return;
        if (++sweepTicker < SWEEP_INTERVAL_TICKS) return;
        sweepTicker = 0;
        if (TOUCHED.isEmpty()) return;
        sweep(server);
    }

    /**
     * 把"不再应该有自定义速度"的乐魂改回原版。
     *
     * <p>覆盖的正是上下坐注入抓不到的那些情况：still-timeout、挽具被移除、座位没变但控制者
     * 从玩家变成了别的东西 —— 这些都不会触发 {@code addPassenger} / {@code removePassenger}，
     * 以前速度就卡在自定义值上，无人骑乘的乐魂 AI 飞行也跟着变快。
     */
    private static void sweep(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof HappyGhast ghast)) continue;
                UUID uuid = ghast.getUUID();
                if (!TOUCHED.contains(uuid)) continue;

                AttributeInstance attribute = ghast.getAttribute(Attributes.FLYING_SPEED);
                if (attribute == null) {
                    TOUCHED.remove(uuid);
                    continue;
                }
                double desired = desiredSpeed(ghast);
                double current = attribute.getBaseValue();
                if (Math.abs(current - desired) > EPSILON) {
                    log.debug("[HappyGhastSpeed] 回滚残留速度：{} {} → {}（控制者：{}）",
                            uuid, current, desired,
                            ghast.getControllingPassenger() == null ? "无" : "非玩家");
                    writeBase(ghast, attribute, desired);
                } else if (Math.abs(desired - defaultSpeed()) <= EPSILON) {
                    TOUCHED.remove(uuid);
                }
            }
        }
    }

    /**
     * 服务端停机 / 换存档时清掉静态状态。
     *
     * <p>不清的话，下一个世界会用着上一个世界的 UUID 集合（虽然只影响一次遍历，但没必要）。
     */
    public static void resetState() {
        TOUCHED.clear();
        BYPASS_WARNED.clear();
        sweepTicker = 0;
    }
}
