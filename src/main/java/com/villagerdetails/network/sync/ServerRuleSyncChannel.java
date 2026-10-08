package com.villagerdetails.network.sync;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.network.channel.SyncChannel;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则状态同步：服务端 → 客户端。
 *
 * <p>玩家加入 / 客户端主动请求 / 任意规则变化 / 权限变化时，下发<b>这个玩家有权限看到的</b>规则状态；
 * 内容和上次一样就不发（所以每秒一次的权限复查几乎不产生流量）。
 *
 * <p>包 id：{@code villagerdetails:server_rule_sync}
 */
public class ServerRuleSyncChannel extends SyncChannel<ServerRuleSyncPayload> {

    public static final ServerRuleSyncChannel INSTANCE = new ServerRuleSyncChannel();

    /**
     * 每隔多少 tick 复查一次权限。
     *
     * <p>玩家被 {@code /op} / {@code /deop}、或者权限插件改了权限时没有任何事件可听，
     * 只能自己定期比一次。
     */
    private static final int PERMISSION_RECHECK_TICKS = 20;

    /**
     * 每个玩家上一次同步过去的内容，用来判断「有没有变化」
     */
    private final Map<UUID, List<ServerRuleSyncPayload.Entry>> lastSynced = new ConcurrentHashMap<>();

    protected ServerRuleSyncChannel() {
        super(ServerRuleSyncPayload.ID, ServerRuleSyncPayload.CODEC);
    }

    @Override
    public void registerEvents() {
        ServerPlayConnectionEvents.JOIN.register((handler, _, _) -> {
            // 刚进来的先忘掉旧记录，保证一定会推一份
            lastSynced.remove(handler.getPlayer().getUUID());
            syncTo(handler.getPlayer());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, _) ->
                lastSynced.remove(handler.getPlayer().getUUID()));

        // 任意规则状态变化 → 广播
        RuleCache.addListener((_, _, _) -> broadcastAll());

        // 定期复查权限：被下 op / 权限被改时，客户端那边会跟着刷新
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
     * 把这个玩家有权限看到的规则同步给他；内容和上次一样就什么都不发
     */
    public void syncTo(ServerPlayer player) {
        if (!canSendTo(player)) {
            return;
        }
        List<ServerRuleSyncPayload.Entry> entries = buildEntries(player);
        List<ServerRuleSyncPayload.Entry> previous = lastSynced.put(player.getUUID(), entries);
        if (entries.equals(previous)) {
            return;
        }
        sendTo(player, new ServerRuleSyncPayload(entries));
    }

    /**
     * 广播给所有在线玩家（各自只收到自己有权看到的那些）
     */
    public void broadcastAll() {
        forEachPlayer(this::syncTo);
    }

    /**
     * 这个玩家能看到的规则清单：没权限的规则直接不下发（返回空清单 = 一条都看不了）。
     *
     * <p>权限只看 {@code /ec} 那道总权限；有权限就把规则全下发，客户端那边能看也能改。
     */
    private List<ServerRuleSyncPayload.Entry> buildEntries(ServerPlayer player) {
        if (RuleCache.lacksRuleCommandPermission(player)) {
            return List.of();
        }
        List<ServerRuleSyncPayload.Entry> entries = new ArrayList<>(RuleType.values().length);
        for (RuleType type : RuleType.values()) {
            entries.add(new ServerRuleSyncPayload.Entry(type.getRegisterName(), RuleCache.getState(type)));
        }
        return List.copyOf(entries);
    }
}
