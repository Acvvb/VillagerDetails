package com.villagerdetails.client.cache.base;

import com.villagerdetails.client.cache.impl.ServerRuleCache;
import com.villagerdetails.rule.descriptor.RuleDescriptor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.HashSet;
import java.util.Set;

/**
 * 「某条逐条配置规则 → 方块集合」的客户端派生容器。
 *
 * <p>数据来源是<b>描述符全量同步</b>（{@code rule_descriptor_sync} → {@link ServerRuleCache}）：
 * 服务端每条规则的逐条配置内容（id + 值）本来就整份下发 —— 配置界面的列表页就是拿它画的，
 * 「可采集 / 无挤压 / 禁止生成」这些规则一个字节都没为客户端多写代码。
 *
 * <p><b>为什么还要这份容器</b>：描述符里的数据是 {@code List<DataEntry>}，每次判定都线性扫一遍、
 * 还要把字符串解析成 {@link Block} 是不可能的（{@code isPushable} 在活塞结构扫描里被反复调）。
 * 这里按描述符的 {@code version} 做懒重建：版本没变就是「一次 volatile 读 + 一次哈希查找」，
 * 版本变了才重新解析一遍（只在描述符真变了的那一次发生）。
 *
 * <p><b>不是</b>{@link com.villagerdetails.cache.base.SessionState}：它自己不扫包登记，
 * 由持有它的缓存类（{@code cache.impl} 下那些）负责 {@link #clear()}。
 */
public final class ClientRuleBlockSet {

    /**
     * 一次描述符同步解析出来的镜像。
     *
     * @param version 描述符的版本号（{@link ServerRuleCache.Snapshot#version()}）；对不上就重建
     * @param enabled 服务端那条规则开着没（关掉 = 一律不覆盖，和 {@code RuleCache.isEnabled} 语义一致）
     * @param blocks  名单里的方块
     */
    public record Snapshot(int version, boolean enabled, Set<Block> blocks) {

        /**
         * 空镜像：还没同步 / 规则没开 / 服务端没下发这条规则。
         *
         * <p>版本号给 -1：任何真实版本都对不上，下一次判定会重建一次。
         */
        public static final Snapshot EMPTY = new Snapshot(-1, false, Set.of());

        public boolean contains(Block block) {
            return enabled && block != null && blocks.contains(block);
        }
    }

    private final String registerName;

    private final ClientState<Snapshot> state = new ClientState<>(Snapshot.EMPTY);

    /**
     * @param registerName 规则的注册名（要和服务端 {@code RuleType} 里那一行一致）
     */
    public ClientRuleBlockSet(String registerName) {
        this.registerName = registerName;
    }

    /**
     * 这个方块在不在名单里，热路径唯一入口。
     */
    public boolean contains(Block block) {
        return snapshot().contains(block);
    }

    /**
     * 断线 / 切世界时清空（由持有它的缓存类转调 {@code ClientSessionStates}）
     */
    public void clear() {
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
        Snapshot rebuilt = build(version, rules.rules().get(registerName));
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
        Set<Block> blocks = new HashSet<>();
        for (RuleDescriptor.DataEntry entry : rule.data()) {
            Identifier id = Identifier.tryParse(entry.id() == null ? "" : entry.id().trim());
            if (id == null) {
                continue;
            }
            // 客户端注册表里没有的 id（服务端装了别的模组 / 版本不一致）跳过：
            // 本机没有这个方块，也就谈不上判定
            BuiltInRegistries.BLOCK.getOptional(id).ifPresent(blocks::add);
        }
        // 不可变快照：热路径只读，不用加锁也不用防御性拷贝
        return new Snapshot(version, true, Set.copyOf(blocks));
    }
}
