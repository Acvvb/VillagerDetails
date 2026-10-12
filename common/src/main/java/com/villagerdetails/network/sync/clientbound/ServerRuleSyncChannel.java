package com.villagerdetails.network.sync.clientbound;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.network.channel.SyncChannel;
import com.villagerdetails.network.sync.payload.clientbound.ServerRuleSyncPayload;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则状态同步：服务端 → 客户端。
 *
 * <p>放在 {@code network.sync.clientbound} 包下 = 自动注册成 S→C 包类型，不用手动登记。
 * 「玩家加入 / 断开」「任意规则变化」「每秒复查一次权限」这些接线
 * 在 {@code network.sync.event.ServerRuleSyncEvents}。
 *
 * <p>下发的是<b>这个玩家有权限看到的</b>规则状态，内容和上次一样就不发，
 * 所以每秒一次的权限复查几乎不产生流量。
 *
 * <p>包 id：{@code villagerdetails:server_rule_sync}
 */
public class ServerRuleSyncChannel extends SyncChannel<ServerRuleSyncPayload> {

    public static final ServerRuleSyncChannel INSTANCE = new ServerRuleSyncChannel();

    /**
     * 每个玩家上一次同步过去的内容，用来判断「有没有变化」
     */
    private final Map<UUID, List<ServerRuleSyncPayload.Entry>> lastSynced = new ConcurrentHashMap<>();

    protected ServerRuleSyncChannel() {
        super(ServerRuleSyncPayload.ID);
    }

    /**
     * 忘掉这个玩家上次同步过去的内容，保证下一次 {@link #syncTo} 一定推一份。
     *
     * <p>玩家刚进来、或者断开时由 {@code ServerRuleSyncEvents} 调：
     * 不这么做的话，同一次开服里重连（UUID 一样、内容也恰好一样）会一条都收不到。
     */
    public void forgetLastSynced(ServerPlayer player) {
        lastSynced.remove(player.getUUID());
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
