package com.villagerdetails.config;

import com.villagerdetails.rule.registry.RuleDataRegistry;

public class ConfigManager {

    public static void registry() {
        //注册配置
        ConfigRegistry.registerStartedHook();

        //规则配置同步客户端注册，必须在规则注册完成之后注册
        RuleDataRegistry.verifyConsistency();

        // 配置侧自检：一实例一规则 / 一名一配置 / 规则配置不许再注册进 ConfigRegistry
        // （放在规则注册之后，和上面的 RuleDataRegistry 自检同一批跑）
        ConfigRegistry.verifyReloadableConfigs();
    }


}
