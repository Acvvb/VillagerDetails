package com.villagerdetails.command;

import net.minecraft.server.permissions.PermissionLevel;

import java.util.List;

/**
 * 规则开关状态（单一来源）。
 * <p>
 * 合并了原来的 QuickSwitches（状态字符串 + 权限等级映射）与 SwitchComponentType（状态显示名 + 按钮渲染）：
 * 每个枚举常量同时承载「状态字符串 commandStr」「显示名 displayName」「权限等级 permissionLevel」。
 */
public enum SwitchComponentType {

    FALSE("关闭", "false", null),
    TRUE("开启", "true", PermissionLevel.ALL),
    OPS("ops", "ops", PermissionLevel.MODERATORS),
    MASTER("master", "master", PermissionLevel.GAMEMASTERS),
    ADMIN("admin", "admin", PermissionLevel.ADMINS),
    OWNER("owner", "owner", PermissionLevel.OWNERS)
    ;

    public static final String INFO = "点击切换规则状态\n当前状态: %s";

    /** 不含关闭态的权限开关（用于 /ec 权限管理这类“必须有一个等级”的设置） */
    public static final List<SwitchComponentType> SETTING_PERMISSIONS = List.of(TRUE, OPS, MASTER, ADMIN, OWNER);

    /** 完整开关（关闭 + 各权限等级） */
    public static final List<SwitchComponentType> PERMISSIONS = List.of(FALSE, TRUE, OPS, MASTER, ADMIN, OWNER);

    /** 简单开关（仅关闭/开启） */
    public static final List<SwitchComponentType> ON_OFF = List.of(FALSE, TRUE);

    private final String displayName;
    private final String commandStr;
    private final PermissionLevel permissionLevel;

    SwitchComponentType(String displayName, String commandStr, PermissionLevel permissionLevel) {
        this.displayName = displayName;
        this.commandStr = commandStr;
        this.permissionLevel = permissionLevel;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCommandStr() {
        return commandStr;
    }

    public PermissionLevel getPermissionLevel() {
        return permissionLevel;
    }

    /**
     * 根据命令值（state 字符串）查找对应的开关类型，找不到返回 null。
     */
    public static SwitchComponentType getByCommandStr(String commandStr) {
        for (SwitchComponentType type : values()) {
            if (type.commandStr.equalsIgnoreCase(commandStr)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 根据命令值（state 字符串）获取显示名，找不到时返回原始字符串。
     */
    public static String displayNameOf(String commandStr) {
        SwitchComponentType type = getByCommandStr(commandStr);
        return type != null ? type.getDisplayName() : commandStr;
    }

}
