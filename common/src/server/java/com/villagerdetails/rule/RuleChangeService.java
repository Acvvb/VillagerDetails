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
     */
    public static RuleSwitches.Canonical change(RuleType rule, String rawState, @Nullable CommandSourceStack source) {
        Objects.requireNonNull(rule, "rule");
        RuleSwitches.Canonical canonical = RuleSwitches.canonicalize(rule.getQuickSwitches(), rawState);

        if (canonical instanceof RuleSwitches.Canonical.Ok(String state)) {
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

    public static RuleSwitches.Canonical reset(RuleType rule, @Nullable CommandSourceStack source) {
        Objects.requireNonNull(rule, "rule");
        String state = rule.getState();

        RuleChangeContext.setSource(source);
        try {
            RuleCache.setState(rule, state);
        } finally {
            RuleChangeContext.clear();
        }
        return new RuleSwitches.Canonical.Ok(state);
    }

    public static RuleSwitches.Canonical reset(RuleType rule) {
        return reset(rule, null);
    }

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