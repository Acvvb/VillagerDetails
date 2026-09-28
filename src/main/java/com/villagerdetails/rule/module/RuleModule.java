package com.villagerdetails.rule.module;

/**
 * 规则回调模块。
 * 每个功能模块实现它，负责注册自己关心的规则回调。
 */
public interface RuleModule {

    /** 注册本模块的回调 */
    void registerCallbacks();

    /** 模块名（日志用） */
    default String name() {
        return getClass().getSimpleName();
    }
}