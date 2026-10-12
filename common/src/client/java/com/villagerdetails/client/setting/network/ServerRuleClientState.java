package com.villagerdetails.client.setting.network;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.client.network.sync.ClientRequests;
import com.villagerdetails.rule.type.RuleDataKind;
import com.villagerdetails.rule.type.RuleType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 客户端侧的「服务器规则」状态：
 * <ul>
 *     <li>{@link #isAvailable()}：服务器是否装了本 mod（能收我们的 C2S payload）</li>
 *     <li>{@link #getVersion()}：每次收到服务端同步就 +1，界面靠它发现自己该刷新了</li>
 *     <li>{@link #isRuleVisible}：这条规则服务端有没有下发过 ——
 *         <b>没下发就是没权限看</b>，界面直接不渲染它</li>
 * </ul>
 *
 * <p>规则本身的状态存在 {@code RuleCache} 里（客户端一份镜像，和服务端同一套读取方式）。
 *
 * <p>上面这些字段装在<b>一个不可变快照</b>（{@link Snapshot}）里整体替换：读侧拿一次就是
 * 同一时刻的完整状态，不会出现「version 已经是新的、清单还是上一份」这种自相矛盾的中间态。
 * 所有写入都在客户端主线程发生，用一个锁把「读-改-写」串起来。
 */
public final class ServerRuleClientState {

    private static final Object LOCK = new Object();

    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    private ServerRuleClientState() {
    }

    /**
     * 一条规则同步下来的数据。
     *
     * @param kind 列表形态（服务端下发的标识决定的：纯名单 / id → 数字）
     * @param data id → 值（纯名单一律是 {@code "true"}），保持服务端给的顺序
     */
    public record RuleData(RuleDataKind kind, LinkedHashMap<String, String> data) {
    }

    /**
     * 一次同步下来的全部客户端状态。
     *
     * @param available   服务器装没装本 mod
     * @param syncedRules 服务端最近一次同步过来的规则注册名（= 我们有权限看的那些）
     * @param ruleData    逐条配置类规则的数据
     * @param version     每次同步 +1，界面靠它发现自己该刷新了
     */
    public record Snapshot(boolean available, Set<String> syncedRules,
                           Map<RuleType, RuleData> ruleData, int version) {

        static final Snapshot EMPTY = new Snapshot(false, Set.of(), Map.of(), 0);

        Snapshot withAvailable(boolean value) {
            return new Snapshot(value, syncedRules, ruleData, version);
        }

        Snapshot withSyncedRules(Set<String> value) {
            return new Snapshot(available, value, ruleData, version);
        }

        Snapshot withRuleData(Map<RuleType, RuleData> value) {
            return new Snapshot(available, syncedRules, value, version);
        }

        Snapshot withNextVersion() {
            return new Snapshot(available, syncedRules, ruleData, version + 1);
        }
    }

    private static Snapshot current() {
        return snapshot;
    }

    private static void update(UnaryOperator<Snapshot> operator) {
        synchronized (LOCK) {
            snapshot = operator.apply(snapshot);
        }
    }

    // ==============================================================
    // 服务器装没装本 mod
    // ==============================================================

    /**
     * 服务器有没有装本 mod（未联机 / 单机集成服未加载时按 false 处理）
     */
    public static boolean isAvailable() {
        boolean detected = ClientRequests.canRequestRuleSync();
        if (detected != current().available()) {
            update(s -> s.withAvailable(detected));
        }
        return detected;
    }

    /**
     * 上一次检测的结果（不重新检测，绘制时用）
     */
    public static boolean isAvailableCached() {
        return current().available();
    }

    // ==============================================================
    // 有权限看的规则清单
    // ==============================================================

    /**
     * 收到同步后整份替换（别合并：没同步过来的就是没权限）
     */
    public static void setSyncedRules(Collection<String> registerNames) {
        Set<String> names = Set.copyOf(registerNames);
        update(s -> s.withSyncedRules(names));
    }

    /**
     * 这条规则服务端下发过吗（= 我们有权限看它）
     */
    public static boolean isRuleVisible(RuleType type) {
        return type != null && current().syncedRules().contains(type.getRegisterName());
    }

    /**
     * 「服务器」这个一级分类要不要显示：服务器装了本 mod，而且至少还有一条规则是有权限看的。
     * 一条都没有就整个分类都藏掉。
     */
    public static boolean isServerCategoryVisible() {
        return isAvailable() && !current().syncedRules().isEmpty();
    }

    // ==============================================================
    // 「逐条配置」规则的数据（服务端按 RuleDataRegistry 下发，带列表形态标识）
    // ==============================================================

    /**
     * 收到同步后整份替换
     */
    public static void setRuleData(Map<RuleType, RuleData> synced) {
        Map<RuleType, RuleData> copy = Map.copyOf(synced);
        update(s -> s.withRuleData(copy));
    }

    /**
     * 这条规则的数据（服务端没下发过就是 null = 不支持或没权限）
     */
    public static RuleData ruleData(RuleType rule) {
        return rule == null ? null : current().ruleData().get(rule);
    }

    /**
     * 「服务器配置项」里现在该显示哪些规则：服务端下发过（= 有权限）
     * <b>而且</b>规则是开着的。一条都没有时这个一级分类整个不显示。
     */
    public static List<RuleType> configurableRules() {
        Snapshot now = current();
        List<RuleType> rules = new ArrayList<>();
        for (Map.Entry<RuleType, RuleData> entry : now.ruleData().entrySet()) {
            if (now.syncedRules().contains(entry.getKey().getRegisterName())
                    && RuleCache.isEnabled(entry.getKey())) {
                rules.add(entry.getKey());
            }
        }
        rules.sort(Comparator.comparing(RuleType::getRegisterName));
        return rules;
    }

    /**
     * 至少有一条能逐条配置的规则吗
     */
    public static boolean hasConfigurableRules() {
        return !configurableRules().isEmpty();
    }

    /**
     * 断开连接时整体清空（保留版本号，避免界面把「已同步的版本」误判成回退）。
     *
     * <p>由 {@link com.villagerdetails.client.cache.ClientSessionStates} 统一调用。
     */
    public static void clearSession() {
        update(s -> new Snapshot(false, Set.of(), Map.of(), s.version()));
    }

    // ==============================================================
    // 版本号（界面靠它发现自己该刷新了）
    // ==============================================================

    public static int getVersion() {
        return current().version();
    }

    /**
     * 收到服务端同步后调用
     */
    public static void bumpVersion() {
        update(Snapshot::withNextVersion);
    }
}
