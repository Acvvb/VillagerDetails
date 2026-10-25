package com.villagerdetails.network.sync.payload.serverbound;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerboundPayload;
import com.villagerdetails.network.channel.SyncPayload;
import com.villagerdetails.network.codec.ModCodecs;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 客户端 → 服务端：改「服务器配置项」那几条规则的逐条数据。
 *
 * <p>支持的三件事（其余组合都是非法的）：
 * <ul>
 *     <li>{@code reset=true}：整条规则的数据清空（等于 {@code /ec c <规则> reset}）</li>
 *     <li>{@code remove=true}：删掉某个 id 的那一条</li>
 *     <li>两个 flag 都是 false：把 {@code id} 设成 {@code value}
 *         （抗性是数字，可采集 / 无挤压是 {@code true|false}）</li>
 * </ul>
 *
 * <p>包 id：{@code villagerdetails:rule_data}
 */
public final class RuleDataPayload extends SyncPayload<RuleDataPayload> implements ServerboundPayload {

    public static final PayloadId<RuleDataPayload> ID = PayloadId.of("rule_data");

    public static final StreamCodec<RegistryFriendlyByteBuf, RuleDataPayload> CODEC =
            StreamCodec.composite(
                    ModCodecs.STRING, RuleDataPayload::registerName,
                    ModCodecs.STRING, RuleDataPayload::id,
                    ModCodecs.STRING, RuleDataPayload::value,
                    ModCodecs.BOOL, RuleDataPayload::remove,
                    ModCodecs.BOOL, RuleDataPayload::reset,
                    RuleDataPayload::new);

    private final String registerName;
    private final String id;
    private final String value;
    private final boolean remove;
    private final boolean reset;

    public RuleDataPayload(String registerName, String id, String value,
                           boolean remove, boolean reset) {
        super(ID);
        this.registerName = registerName;
        this.id = id;
        this.value = value;
        this.remove = remove;
        this.reset = reset;
    }

    public String registerName() {
        return registerName;
    }

    public String id() {
        return id;
    }

    public String value() {
        return value;
    }

    public boolean remove() {
        return remove;
    }

    public boolean reset() {
        return reset;
    }
}
