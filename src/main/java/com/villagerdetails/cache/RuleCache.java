package com.villagerdetails.cache;

import com.villagerdetails.VillagerDetails;
import com.villagerdetails.command.SwitchComponentType;
import com.villagerdetails.config.impl.RuleConfig;
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

    /**
     * 通用监听器列表（线程安全）
     */
    private static final List<RuleChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 当前运行的服务器引用——用于持久化
     */
    private static volatile MinecraftServer server;

    static {
        for (RuleType type : RuleType.values()) {
            setState(type, type.getState());
        }
    }

    /**
     * 服务器启动时调一次
     */
    public static void setServer(MinecraftServer s) {
        server = s;
    }

    public static MinecraftServer getServer() {
        return server;
    }

    @FunctionalInterface
    public interface RuleChangeListener {
        void onRuleChanged(RuleType type, String oldValue, String newValue);
    }

    public static void addListener(RuleChangeListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public static String getState(RuleType type) {
        // 允许 value 为空：none 已在 setState 中规范化为空字符串，这里直接返回原始值
        return rules.getOrDefault(type, type.getState());
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

    public static void setState(RuleType type, String state) {
        if (type == null || state == null) return;
        // 占位符 none 统一规范化为空字符串，允许缓存中保存空值
        if (SwitchComponentType.NONE.equalsIgnoreCase(state)) state = "";
        String oldValue = getState(type);
        if (state.equals(oldValue)) return;
        rules.put(type, state);
        persist(type, state);
        fireCallbacks(type, oldValue, state);
    }

    public static void resetAll() {
        for (RuleType type : RuleType.values()) {
            setState(type, type.getState());
        }
    }

    public static void syncRules(Map<RuleType, String> newRules) {
        if (newRules == null) return;

        for (RuleType type : RuleType.values()) {
            String newValue = newRules.get(type);
            if (newValue == null) continue;
            setState(type, newValue);
        }
    }

    private static void persist(RuleType type, String state) {
        MinecraftServer s = server;
        if (s == null) return;

        try {
            RuleConfig cfg = RuleConfig.getOrCreate(s);
            cfg.setBindingState(type.getRegisterName(), state);
            cfg.save();
        } catch (Exception e) {
            System.err.println("[RuleCache] 持久化失败 " + type.getRegisterName() + ": " + e);
        }
    }

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

    public static boolean isEnableOfListener(ServerPlayer serverPlayer, ListenerType targetRuleType) {
        for (RuleType type : RuleType.values()) {
            if (isEnabled(serverPlayer, type)) {
                for (ListenerType listenerType : type.getListenerType()) {
                    if (listenerType.equals(targetRuleType)) return true;
                }
            }
        }
        return false;
    }
}