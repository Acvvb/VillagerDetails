package com.villagerdetails.rule;

import net.minecraft.commands.CommandSourceStack;
import org.jetbrains.annotations.Nullable;

/**
 * 规则修改时的上下文环境，用于在 ThreadLocal 中传递操作来源。
 */
public class RuleChangeContext {
    private static final ThreadLocal<CommandSourceStack> CURRENT_SOURCE = new ThreadLocal<>();

    public static void setSource(@Nullable CommandSourceStack source) {
        if (source != null) {
            CURRENT_SOURCE.set(source);
        } else {
            CURRENT_SOURCE.remove();
        }
    }

    @Nullable
    public static CommandSourceStack getSource() {
        return CURRENT_SOURCE.get();
    }

    public static void clear() {
        CURRENT_SOURCE.remove();
    }
}