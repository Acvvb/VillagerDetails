package com.villagerdetails.rule.callback;

import com.villagerdetails.rule.type.RuleType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumMap;
import java.util.Map;

/**
 * 规则回调注册中心。
 * 每条规则可以注册一个"变更时执行"的处理器。
 */
public final class RuleCallbacks {

    private static final Logger log = LogManager.getLogger(RuleCallbacks.class);

    private RuleCallbacks() {
    }

    @FunctionalInterface
    public interface Handler {
        void onChanged(String oldValue, String newValue);
    }

    private static final Map<RuleType, Handler> HANDLERS = new EnumMap<>(RuleType.class);

    /**
     * 注册某条规则的回调
     */
    public static void register(RuleType type, Handler handler) {
        if (type != null && handler != null) {
            HANDLERS.put(type, handler);
        }
    }

    /**
     * 内部调用：触发某条规则的回调
     */
    public static void fire(RuleType type, String oldValue, String newValue) {
        Handler h = HANDLERS.get(type);
        if (h == null) return;
        try {
            h.onChanged(oldValue, newValue);
        } catch (Exception e) {
            // 异常对象作为最后一个参数交给日志：回调里的 NPE 要能看到堆栈才定位得了
            log.error("[RuleCallbacks] {} 回调失败", type.getRegisterName(), e);
        }
    }
}