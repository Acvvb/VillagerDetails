package com.villagerdetails.cache;

import com.villagerdetails.VillagerDetails;
import com.villagerdetails.command.SwitchComponentType;
import com.villagerdetails.event.type.ListenerType;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * 规则管理器
 * 管理模组内各类可配置的字符串型规则开关（运行时内存层）
 */
public class RuleCache {

    private static final Map<RuleType, SwitchComponentType> rules = new EnumMap<>(RuleType.class);

    static {
        // 默认使用枚举中定义的 state 值
        for (RuleType type : RuleType.values()) {
            rules.put(type, type.getState());
        }
    }

    /**
     * 根据 RuleType 查询规则状态
     */
    public static SwitchComponentType getState(RuleType type) {
        return rules.getOrDefault(type, type.getState());
    }

    /**
     * 根据 RuleType 查询规则是否开启（非 FALSE 即视为开启）
     */
    public static boolean isEnabled(RuleType type) {
        return getState(type) != SwitchComponentType.FALSE;
    }

    public static boolean isEnabled(ServerPlayer serverPlayer, RuleType ruleType) {
        if (!isEnabled(ruleType)) return false;
        PermissionLevel level = getState(ruleType).getPermissionLevel();
        if (level == null) return false;
        String node = "c." + ruleType.getRegisterName().toLowerCase(Locale.ROOT);
        Identifier permissionNode = VillagerDetails.id(node);
        return serverPlayer.createCommandSourceStack().checkPermission(permissionNode, level);
    }

    public static boolean isEnabled(CommandSourceStack src, RuleType ruleType) {
        if (!isEnabled(ruleType)) return false;
        PermissionLevel level = getState(ruleType).getPermissionLevel();
        if (level == null) return false;
        String node = "c." + ruleType.getRegisterName().toLowerCase(Locale.ROOT);
        return src.checkPermission(VillagerDetails.id(node), level);
    }

    /**
     * 根据 RuleType 设置规则状态
     */
    public static void setState(RuleType type, SwitchComponentType state) {
        rules.put(type, state);
    }

    /**
     * 重置所有规则为默认状态
     */
    public static void resetAll() {
        for (RuleType type : RuleType.values()) {
            rules.put(type, type.getState());
        }
    }

    /**
     * 批量同步规则状态
     * 通常在配置变更时调用，解耦了与 WorldBindingConfig 的直接依赖
     */
    public static void syncRules(Map<RuleType, SwitchComponentType> newRules) {
        if (newRules == null) return;
        for (RuleType type : RuleType.values()) {
            SwitchComponentType value = newRules.get(type);
            if (value != null) {
                rules.put(type, value);
            }
        }
    }

    /**
     * 检查指定类型的绑定是否启用
     * 逻辑修正：只要有一个同类型的规则启用了，就返回 true
     */
    public static boolean isEnableOfListener(ServerPlayer serverPlayer, ListenerType targetRuleType) {
        for (RuleType type : RuleType.values()) {
            if (isEnabled(serverPlayer, type) && type.getListenerType().equals(targetRuleType)) {
                return true;
            }
        }
        return false;
    }
}
