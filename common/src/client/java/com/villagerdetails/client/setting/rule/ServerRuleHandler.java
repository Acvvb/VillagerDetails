package com.villagerdetails.client.setting.rule;

import com.villagerdetails.client.cache.impl.ServerRuleCache;
import com.villagerdetails.client.network.requests.ClientRequests;
import com.villagerdetails.rule.descriptor.RuleDescriptor;

import java.util.*;

/**
 * 「服务器规则」的业务处理器（对外门面）。
 *
 * <p>负责网络可用性探测、数据组装入库，以及向界面提供业务查询。
 * 底层数据存取全部托管给 {@link ServerRuleCache}；断线清理由那边（扫包接管的
 * {@code clearSession()}）负责，这里不再保留一份重复的清理入口。
 *
 * <p>本类自身不持有任何会话数据（全是无状态的转发），所以只提供一个
 * {@link #INSTANCE} 供调用方统一写 {@code ServerRuleHandler.INSTANCE.xxx()}。
 */
public final class ServerRuleHandler {

    /**
     * 单例：和缓存层保持同一套调用姿势（{@code INSTANCE.xxx()}）
     */
    public static final ServerRuleHandler INSTANCE = new ServerRuleHandler();

    private ServerRuleHandler() {
    }

    // ==============================================================
    // 服务器装没装本 mod
    // ==============================================================

    /**
     * 服务器有没有装本 mod（未联机 / 单机集成服未加载时按 false 处理）
     */
    public boolean isAvailable() {
        boolean detected = ClientRequests.INSTANCE.canSync();
        if (detected != ServerRuleCache.INSTANCE.get().available()) {
            ServerRuleCache.INSTANCE.update(s -> s.withAvailable(detected));
        }
        return detected;
    }

    // ==============================================================
    // 数据写入（解析、组装、存入缓存）
    // ==============================================================

    /**
     * 收到描述符后整份替换（别合并：服务端没下发的就是不该显示的）
     */
    public void setDescriptors(List<RuleDescriptor.Category> categories,
                               List<RuleDescriptor> rules) {
        Map<String, RuleDescriptor> byName = new LinkedHashMap<>();
        for (RuleDescriptor rule : rules) {
            byName.put(rule.registerName(), rule);
        }
        List<RuleDescriptor.Category> categoriesCopy = List.copyOf(categories);
        // 用不可变 LinkedHashMap 包一层：Map.copyOf 不保证迭代顺序，而行的顺序就是服务端给的顺序
        Map<String, RuleDescriptor> rulesCopy = Collections.unmodifiableMap(byName);

        ServerRuleCache.INSTANCE.update(s -> s.withDescriptors(categoriesCopy, rulesCopy));
    }

    /**
     * 收到服务端同步后调用
     */
    public void bumpVersion() {
        ServerRuleCache.INSTANCE.update(ServerRuleCache.Snapshot::withNextVersion);
    }

    public int getVersion() {
        return ServerRuleCache.INSTANCE.get().version();
    }

    // ==============================================================
    // 业务查询（只读，直接从 Cache 拿快照过滤）
    // ==============================================================

    /**
     * 二级分类页签（服务端顺序）
     */
    public List<RuleDescriptor.Category> categories() {
        return ServerRuleCache.INSTANCE.get().categories();
    }

    /**
     * 全部规则（服务端顺序）
     */
    public List<RuleDescriptor> rules() {
        return List.copyOf(ServerRuleCache.INSTANCE.get().rules().values());
    }

    /**
     * 某个二级分类下的规则（保持服务端给的全局顺序）
     */
    public List<RuleDescriptor> rulesIn(String categoryRegisterName) {
        if (categoryRegisterName == null) {
            return List.of();
        }
        List<RuleDescriptor> matched = new ArrayList<>();
        for (RuleDescriptor rule : ServerRuleCache.INSTANCE.get().rules().values()) {
            if (rule.categories().contains(categoryRegisterName)) {
                matched.add(rule);
            }
        }
        return List.copyOf(matched);
    }

    /**
     * 按注册名取描述符；服务端没下发过就是 null
     */
    public RuleDescriptor rule(String registerName) {
        return registerName == null ? null : ServerRuleCache.INSTANCE.get().rules().get(registerName);
    }

    /**
     * 「服务端配置」里现在该显示哪些规则：服务端下发了逐条配置模板（= 支持逐条配置）
     * <b>而且</b>规则是开着的。一条都没有时这个一级分类整个不显示。
     *
     * <p>这两个条件都是服务端下发的数据，客户端只是把它们拼起来 —— 不涉及规则语义。
     */
    public List<RuleDescriptor> configurableRules() {
        List<RuleDescriptor> rules = new ArrayList<>();
        for (RuleDescriptor rule : ServerRuleCache.INSTANCE.get().rules().values()) {
            if (rule.template() != null && rule.enabled()) {
                rules.add(rule);
            }
        }
        return List.copyOf(rules);
    }

    /**
     * 至少有一条能逐条配置的规则吗
     */
    public boolean hasConfigurableRules() {
        for (RuleDescriptor rule : ServerRuleCache.INSTANCE.get().rules().values()) {
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
    public boolean isServerCategoryVisible() {
        return isAvailable() && !ServerRuleCache.INSTANCE.get().rules().isEmpty();
    }
}
