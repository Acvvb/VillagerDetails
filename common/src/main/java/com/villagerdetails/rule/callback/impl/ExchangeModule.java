package com.villagerdetails.rule.callback.impl;

import com.villagerdetails.rule.RuleChangeContext;
import com.villagerdetails.rule.RuleChangeService;
import com.villagerdetails.rule.callback.RuleCallbacks;
import com.villagerdetails.rule.callback.RuleModule;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ExchangeModule implements RuleModule {

    private static final Logger log = LogManager.getLogger("ExchangeModule");

    @Override
    public void registerCallbacks() {
        for (RuleType type : RuleType.values()) {
            RuleCallbacks.register(type, (oldValue, newValue) -> {
                // 1. 启动/重载阶段静默
                if (RuleChangeService.isReloading) {
                    return;
                }

                // 2. 仅状态变化时打印
                if (oldValue == null || !oldValue.equals(newValue)) {
                    // 3. 获取操作者
                    CommandSourceStack source = RuleChangeContext.getSource();
                    String executorName = "Console";
                    if (source != null) {
                        if (source.getEntity() != null) {
                            executorName = source.getEntity().getName().getString();
                        } else {
                            executorName = source.getTextName(); // 比如 "Server"
                        }
                    }

                    log.info("[{}] {} → {} (by {})", type.getRegisterName(), oldValue, newValue, executorName);
                }
            });
        }
    }
}