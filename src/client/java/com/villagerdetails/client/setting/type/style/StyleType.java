package com.villagerdetails.client.setting.type.style;

import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.client.setting.type.server.BindingServer;

/**
 * 配置行的渲染样式。独立于分类存在 —— 分类只决定「在哪一栏」，
 * 样式由每个 {@link ConfigEntryType} 通过 {@link BindingServer} 自己声明。
 */
public enum StyleType {
    /**
     * 重置按钮 + 规则名 + 快捷键绑定 + 是/否
     */
    TOGGLE,
    /**
     * 齿轮 + 颜色预览块 + 颜色按钮
     */
    COLOR,
    /**
     * 齿轮 + 滑条（齿轮可切成立即输入数值的文本框）
     */
    SLIDER,
    /**
     * 占位：只有一个按钮
     */
    LIST
}