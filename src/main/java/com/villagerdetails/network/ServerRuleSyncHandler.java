package com.villagerdetails.network;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.RuleSwitches;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务器规则与客户端的交互：
 * <ul>
 *     <li>玩家加入 / 客户端主动请求 / 任意规则变化 / 权限变化时，下发<b>这个玩家有权限看到的</b>规则状态</li>
 *     <li>接收客户端配置界面的开关操作（带权限校验），改完再同步一次</li>
 * </ul>
 *
 * <p><b>权限只看 {@code /ec} 总权限</b>（等级取自 {@code setting.ec_command_permission} 这条规则的状态）：
 * 有权限就把规则全下发、能看也能改；没权限就一条都不发 —— 客户端收不到就不会渲染，
 * 「服务器」分类会整个消失。那条设置被改成 {@code true} 时，谁都能看、谁都能改。
 *
 * <p>同步是「内容有变化才发」：每次算出来的清单和上次发过去的一样就直接跳过，
 * 所以每秒一次的权限复查几乎不产生流量。
 *
 * <p>没装本 mod 的客户端收不到 payload，{@link ServerPlayNetworking#canSend} 返回 false；
 * 客户端也据此判断「服务器有没有装本 mod」。
 */
public final class ServerRuleSyncHandler {

    private static final Logger LOGGER = LogManager.getLogger(ServerRuleSyncHandler.class);

    /**
     * 每隔多少 tick 复查一次权限。
     *
     * <p>玩家被 {@code /op} / {@code /deop}、或者权限插件改了权限时没有任何事件可听，
     * 只能自己定期比一次：内容没变 {@link #syncTo} 会直接跳过，所以代价只是几次权限查询。
     */
    private static final int PERMISSION_RECHECK_TICKS = 20;

    /** 每个玩家上一次同步过去的内容，用来判断「有没有变化」 */
    private static final Map<UUID, List<ServerRuleSyncPayload.Entry>> LAST_SYNCED = new ConcurrentHashMap<>();

    private ServerRuleSyncHandler() {
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, _, _) -> {
            // 刚进来的先忘掉旧记录，保证一定会推一份
            LAST_SYNCED.remove(handler.getPlayer().getUUID());
            syncTo(handler.getPlayer());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, _) ->
                LAST_SYNCED.remove(handler.getPlayer().getUUID()));

        ServerPlayNetworking.registerGlobalReceiver(ServerRuleRequestPayload.TYPE,
                (payload, context) -> context.server().execute(() -> syncTo(context.player())));

        ServerPlayNetworking.registerGlobalReceiver(ServerRuleSetPayload.TYPE,
                (payload, context) -> context.server().execute(() -> handleSet(context.player(), payload)));

        // 任意规则状态变化 → 广播（方块规则那条广播由 BlockRuleSyncHandler 负责，这里管界面用的全量同步）
        RuleCache.addListener((type, _, _) -> broadcast());

        // 定期复查权限：被下 op / 权限被改时，客户端那边会跟着刷新（没权限的规则消失）
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PERMISSION_RECHECK_TICKS != 0) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                syncTo(player);
            }
        });
    }

    /**
     * 把这个玩家有权限看到的规则同步给他；内容和上次一样就什么都不发。
     *
     * <p>算出来的清单为空 = 这个玩家一条规则都看不了 → 客户端会把「服务器」分类整个藏掉。
     */
    public static void syncTo(ServerPlayer player) {
        if (player == null) return;
        if (!ServerPlayNetworking.canSend(player, ServerRuleSyncPayload.TYPE)) return;

        List<ServerRuleSyncPayload.Entry> entries = buildEntries(player);
        List<ServerRuleSyncPayload.Entry> previous = LAST_SYNCED.put(player.getUUID(), entries);
        if (entries.equals(previous)) {
            return;
        }
        ServerPlayNetworking.send(player, new ServerRuleSyncPayload(entries));
    }

    public static void broadcast() {
        MinecraftServer server = RuleCache.getServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncTo(player);
        }
    }

    /**
     * 这个玩家能看到的规则清单。
     *
     * <p>权限只看 {@code /ec} 那道<b>总权限</b>（等级取自 {@code setting.ec_command_permission} 这条规则的状态）：
     * <ul>
     *     <li>有权限 → 全部规则都下发，能看也能改；</li>
     *     <li>没权限 → 一条都不发，客户端那边「服务器」分类整个消失；</li>
     * </ul>
     * 所以那条设置被改成 {@code true} 时，谁都能看、谁都能改。
     */
    private static List<ServerRuleSyncPayload.Entry> buildEntries(ServerPlayer player) {
        if (RuleCache.lacksRuleCommandPermission(player)) {
            return List.of();
        }
        List<ServerRuleSyncPayload.Entry> entries = new ArrayList<>(RuleType.values().length);
        for (RuleType type : RuleType.values()) {
            entries.add(new ServerRuleSyncPayload.Entry(type.getRegisterName(), RuleCache.getState(type)));
        }
        return List.copyOf(entries);
    }

    // ==============================================================
    // 处理客户端的状态修改（等价于 /ec <规则> <状态>）
    // ==============================================================

    private static void handleSet(ServerPlayer player, ServerRuleSetPayload payload) {
        if (player == null) return;

        RuleType type = RuleType.getRuleTypeByRegisterName(payload.registerName());
        if (type == null) {
            LOGGER.warn("收到未知规则的状态修改请求：{}", payload.registerName());
            return;
        }

        // 和 /ec 命令同一套权限判定
        if (RuleCache.lacksRuleCommandPermission(player)) {
            player.sendSystemMessage(Component.translatable("villagerdetails.ui.rule.no_permission"));
            LOGGER.warn("玩家 {} 尝试修改规则 {} 但没有权限", player.getName().getString(), type.getRegisterName());
            return;
        }

        String canonical;
        if (payload.reset()) {
            canonical = type.getState();
        } else {
            // 和命令同一套校验：预定义列表必须匹配，自定义列表放行，none 归一化成空
            canonical = RuleSwitches.canonicalize(type, payload.state());
            if (canonical == null) {
                player.sendSystemMessage(Component.translatable(
                        "command.entity_binder.error.invalid_state", payload.state()));
                LOGGER.warn("玩家 {} 给规则 {} 传了非法状态：{}",
                        player.getName().getString(), type.getRegisterName(), payload.state());
                return;
            }
        }

        RuleCache.setState(type, canonical);

        // setState 会触发监听器广播，这里再补一次，保证发起者立刻看到最新状态
        syncTo(player);
    }
}
