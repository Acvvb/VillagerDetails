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

import static com.villagerdetails.command.CommandConstants.PERM_BASE;

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
        return serverPlayer.createCommandSourceStack().checkPermission(permissionNode(ruleType), level);
    }

    public static boolean isEnabled(CommandSourceStack src, RuleType ruleType) {
        if (!isEnabled(ruleType)) return false;
        PermissionLevel level = SwitchComponentType.permissionLevelOf(getState(ruleType));
        if (level == null) return false;
        return src.checkPermission(permissionNode(ruleType), level);
    }

    /** 规则自己的权限节点：{@code villagerdetails:c.<注册名小写>} */
    private static Identifier permissionNode(RuleType ruleType) {
        return VillagerDetails.id("c." + ruleType.getRegisterName().toLowerCase(Locale.ROOT));
    }

    /**
     * 这个玩家是不是<b>没有</b> {@code /ec} 那道总权限 —— 规则列表能不能看、规则能不能改，全看它。
     *
     * <p>等级直接取 {@link RuleType#SETTING_EC_COMMAND_PERMISSION} 这条规则自己的状态：
     * <ul>
     *     <li>{@code true} → {@link PermissionLevel#ALL}：<b>谁都能看、谁都能改</b>（这时的答案永远是 false）；</li>
     *     <li>{@code ops / master / admin / owner} → 对应等级及以上才算有权限；</li>
     *     <li>状态读不出来（自定义 / 空）→ 退回 {@code owner}。</li>
     * </ul>
     *
     * <p>和命令、界面改规则用的是同一套判定，所以「能不能看」和「能不能改」永远一致。
     *
     * <p>故意用否定命名（而不是 {@code hasRuleCommandPermission}）：调用方清一色是
     * 「没权限就拒绝 / 就不下发」，写成 {@code if (lacksRuleCommandPermission(p))} 更顺，
     * 也免得到处写 {@code !hasXxx(...)}（IDEA 会为此报「布尔方法的调用始终被取反」）。
     */
    public static boolean lacksRuleCommandPermission(ServerPlayer serverPlayer) {
        if (serverPlayer == null) {
            // 没有玩家 = 没权限（和原来 hasXxx 返回 false 是同一个判断，只是取了反）
            return true;
        }
        PermissionLevel level = SwitchComponentType.permissionLevelOf(getState(RuleType.SETTING_EC_COMMAND_PERMISSION));
        return !serverPlayer.createCommandSourceStack().checkPermission(
                PERM_BASE, level != null ? level : PermissionLevel.OWNERS);
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

    /**
     * 客户端镜像专用：只把状态写进本地缓存，不落盘、不触发规则回调
     * （回调是服务端行为，客户端只是拿它显示界面）。
     */
    public static void applyClientMirror(RuleType type, String state) {
        if (type == null || state == null) return;
        if (SwitchComponentType.NONE.equalsIgnoreCase(state)) state = "";
        rules.put(type, state);
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