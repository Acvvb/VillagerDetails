package com.villagerdetails.cache;

import com.villagerdetails.VillagerDetails;
import com.villagerdetails.command.SwitchComponentType;
import com.villagerdetails.config.impl.RuleConfig;
import com.villagerdetails.event.type.ListenerType;
import com.villagerdetails.rule.callback.RuleCallbacks;
import com.villagerdetails.rule.type.RuleConfigKind;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.villagerdetails.command.CommandConstants.PERM_BASE;

public class RuleCache {

    private static final Map<RuleType, String> rules = new EnumMap<>(RuleType.class);

    /**
     * 通用监听器列表（线程安全）
     */
    private static final List<RuleChangeListener> listeners = new CopyOnWriteArrayList<>();
    private static final Logger log = LoggerFactory.getLogger(RuleCache.class);

    /**
     * 当前运行的服务器引用——用于持久化
     */
    private static volatile MinecraftServer server;

    /**
     * 「逐条配置类」规则（不可变）—— 只有它们的配置内容需要下发给客户端。
     *
     * <p>由下面的 static 块在遍历 {@link RuleType#values()} 时顺手筛出来，
     * 「要不要下发」只看 {@link RuleConfigKind#needsSync()} 一处，
     * 同步那边（{@code BlockRuleSyncEvents}）不再手写规则列表。
     */
    private static final List<RuleType> CONFIG_RULE_TYPES;

    static {
        List<RuleType> configTypes = new ArrayList<>();
        for (RuleType type : RuleType.values()) {
            setState(type, type.getState());
            // 顺手收集：不额外多跑一轮 values()
            if (type.getConfigKind().needsSync()) {
                configTypes.add(type);
            }
        }
        // List.copyOf = 不可变快照；这里必须放 static 块里赋值，
        // 写成字段初始化表达式会在 RuleType 未被初始化时就读它的静态字段
        CONFIG_RULE_TYPES = List.copyOf(configTypes);

        // 打一行便于排查「客户端列表页空白 / 规则开关不同步」这类问题
        log.info("[RuleCache] 逐条配置类规则 {} 条：{}", CONFIG_RULE_TYPES.size(),
                CONFIG_RULE_TYPES.stream().map(RuleType::getRegisterName).toList());
    }

    /**
     * 需要同步配置内容的规则（不可变列表）
     */
    public static List<RuleType> configRuleTypes() {
        return CONFIG_RULE_TYPES;
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

    /**
     * 服务器停机时调一次：把静态引用放掉。
     *
     * <p>不放的话这个静态字段会一直攥着已经废弃的 {@link MinecraftServer}，
     * 单人反复换存档时等于把整个旧世界挂在类加载器上。
     */
    public static void clearServer() {
        server = null;
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

    /**
     * 规则自己的权限节点：{@code villagerdetails:c.<注册名小写>}
     */
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
            return true;
        }
        PermissionLevel level = SwitchComponentType.permissionLevelOf(getState(RuleType.SETTING_EC_COMMAND_PERMISSION));
        return !serverPlayer.createCommandSourceStack().checkPermission(
                PERM_BASE, level != null ? level : PermissionLevel.OWNERS);
    }

    /**
     * 改一条规则的状态：写内存 + 落盘 + 触发回调与监听器。
     *
     * <p><b>别直接调这里</b> —— 对外的「改规则」入口是
     * {@link com.villagerdetails.rule.RuleChangeService}（它负责先做合法性校验）；
     * 只有两处例外：它自己，以及 {@link #syncRules}（配置回灌）。
     */
    public static void setState(RuleType type, String state) {
        if (type == null || state == null) return;
        String oldValue = getState(type);
        if (state.equals(oldValue)) return;
        rules.put(type, state);
        persist(type, state);
        fireCallbacks(type, oldValue, state);
    }

    /**
     * 配置回灌专用：把磁盘上读到的状态批量写进内存（走 {@link #setState}，所以回调照常触发）。
     */
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
            log.error("[RuleCache] 持久化失败 {}: {}", type.getRegisterName(), e);
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
                log.error("[RuleCache] 监听器执行失败: {}", String.valueOf(e));
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