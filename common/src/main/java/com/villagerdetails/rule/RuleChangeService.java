package com.villagerdetails.rule;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.RuleConfig;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;

/**
 * 「改规则」的<b>唯一入口</b>：校验 → 规范化 → 写内存 → 落盘。
 *
 * <p>命令（{@code /ec <规则> <状态>}）、客户端界面（收包端）、配置回灌都必须从这里走，
 * 免得同一个动作在不同入口上出现「一个拦、一个放」的差异。
 *
 * <table>
 *     <tr><th>调用方</th><th>调什么</th></tr>
 *     <tr><td>{@code RuleCommand}</td><td>{@link #change} / {@link #reset}</td></tr>
 *     <tr><td>{@code ServerRuleSetReceiver}（客户端 UI 改规则）</td><td>{@link #change} / {@link #reset}</td></tr>
 *     <tr><td>启动 / {@code reload} / 迁移</td><td>{@link #reload}、{@link #change}</td></tr>
 * </table>
 *
 * <p>不负责的事：<b>权限</b>（命令走 brigadier 的 requires，收包端自己判
 * {@link RuleCache#lacksRuleCommandPermission}）、<b>提示文案</b>（各自 render），
 * 以及<b>网络广播</b> —— 状态变化由 {@link RuleCache} 的监听器接线广播出去。
 */
public final class RuleChangeService {

    private RuleChangeService() {
    }

    /**
     * 校验并写入一个新状态。
     *
     * @return 校验结果：{@link RuleSwitches.Canonical.Ok} 表示已经写进去并落盘；
     * {@code BadState} / {@code BadOption} 表示被拒，缓存没有被动过
     */
    public static RuleSwitches.Canonical change(RuleType rule, String rawState) {
        Objects.requireNonNull(rule, "rule");
        RuleSwitches.Canonical canonical = RuleSwitches.canonicalize(rule, rawState);
        if (canonical instanceof RuleSwitches.Canonical.Ok(String state)) {
            RuleCache.setState(rule, state);
        }
        return canonical;
    }

    /**
     * 把规则恢复到它自己声明的默认状态（{@link RuleType#getState()}）
     */
    public static RuleSwitches.Canonical reset(RuleType rule) {
        Objects.requireNonNull(rule, "rule");
        String state = rule.getState();
        RuleCache.setState(rule, state);
        return new RuleSwitches.Canonical.Ok(state);
    }

    /**
     * 从磁盘回灌：把配置文件里的状态批量同步进内存（服务器启动、{@code reload}）。
     */
    public static void reload(MinecraftServer server) {
        RuleCache.setServer(server);
        RuleConfig.getOrCreate(server).syncToSwitch();
    }
}
