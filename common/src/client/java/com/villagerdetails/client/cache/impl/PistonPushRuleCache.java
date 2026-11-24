package com.villagerdetails.client.cache.impl;

import com.villagerdetails.cache.base.SessionState;
import com.villagerdetails.client.cache.base.ClientState;
import com.villagerdetails.rule.descriptor.RuleDescriptor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.HashSet;
import java.util.Set;

/**
 * 「活塞推动控制」规则在本地的镜像：<b>与原版判定不同的方块集合</b>。
 *
 * <p><b>数据从哪来</b>：描述符全量同步（{@code rule_descriptor_sync} → {@link ServerRuleCache}）。
 * 服务端每条规则的逐条配置内容（id + 值）本来就整份下发 —— 配置界面的列表页就是拿它画的，
 * 「可采集 / 无挤压 / 禁止生成」这些规则一个字节都没为客户端多写代码。
 * 活塞推动是同一套东西（{@code IdSetConfig} + {@code RuleDataRegistry}），所以这里也只是
 * <b>把其中那一段取出来</b>，不另开通道、不订阅 {@code block_rule_sync}。
 *
 * <p>为什么不读 {@code block_rule_sync}：那条是<b>会被客户端静音</b>的业务通道
 * （{@code SyncSubscriptionClient} / {@code canSendTo}），拿它当判定依据 ——
 * 玩家一关掉这个通道的显示开关，本地推动判定就静默失效了。描述符同步没有开关，
 * 且服务端每秒复查一次（{@code RuleDescriptorSyncEvents}），内容变了才发。
 *
 * <p><b>为什么还要这份缓存</b>：描述符里的数据是 {@code List<DataEntry>}，每次判定都线性扫一遍、
 * 还要把字符串解析成 {@link Block} 是不可能的（{@code isPushable} 在活塞结构扫描里被反复调）。
 * 这里按描述符的 {@code version} 做懒重建：版本没变就是「一次 volatile 读 + 一次哈希查找」，
 * 版本变了才重新解析一遍（只在描述符真变了的那一次发生）。
 *
 * <p>断线清理由 {@code ClientSessionStates} 扫包自动接管（本类实现 {@link SessionState}）。
 */
public final class PistonPushRuleCache implements SessionState {

    /**
     * 服务端那条规则的注册名（{@code RuleType.CUSTOM_PISTON_PUSH_BLOCK} 的第一个构造参数）。
     *
     * <p>这里只能写死字符串：{@code RuleType} 在 <b>server</b> 源集里，client 编译期看不见它。
     * 改注册名时必须和 {@code RuleType} 里那一行一起改（那边是唯一事实来源）。
     */
    public static final String RULE_NAME = "CustomPistonPushBlock";

    /**
     * 单例：扫包框架靠它拿实例，调用方统一写 {@code PistonPushRuleCache.INSTANCE.xxx()}
     */
    public static final PistonPushRuleCache INSTANCE = new PistonPushRuleCache();

    /**
     * 一次描述符同步解析出来的镜像。
     *
     * @param version 描述符的版本号（{@link ServerRuleCache.Snapshot#version()}）；对不上就重建
     * @param enabled 服务端那条规则开着没（关掉 = 一律不覆盖，和 {@code RuleCache.isEnabled} 语义一致）
     * @param flips   要取反的方块
     */
    public record Snapshot(int version, boolean enabled, Set<Block> flips) {

        /**
         * 空镜像：还没同步 / 规则没开 / 服务端没下发这条规则。
         *
         * <p>版本号给 -1：任何真实版本都对不上，下一次判定会重建一次。
         * 声明在嵌套 record 里而不是外层类里，避开 {@code INSTANCE} 初始化顺序的坑
         * （{@code ClientState} 的构造会读它，外层静态字段按声明顺序初始化）。
         */
        public static final Snapshot EMPTY = new Snapshot(-1, false, Set.of());

        /**
         * 这个方块要不要把原版判定取反
         */
        public boolean flips(Block block) {
            return enabled && block != null && flips.contains(block);
        }
    }

    private final ClientState<Snapshot> state = new ClientState<>(Snapshot.EMPTY);

    private PistonPushRuleCache() {
    }

    /**
     * 这个方块要不要取反，热路径唯一入口。
     */
    public boolean flips(Block block) {
        return snapshot().flips(block);
    }

    /**
     * 断线 / 切世界清空（由 {@code ClientSessionStates} 统一调用）。
     */
    @Override
    public void clearSession() {
        state.set(Snapshot.EMPTY);
    }

    // ==============================================================
    // 内部
    // ==============================================================

    /**
     * 当前镜像：版本对得上就直接返回，对不上就按最新的描述符重建一份。
     *
     * <p>重建没有加锁：两个线程同时重建只会各算出同一份结果、互相覆盖一次，
     * 不会出现「一半新一半旧」的中间态（每次都是整份不可变快照）。
     */
    private Snapshot snapshot() {
        ServerRuleCache.Snapshot rules = ServerRuleCache.INSTANCE.get();
        int version = rules.version();
        Snapshot cached = state.get();
        if (cached.version() == version) {
            return cached;
        }
        Snapshot rebuilt = build(version, rules.rules().get(RULE_NAME));
        state.set(rebuilt);
        return rebuilt;
    }

    /**
     * 把描述符里这条规则的 {@code id → 值} 列表解析成方块集合。
     *
     * <p>规则没开 / 服务端没下发这条规则 → 空镜像（本地一律不覆盖）。
     */
    private static Snapshot build(int version, RuleDescriptor rule) {
        if (rule == null || !rule.enabled()) {
            return new Snapshot(version, false, Set.of());
        }
        Set<Block> flips = new HashSet<>();
        for (RuleDescriptor.DataEntry entry : rule.data()) {
            Identifier id = Identifier.tryParse(entry.id() == null ? "" : entry.id().trim());
            if (id == null) {
                continue;
            }
            // 客户端注册表里没有的 id（服务端装了别的模组 / 版本不一致）跳过：
            // 本机没有这个方块，也就谈不上推动判定
            BuiltInRegistries.BLOCK.getOptional(id).ifPresent(flips::add);
        }
        // 不可变快照：热路径只读，不用加锁也不用防御性拷贝
        return new Snapshot(version, true, Set.copyOf(flips));
    }
}
