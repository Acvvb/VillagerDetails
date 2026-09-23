package com.villagerdetails.cache;

import com.villagerdetails.event.type.ListenerType;
import com.villagerdetails.rule.type.RuleType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumMap;
import java.util.Map;

/**
 * 规则管理器
 * 管理模组内各类可配置的布尔型规则开关（运行时内存层）
 */
public class RuleCache {

    private static final Map<RuleType, Boolean> rules = new EnumMap<>(RuleType.class);
    private static final Logger log = LogManager.getLogger(RuleCache.class);

    static {
        // 默认使用枚举中定义的 state 值
        for (RuleType type : RuleType.values()) {
            rules.put(type, type.isState());
        }
    }

    /**
     * 根据 RuleType 查询规则状态
     */
    public static boolean isEnabled(RuleType type) {
        return rules.getOrDefault(type, false);
    }

    /**
     * 根据 ruleId 查询规则状态
     */
    public static boolean isEnabledById(int ruleId) {
        for (RuleType type : RuleType.values()) {
            if (type.getId() == ruleId) {
                return isEnabled(type);
            }
        }
        return false;
    }

    /**
     * 根据 RuleType 设置规则状态
     */
    public static void setEnabled(RuleType type, boolean enabled) {
        rules.put(type, enabled);
    }

    /**
     * 根据 ruleId 设置规则状态
     */
    public static void setEnabled(int ruleId, boolean enabled) {
        for (RuleType type : RuleType.values()) {
            if (type.getId() == ruleId) {
                setEnabled(type, enabled);
                return;
            }
        }
    }

    /**
     * 重置所有规则为默认状态
     */
    public static void resetAll() {
        for (RuleType type : RuleType.values()) {
            rules.put(type, type.isState());
        }
    }

    /**
     * 批量同步规则状态
     * 通常在配置变更时调用，解耦了与 WorldBindingConfig 的直接依赖
     */
    public static void syncRules(Map<RuleType, Boolean> newRules) {
        if (newRules == null) return;
        for (RuleType type : RuleType.values()) {
            Boolean value = newRules.get(type);
            if (value != null) {
                rules.put(type, value);
            }
        }
    }

    /**
     * 检查指定类型的绑定是否启用
     * 逻辑修正：只要有一个同类型的规则启用了，就返回 true
     */
    public static boolean isEnableOfListener(ListenerType targetRuleType) {
        for (RuleType type : RuleType.values()) {
            if (isEnabled(type) && type.getListenerType().equals(targetRuleType)) {
                return true;
            }
        }
        return false;
    }
}