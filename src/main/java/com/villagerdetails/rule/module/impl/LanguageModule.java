package com.villagerdetails.rule.module.impl;

import com.villagerdetails.lang.ServerTranslations;
import com.villagerdetails.rule.RuleCallbacks;
import com.villagerdetails.rule.module.RuleModule;
import com.villagerdetails.rule.type.RuleType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class LanguageModule implements RuleModule {

    private static final Logger log = LogManager.getLogger("LanguageModule");

    @Override
    public void registerCallbacks() {
        RuleCallbacks.register(RuleType.SETTING_LANGUAGE, (oldValue, newValue) -> {
            log.info("[Language] {} → {}", oldValue, newValue);
            ServerTranslations.switchTo(newValue);
        });
    }
}