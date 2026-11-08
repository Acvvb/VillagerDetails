package com.villagerdetails.rule.callback;

import com.villagerdetails.rule.type.RuleType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 规则回调注册中心。
 *
 * <p>每条规则可以注册<b>多个</b>"变更时执行"的处理器（列表语义）。
 *
 * <p><b>为什么不是"每条规则一个槽位"</b>：早期实现是 {@code EnumMap<RuleType, Handler>} + {@code put}，
 * 同一条规则第二次注册会<b>静默覆盖</b>第一次 —— {@code ExchangeModule} 给每条规则都登记了审计日志，
 * 而它排在 {@code LanguageModule} 之后注册，于是语言切换回调被顶掉，改 {@code Language} 不再生效。
 * 改成列表之后两类回调可以并存。
 *
 * <p>线程约定：注册只发生在初始化阶段（主线程），触发发生在规则变更时（命令 / 收包 / 重载）。
 * 用 {@link CopyOnWriteArrayList} 保证"触发过程中有人注册"也不会抛 {@code ConcurrentModificationException}。
 */
public final class RuleCallbacks {

    private static final Logger log = LogManager.getLogger(RuleCallbacks.class);

    private RuleCallbacks() {
    }

    @FunctionalInterface
    public interface Handler {
        void onChanged(String oldValue, String newValue);
    }

    /**
     * 规则 → 回调列表（只在初始化阶段写，之后基本只读）。
     */
    private static final Map<RuleType, List<Handler>> HANDLERS = new EnumMap<>(RuleType.class);

    /**
     * 注册某条规则的回调（可注册多个，互不覆盖）。
     *
     * <p>同一个 handler 实例重复注册会被忽略：lambda 的 {@code equals} 是引用相等，
     * 能挡住"同一个常量被注册两遍"这类重复。
     */
    public static void register(RuleType type, Handler handler) {
        if (type == null || handler == null) {
            return;
        }
        List<Handler> handlers = HANDLERS.computeIfAbsent(type, _ -> new CopyOnWriteArrayList<>());
        if (handlers.contains(handler)) {
            log.debug("[RuleCallbacks] {} 的同一个回调被重复注册，已忽略", type.getRegisterName());
            return;
        }
        handlers.add(handler);
    }

    /**
     * 内部调用：触发某条规则的<b>全部</b>回调。
     *
     * <p>单个回调抛异常只记日志，不影响同一规则上的其它回调（以前只有一个槽位，现在是一组）。
     */
    public static void fire(RuleType type, String oldValue, String newValue) {
        List<Handler> handlers = HANDLERS.get(type);
        if (handlers == null || handlers.isEmpty()) {
            return;
        }
        for (Handler handler : handlers) {
            try {
                handler.onChanged(oldValue, newValue);
            } catch (Exception e) {
                // 异常对象作为最后一个参数交给日志：回调里的 NPE 要能看到堆栈才定位得了
                log.error("[RuleCallbacks] {} 的某个回调失败", type.getRegisterName(), e);
            }
        }
    }
}
