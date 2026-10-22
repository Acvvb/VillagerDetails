package com.villagerdetails.client.cache.impl;

import com.villagerdetails.client.cache.base.ClientSessionState;
import com.villagerdetails.client.cache.base.ClientState;
import com.villagerdetails.rule.descriptor.RuleDescriptor;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * 「服务器规则」的纯数据缓存容器。
 *
 * <p>不含任何网络请求和业务逻辑，仅负责线程安全地维护最新快照；
 * 组装 / 探测 / 查询那些事全在 {@link com.villagerdetails.client.setting.rule.ServerRuleHandler} 里。
 * 被扫包框架自动接管断线清理。
 */
public final class ServerRuleCache implements ClientSessionState {

    /**
     * 单例：扫包框架靠它拿实例，调用方统一写 {@code ServerRuleCache.INSTANCE.xxx()}
     */
    public static final ServerRuleCache INSTANCE = new ServerRuleCache();

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

        public static final Snapshot EMPTY = new Snapshot(false, List.of(), Map.of(), 0);

        public Snapshot withAvailable(boolean value) {
            return new Snapshot(value, categories, rules, version);
        }

        public Snapshot withDescriptors(List<RuleDescriptor.Category> newCategories,
                                        Map<String, RuleDescriptor> newRules) {
            return new Snapshot(available, newCategories, newRules, version);
        }

        public Snapshot withNextVersion() {
            return new Snapshot(available, categories, rules, version + 1);
        }
    }

    private final ClientState<Snapshot> state = new ClientState<>(Snapshot.EMPTY);

    private ServerRuleCache() {
    }

    public Snapshot get() {
        return state.get();
    }

    public void update(UnaryOperator<Snapshot> operator) {
        state.update(operator);
    }

    /**
     * 断线清理，但保留版本号，避免界面把「已同步的版本」误判成回退。
     */
    @Override
    public void clearSession() {
        update(s -> new Snapshot(false, List.of(), Map.of(), s.version()));
    }
}
