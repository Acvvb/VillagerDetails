package com.villagerdetails.rule;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.module.RuleModule;
import com.villagerdetails.rule.module.impl.ExchangeModule;
import com.villagerdetails.rule.module.impl.LanguageModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * 所有 RuleModule 的注册中心。
 * <p>加新模块：在 {@link #MODULES} 里 new 一个即可。
 */
public final class RuleModuleRegistry {

    private RuleModuleRegistry() {}

    private static final Logger log = LogManager.getLogger("RuleModuleRegistry");

    /** ★ 所有模块在这里登记 */
    private static final List<RuleModule> MODULES = List.of(
            new LanguageModule(),
            new ExchangeModule()
    );

    /** 主类调一次 */
    public static void initAll() {
        log.info("[RuleModuleRegistry] initAll 开始，{} 个模块", MODULES.size());

        for (RuleModule module : MODULES) {
            log.info("[RuleModuleRegistry] 注册模块：{}", module.name());
            module.registerCallbacks();
        }

        RuleCache.addListener((type, oldValue, newValue) ->
                log.info("[RuleCache] {} {} → {}", type.getRegisterName(), oldValue, newValue));

        log.info("[RuleModuleRegistry] initAll 完成");
    }
}