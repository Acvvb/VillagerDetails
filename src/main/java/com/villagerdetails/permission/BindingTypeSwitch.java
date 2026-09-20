package com.villagerdetails.permission;

import com.villagerdetails.config.WorldBindingConfig;
import com.villagerdetails.event.type.BindingType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumMap;
import java.util.Map;

public class BindingTypeSwitch {

    private static final Logger LOGGER = LogManager.getLogger(BindingTypeSwitch.class);

    private static final Map<BindingType, Boolean> SWITCH_MAP = new EnumMap<>(BindingType.class);

    // 当前世界的配置实例（由事件处理器设置）
    private static WorldBindingConfig currentConfig;

    static {
        for (BindingType type : BindingType.values()) {
            SWITCH_MAP.put(type, false);
        }
    }

    /**
     * 设置当前世界配置实例（在世界加载事件中调用）
     */
    public static void setWorldConfig(WorldBindingConfig config) {
        currentConfig = config;
    }

    /**
     * 检查指定绑定类型是否开启
     */
    public static boolean isEnabled(BindingType type) {
        return SWITCH_MAP.getOrDefault(type, false);
    }

    /**
     * 设置指定绑定类型的开关状态，同时保存到存档
     */
    public static void setEnabled(BindingType type, boolean enabled) {
        SWITCH_MAP.put(type, enabled);
        LOGGER.info("绑定类型 [{}] 已{}", type.getRequiredToolName(), enabled ? "开启" : "关闭");

        // 同步保存到 WorldBindingConfig
        if (currentConfig != null) {
            currentConfig.setBindingState(type.getName(), enabled);
        }
    }

    /**
     * 一次性设置所有绑定类型的开关状态
     */
    public static void setAllEnabled(boolean enabled) {
        for (BindingType type : BindingType.values()) {
            SWITCH_MAP.put(type, enabled);
            if (currentConfig != null) {
                currentConfig.setBindingState(type.getName(), enabled);
            }
        }
        LOGGER.info("所有绑定类型已{}", enabled ? "开启" : "关闭");
    }

    /**
     * 重置所有绑定类型为默认关闭状态
     */
    public static void resetAll() {
        for (BindingType type : BindingType.values()) {
            SWITCH_MAP.put(type, false);
            if (currentConfig != null) {
                currentConfig.setBindingState(type.getName(), false);
            }
        }
        LOGGER.info("所有绑定类型已重置为默认关闭状态");
    }

    /**
     * 获取所有开关状态的快照
     */
    public static Map<BindingType, Boolean> getAllStates() {
        return new EnumMap<>(SWITCH_MAP);
    }
}