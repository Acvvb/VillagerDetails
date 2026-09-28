package com.villagerdetails.rule;

import com.villagerdetails.rule.type.RuleType;

import java.util.EnumMap;
import java.util.Map;

/**
 * 规则回调注册中心。
 * 每条规则可以注册一个"变更时执行"的处理器。
 */
public final class RuleCallbacks {

    private RuleCallbacks() {}

    @FunctionalInterface
    public interface Handler {
        void onChanged(String oldValue, String newValue);
    }

    private static final Map<RuleType, Handler> HANDLERS = new EnumMap<>(RuleType.class);

    /** 注册某条规则的回调 */
    public static void register(RuleType type, Handler handler) {
        if (type != null && handler != null) {
            HANDLERS.put(type, handler);
        }
    }

    /** 取消某条规则的回调 */
    public static void unregister(RuleType type) {
        HANDLERS.remove(type);
    }

    /** 内部调用：触发某条规则的回调 */
    public static void fire(RuleType type, String oldValue, String newValue) {
        Handler h = HANDLERS.get(type);
        if (h == null) return;
        try {
            h.onChanged(oldValue, newValue);
        } catch (Exception e) {
            System.err.println("[RuleCallbacks] " + type.getRegisterName() + " 回调失败: " + e);
        }
    }
}