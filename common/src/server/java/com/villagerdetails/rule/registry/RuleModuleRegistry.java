package com.villagerdetails.rule.registry;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.callback.RuleModule;
import com.villagerdetails.rule.callback.impl.ExchangeModule;
import com.villagerdetails.rule.callback.impl.LanguageModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 所有 RuleModule 的注册中心。
 * <p>加新模块：在 {@link #MODULES} 里 new 一个即可。
 */
public final class RuleModuleRegistry {

    private RuleModuleRegistry() {
    }

    private static final Logger log = LogManager.getLogger(RuleModuleRegistry.class);

    /**
     * 是否已经注册过。
     *
     * <p>{@code VillagerDetailsServer} 的 {@code main} 与 {@code server} 两个入口点都会走 {@code init()}，
     * 所以这里必须幂等：否则每个模块会注册两遍（在回调列表语义下 = 审计日志 / 语言切换各跑两次）。
     */
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    /**
     * ★ 所有模块在这里登记
     */
    private static final List<RuleModule> MODULES = List.of(
            new LanguageModule(),
            new ExchangeModule()
    );

    /**
     * 主类调一次（重复调用是空操作）
     */
    public static void initAll() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            log.warn("检测到 RuleModuleRegistry.initAll() 被重复调用，本次已忽略");
            return;
        }

        log.info("initAll 开始，{} 个模块", MODULES.size());

        for (RuleModule module : MODULES) {
            log.info("注册模块：{}", module.name());
            module.registerCallbacks();
        }

        RuleCache.addListener((type, oldValue, newValue) ->
                log.debug(" {} {} → {}", type.getRegisterName(), oldValue, newValue));

        log.info("initAll 完成");
    }
}
