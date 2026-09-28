package com.villagerdetails.cache;

import com.villagerdetails.VillagerDetails;
import com.villagerdetails.command.SwitchComponentType;
import com.villagerdetails.config.WorldBindingConfig;
import com.villagerdetails.event.type.ListenerType;
import com.villagerdetails.rule.RuleCallbacks;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class RuleCache {

    private static final Map<RuleType, String> rules = new EnumMap<>(RuleType.class);

    /** 通用监听器列表（线程安全） */
    private static final List<RuleChangeListener> listeners = new CopyOnWriteArrayList<>();

    /** 当前运行的服务器引用——用于持久化 */
    private static volatile MinecraftServer server;

    static {
        for (RuleType type : RuleType.values()) {
            rules.put(type, type.getState());
        }
    }

    /** 服务器启动时调一次 */
    public static void setServer(MinecraftServer s) {
        server = s;
    }

    @FunctionalInterface
    public interface RuleChangeListener {
        void onRuleChanged(RuleType type, String oldValue, String newValue);
    }

    public static void addListener(RuleChangeListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public static String getState(RuleType type) {
        return rules.getOrDefault(type, type.getState());
    }

    public static Integer getStateToInteger(RuleType type) {
        String str = rules.getOrDefault(type, type.getState());
        if (str != null) {
            String trimmed = str.trim();
            if (!trimmed.isEmpty()) {
                try {
                    return Integer.parseInt(trimmed);
                } catch (NumberFormatException e) {
                    setState(type, type.getState());
                    return Integer.parseInt(type.getState());
                }
            }
        }
        setState(type, type.getState());
        return Integer.parseInt(type.getState());
    }

    public static boolean isEnabled(RuleType type) {
        return !SwitchComponentType.FALSE_STR.equalsIgnoreCase(getState(type));
    }

    public static boolean isEnabled(ServerPlayer serverPlayer, RuleType ruleType) {
        if (!isEnabled(ruleType)) return false;
        PermissionLevel level = SwitchComponentType.permissionLevelOf(getState(ruleType));
        if (level == null) return false;
        String node = "c." + ruleType.getRegisterName().toLowerCase(Locale.ROOT);
        Identifier permissionNode = VillagerDetails.id(node);
        return serverPlayer.createCommandSourceStack().checkPermission(permissionNode, level);
    }

    public static boolean isEnabled(CommandSourceStack src, RuleType ruleType) {
        if (!isEnabled(ruleType)) return false;
        PermissionLevel level = SwitchComponentType.permissionLevelOf(getState(ruleType));
        if (level == null) return false;
        String node = "c." + ruleType.getRegisterName().toLowerCase(Locale.ROOT);
        return src.checkPermission(VillagerDetails.id(node), level);
    }

    // ==============================================================
    // 修改（值变化 → 持久化 + 双回调）
    // ==============================================================

    public static void setState(RuleType type, String state) {
        if (type == null || state == null) return;

        String oldValue = rules.get(type);
        if (state.equals(oldValue)) return;

        rules.put(type, state);

        // ★ 1. 持久化到配置文件
        persist(type, state);

        // 2. 触发回调
        fireCallbacks(type, oldValue, state);
    }

    public static void resetAll() {
        for (RuleType type : RuleType.values()) {
            String oldValue = rules.get(type);
            String newValue = type.getState();
            if (!newValue.equals(oldValue)) {
                rules.put(type, newValue);
                persist(type, newValue);          // ★
                fireCallbacks(type, oldValue, newValue);
            }
        }
    }

    public static void syncRules(Map<RuleType, String> newRules) {
        if (newRules == null) return;

        for (RuleType type : RuleType.values()) {
            String newValue = newRules.get(type);
            if (newValue == null) continue;

            String oldValue = rules.get(type);
            if (!newValue.equals(oldValue)) {
                rules.put(type, newValue);
                persist(type, newValue);          // ★
                fireCallbacks(type, oldValue, newValue);
            }
        }
    }

    // ==============================================================
    // 持久化
    // ==============================================================

    private static void persist(RuleType type, String state) {
        MinecraftServer s = server;
        if (s == null) return;

        try {
            WorldBindingConfig cfg = WorldBindingConfig.getOrCreate(s);
            cfg.setBindingState(type.getRegisterName(), state);
            // 如果 WorldBindingConfig 有显式 save 方法，在这里调：
            // cfg.save(s);
        } catch (Exception e) {
            System.err.println("[RuleCache] 持久化失败 " + type.getRegisterName() + ": " + e);
        }
    }

    // ==============================================================
    // 回调
    // ==============================================================

    private static void fireCallbacks(RuleType type, String oldValue, String newValue) {
        RuleCallbacks.fire(type, oldValue, newValue);
        notifyListeners(type, oldValue, newValue);
    }

    private static void notifyListeners(RuleType type, String oldValue, String newValue) {
        if (listeners.isEmpty()) return;
        for (RuleChangeListener l : listeners) {
            try {
                l.onRuleChanged(type, oldValue, newValue);
            } catch (Exception e) {
                System.err.println("[RuleCache] 监听器执行失败: " + e);
            }
        }
    }

    // ==============================================================
    // 原有逻辑
    // ==============================================================

    public static boolean isEnableOfListener(ServerPlayer serverPlayer, ListenerType targetRuleType) {
        for (RuleType type : RuleType.values()) {
            if (isEnabled(serverPlayer, type) && type.getListenerType().equals(targetRuleType)) {
                return true;
            }
        }
        return false;
    }
}