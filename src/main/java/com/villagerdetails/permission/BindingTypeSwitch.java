package com.villagerdetails.permission;

import com.villagerdetails.config.WorldBindingConfig;
import com.villagerdetails.event.type.BindingType;

import java.util.EnumMap;
import java.util.Map;

/**
 * 绑定类型开关管理器
 */
public class BindingTypeSwitch {

    private static final Map<BindingType, Boolean> switches = new EnumMap<>(BindingType.class);

    static {
        // 默认全部开启
        for (BindingType type : BindingType.values()) {
            switches.put(type, true);
        }
    }

    /**
     * 根据 BindingType 查询开关状态
     */
    public static boolean isEnabled(BindingType type) {
        return switches.getOrDefault(type, false);
    }

    /**
     * 根据 bindingTypeId 查询开关状态
     * 供权限层调用，避免直接依赖 BindingType
     */
    public static boolean isEnabledByTypeId(int bindingTypeId) {
        for (BindingType type : BindingType.values()) {
            if (type.getId() == bindingTypeId) {
                return isEnabled(type);
            }
        }
        return false;
    }

    /**
     * 根据 BindingType 设置开关状态
     */
    public static void setEnabled(BindingType type, boolean enabled) {
        switches.put(type, enabled);
    }

    /**
     * 根据 bindingTypeId 设置开关状态
     */
    public static void setEnabledByTypeId(int bindingTypeId, boolean b) {
        for (BindingType type : BindingType.values()) {
            if (type.getId() == bindingTypeId) {
                setEnabled(type, b);
                return;
            }
        }
    }

    /**
     * 重置所有开关为默认状态（全部开启）
     */
    public static void resetAll() {
        for (BindingType type : BindingType.values()) {
            switches.put(type, true);
        }
    }

    /**
     * 从世界配置批量同步开关状态
     * 通常在世界加载或配置变更时调用
     */
    public static void setWorldConfig(WorldBindingConfig config) {
        if (config == null) return;
        for (BindingType type : BindingType.values()) {
            boolean enabled = config.isBindingEnabled(type.getId());
            switches.put(type, enabled);
        }
    }
}