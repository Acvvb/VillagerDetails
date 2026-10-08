package com.villagerdetails.network.sync;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import com.villagerdetails.network.codec.ModCodecs;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * 服务端 → 客户端：把所有规则的状态下发一份，供配置界面显示与操作。
 *
 * <p>包 id：{@code villagerdetails:server_rule_sync}
 */
public final class ServerRuleSyncPayload extends SyncPayload<ServerRuleSyncPayload> {

    /** 单条规则状态 */
    public record Entry(String registerName, String state) {

        /** 内部小结构，缓冲类型用 FriendlyByteBuf 就够（外面的大包再套成 RegistryFriendlyByteBuf） */
        public static final StreamCodec<FriendlyByteBuf, Entry> CODEC =
                StreamCodec.composite(
                        ModCodecs.STRING, Entry::registerName,
                        ModCodecs.STRING, Entry::state,
                        Entry::new);
    }

    public static final PayloadId<ServerRuleSyncPayload> ID = PayloadId.of("server_rule_sync");

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerRuleSyncPayload> CODEC =
            StreamCodec.composite(
                    ModCodecs.listOf(Entry.CODEC), ServerRuleSyncPayload::rules,
                    ServerRuleSyncPayload::new);

    private final List<Entry> rules;

    public ServerRuleSyncPayload(List<Entry> rules) {
        super(ID);
        this.rules = rules;
    }

    /** 规则注册名 → 状态字符串（和 {@code /ec} 命令里用的一致） */
    public List<Entry> rules() {
        return rules;
    }
}
