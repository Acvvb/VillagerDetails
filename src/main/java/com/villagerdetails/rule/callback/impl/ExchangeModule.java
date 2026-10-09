package com.villagerdetails.rule.callback.impl;

import com.villagerdetails.rule.callback.RuleCallbacks;
import com.villagerdetails.rule.callback.RuleModule;
import com.villagerdetails.rule.type.RuleType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ExchangeModule implements RuleModule {

    private static final Logger log = LogManager.getLogger("ExchangeModule");

    @Override
    public void registerCallbacks() {
        RuleCallbacks.register(RuleType.VILLAGER_TOOLSMITH_EXCHANGE, (oldValue, newValue) -> {
            log.info("[ToolsmithExchange] {} → {}", oldValue, newValue);
            // 你想做的其它处理
        });
    }
}