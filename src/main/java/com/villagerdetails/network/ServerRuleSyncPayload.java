package com.villagerdetails.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：把所有规则的开关状态下发一份，供配置界面显示与操作。
 *
 * @param rules 规则注册名 → 状态字符串（和 {@code /ec} 命令里用的一致）
 */
public record ServerRuleSyncPayload(List<Entry> rules) implements CustomPacketPayload {

    /** 单条规则状态 */
    public record Entry(String registerName, String state) {
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerRuleSyncPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.rules().size());
                for (Entry entry : payload.rules()) {
                    buf.writeUtf(entry.registerName());
                    buf.writeUtf(entry.state());
                }
            },
            buf -> {
                int size = buf.readVarInt();
                List<Entry> entries = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    entries.add(new Entry(buf.readUtf(), buf.readUtf()));
                }
                return new ServerRuleSyncPayload(List.copyOf(entries));
            });

    public static final Identifier PACKET_ID = Identifier.fromNamespaceAndPath("villagerdetails", "server_rule_sync");

    public static final Type<ServerRuleSyncPayload> TYPE = new Type<>(PACKET_ID);

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
