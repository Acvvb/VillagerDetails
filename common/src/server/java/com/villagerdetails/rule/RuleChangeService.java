package com.villagerdetails.rule;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.RuleConfig;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 「改规则」的唯一入口。
 */
public final class RuleChangeService {

    private RuleChangeService() {
    }

    /**
     * 标记当前是否在批量重载/启动回灌阶段。
     */
    public static boolean isReloading = false;

    /**
     * 带操作来源的修改入口（用于指令和 UI）。
     *
     * <p>三道校验，顺序固定：
     * <ol>
     *     <li>{@link RuleSwitches#canonicalize}：空值 / 预定义列表命中；</li>
     *     <li>{@link RuleType#getStateValidator()}：规则自己的格式 / 范围要求
     *         （例如乐魂速度必须是 0.0 ~ 5.0 的数字）。<b>必须在这里、写缓存之前</b>拦，
     *         命令和收包端才会给出同一句提示，非法值也进不了 {@link RuleCache}；</li>
     *     <li>写入 + 落盘 + 触发回调与监听器。</li>
     * </ol>
     */
    public static RuleSwitches.Canonical change(RuleType rule, String rawState, @Nullable CommandSourceStack source) {
        Objects.requireNonNull(rule, "rule");
        RuleSwitches.Canonical canonical = RuleSwitches.canonicalize(rule.getQuickSwitches(), rawState);

        if (canonical instanceof RuleSwitches.Canonical.Ok(String state)) {
            RuleStateValidator validator = rule.getStateValidator();
            if (validator != null) {
                RuleStateValidator.ValidationError error = validator.validate(state);
                if (error != null) {
                    // 直接返回：不写内存、不落盘、不触发回调 —— 被拒的修改必须一点痕迹都不留
                    return new RuleSwitches.Canonical.Rejected(state, error.messageKey(), error.args());
                }
            }
            RuleChangeContext.setSource(source);
            try {
                RuleCache.setState(rule, state);
            } finally {
                RuleChangeContext.clear();
            }
        }
        return canonical;
    }

    /**
     * 兼容不带 source 的旧调用（系统自动触发）。
     */
    public static RuleSwitches.Canonical change(RuleType rule, String rawState) {
        return change(rule, rawState, null);
    }

    /**
     * 带操作来源的「恢复默认」入口（用于指令和 UI）。
     *
     * <p>与 {@link #change} 的区别：这里<b>不做合法性校验</b>，直接把
     * {@link RuleType#getState()} 里定义时的默认值写回，所以返回的一直是
     * {@link RuleSwitches.Canonical.Ok}。
     *
     * <p>写回仍然走 {@link RuleCache#setState}，因此会一并落盘并触发回调与监听器；
     * 期间由 {@link RuleChangeContext} 携带 {@code source}，让回调知道这次是谁触发的。
     */
    public static RuleSwitches.Canonical reset(RuleType rule, @Nullable CommandSourceStack source) {
        Objects.requireNonNull(rule, "rule");
        // 默认值取自规则定义本身，而不是配置文件里的当前值
        String state = rule.getState();

        RuleChangeContext.setSource(source);
        try {
            RuleCache.setState(rule, state);
        } finally {
            RuleChangeContext.clear();
        }
        return new RuleSwitches.Canonical.Ok(state);
    }

    /**
     * 批量重载入口：绑定服务器后把磁盘配置回灌到规则缓存。
     *
     * <p>{@link #isReloading} 只在回灌期间为 true，用于让回调跳过
     * 「逐条下发客户端」之类不该在重载时做的动作。
     */
    public static void reload(MinecraftServer server) {
        isReloading = true;
        try {
            RuleCache.setServer(server);
            RuleConfig.getOrCreate(server).syncToSwitch();
        } finally {
            isReloading = false;
        }
    }
}