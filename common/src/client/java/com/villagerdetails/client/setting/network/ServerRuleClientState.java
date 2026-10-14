package com.villagerdetails.client.setting.network;

import com.villagerdetails.client.network.sync.ClientRequests;
import com.villagerdetails.rule.descriptor.RuleDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * 客户端侧的「服务器」状态：服务端最近一次下发的<b>全量描述符</b>。
 *
 * <p>这里只做两件事：存服务端给的东西、给界面一个版本号。
 * <b>不解析任何规则语义</b> —— 分类顺序、文字、状态、开关选项、逐条配置模板与内容
 * 全部来自 {@link RuleDescriptor}，界面拿到什么画什么。
 *
 * <p>上面这些字段装在一个不可变快照（{@link Snapshot}）里整体替换：读侧拿一次就是同一时刻的
 * 完整状态，不会出现「version 已经是新的、清单还是上一份」这种自相矛盾的中间态。
 * 所有写入都在客户端主线程发生，用一个锁把「读-改-写」串起来。
 */
public final class ServerRuleClientState {

    private static final Object LOCK = new Object();

    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    private ServerRuleClientState() {
    }

    /**
     * 一次同步下来的全部客户端状态。
     *
     * @param available  服务器装没装本 mod（能收我们的 hello）
     * @param categories 二级分类页签（按服务端给的顺序）
     * @param rules      规则注册名 → 描述符（保持服务端给的顺序）
     * @param version    每次同步 +1，界面靠它发现自己该刷新了
     */
    public record Snapshot(boolean available,
                           List<RuleDescriptor.Category> categories,
                           Map<String, RuleDescriptor> rules,
                           int version) {

        static final Snapshot EMPTY = new Snapshot(false, List.of(), Map.of(), 0);

        Snapshot withAvailable(boolean value) {
            return new Snapshot(value, categories, rules, version);
        }

        Snapshot withDescriptors(List<RuleDescriptor.Category> newCategories,
                                 Map<String, RuleDescriptor> newRules) {
            return new Snapshot(available, newCategories, newRules, version);
        }

        Snapshot withNextVersion() {
            return new Snapshot(available, categories, rules, version + 1);
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
        boolean detected = ClientRequests.canSync();
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
    // 服务端下发的描述符
    // ==============================================================

    /**
     * 收到描述符后整份替换（别合并：服务端没下发的就是不该显示的）
     */
    public static void setDescriptors(List<RuleDescriptor.Category> categories,
                                      List<RuleDescriptor> rules) {
        Map<String, RuleDescriptor> byName = new LinkedHashMap<>();
        for (RuleDescriptor rule : rules) {
            byName.put(rule.registerName(), rule);
        }
        List<RuleDescriptor.Category> categoriesCopy = List.copyOf(categories);
        // 用不可变 LinkedHashMap 包一层：Map.copyOf 不保证迭代顺序，而行的顺序就是服务端给的顺序
        Map<String, RuleDescriptor> rulesCopy = Collections.unmodifiableMap(byName);
        update(s -> s.withDescriptors(categoriesCopy, rulesCopy));
    }

    /**
     * 二级分类页签（服务端顺序）
     */
    public static List<RuleDescriptor.Category> categories() {
        return current().categories();
    }

    /**
     * 全部规则（服务端顺序）
     */
    public static List<RuleDescriptor> rules() {
        return List.copyOf(current().rules().values());
    }

    /**
     * 某个二级分类下的规则（保持服务端给的全局顺序）
     */
    public static List<RuleDescriptor> rulesIn(String categoryRegisterName) {
        if (categoryRegisterName == null) {
            return List.of();
        }
        List<RuleDescriptor> matched = new ArrayList<>();
        for (RuleDescriptor rule : current().rules().values()) {
            if (rule.categories().contains(categoryRegisterName)) {
                matched.add(rule);
            }
        }
        return List.copyOf(matched);
    }

    /**
     * 按注册名取描述符；服务端没下发过就是 null
     */
    public static RuleDescriptor rule(String registerName) {
        return registerName == null ? null : current().rules().get(registerName);
    }

    /**
     * 「服务端配置」里现在该显示哪些规则：服务端下发了逐条配置模板（= 支持逐条配置）
     * <b>而且</b>规则是开着的。一条都没有时这个一级分类整个不显示。
     *
     * <p>这两个条件都是服务端下发的数据，客户端只是把它们拼起来 —— 不涉及规则语义。
     */
    public static List<RuleDescriptor> configurableRules() {
        List<RuleDescriptor> rules = new ArrayList<>();
        for (RuleDescriptor rule : current().rules().values()) {
            if (rule.template() != null && rule.enabled()) {
                rules.add(rule);
            }
        }
        return List.copyOf(rules);
    }

    /**
     * 至少有一条能逐条配置的规则吗
     */
    public static boolean hasConfigurableRules() {
        for (RuleDescriptor rule : current().rules().values()) {
            if (rule.template() != null && rule.enabled()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 「服务器」这个一级分类要不要显示：服务器装了本 mod，而且至少有一条规则下发过。
     * 一条都没有（没权限 / 服务端没装）就整个分类都藏掉。
     */
    public static boolean isServerCategoryVisible() {
        return isAvailable() && !current().rules().isEmpty();
    }

    /**
     * 断开连接时整体清空（保留版本号，避免界面把「已同步的版本」误判成回退）。
     *
     * <p>由 {@link com.villagerdetails.client.cache.ClientSessionStates} 统一调用。
     */
    public static void clearSession() {
        update(s -> new Snapshot(false, List.of(), Map.of(), s.version()));
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
