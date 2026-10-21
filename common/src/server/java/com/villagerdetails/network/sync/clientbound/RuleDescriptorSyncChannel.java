package com.villagerdetails.network.sync.clientbound;

import com.villagerdetails.network.channel.SyncChannel;
import com.villagerdetails.network.sync.payload.clientbound.RuleDescriptorSyncPayload;
import com.villagerdetails.rule.registry.RuleDescriptorBuilder;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则描述符同步：服务端 → 客户端（协议 v2 的主角）。
 *
 * <p>下发的是「这个玩家有权限看到的每条规则的完整描述符」（分类顺序 + 翻译好的文字 + 状态 +
 * 开关 + 逐条配置模板与数据），客户端拿到什么画什么。
 *
 * <p>每份内容按玩家缓存：和上次一样就一个包都不发 —— 所以每秒一次的权限复查几乎不产生流量，
 * 而玩家语言变了（描述符里的文字跟着变）会自然被判定成「内容变了」重新推一份。
 *
 * <p>包 id：{@code villagerdetails:rule_descriptor_sync}
 */
public class RuleDescriptorSyncChannel extends SyncChannel<RuleDescriptorSyncPayload> {

    public static final RuleDescriptorSyncChannel INSTANCE = new RuleDescriptorSyncChannel();

    /**
     * 每个玩家上一次同步过去的内容，用来判断「有没有变化」
     */
    private final Map<UUID, RuleDescriptorSyncPayload> lastSynced = new ConcurrentHashMap<>();

    /**
     * 每个玩家声明的界面语言（来自 {@code rule_descriptor_hello}）。
     *
     * <p>「服务器 / 服务端配置」这两个分类的文字按它翻译，不受服务端「模组语言」规则影响。
     */
    private final Map<UUID, String> languages = new ConcurrentHashMap<>();

    protected RuleDescriptorSyncChannel() {
        super(RuleDescriptorSyncPayload.ID);
    }

    /**
     * 记下这个玩家的界面语言（由 hello 收包端调用）
     */
    public void setLanguage(ServerPlayer player, String language) {
        if (player != null) {
            languages.put(player.getUUID(), language == null ? "" : language);
        }
    }

    /**
     * 这个玩家声明的界面语言；没声明过返回空串（= 退回服务端当前语言）
     */
    public String languageOf(ServerPlayer player) {
        return player == null ? "" : languages.getOrDefault(player.getUUID(), "");
    }

    /**
     * 忘掉这个玩家上次同步过去的内容，保证下一次 {@link #syncTo} 一定推一份。
     *
     * <p><b>故意不动语言</b>：客户端进服时会先发 hello 再等推送，两个 JOIN 回调谁先谁后不保证；
     * 这里要是把语言一起清了，已经报到服务端的语言会被抹掉，整份描述符就退回服务端语言了。
     * 语言只在玩家真正断开时清（{@link #release}）。
     */
    public void forget(ServerPlayer player) {
        if (player != null) {
            lastSynced.remove(player.getUUID());
        }
    }

    /**
     * 玩家断开：内容和语言一起摘掉（静态 Map 不摘会一直堆，集成服换存档还会串 UUID）
     */
    public void release(ServerPlayer player) {
        if (player == null) {
            return;
        }
        lastSynced.remove(player.getUUID());
        languages.remove(player.getUUID());
    }

    /**
     * 把这个玩家该看到的描述符同步给他；内容和上次一样就什么都不发
     */
    public void syncTo(ServerPlayer player) {
        if (!canSendTo(player)) {
            return;
        }
        RuleDescriptorSyncPayload payload = RuleDescriptorBuilder.build(player, languageOf(player));
        RuleDescriptorSyncPayload previous = lastSynced.put(player.getUUID(), payload);
        if (payload.equals(previous)) {
            return;
        }
        sendTo(player, payload);
    }

    /**
     * 广播给所有在线玩家（各自只收到自己有权看到的那些）
     */
    public void broadcastAll() {
        forEachPlayer(this::syncTo);
    }
}
