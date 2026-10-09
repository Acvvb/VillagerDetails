package com.villagerdetails.network.sync.payload.clientbound;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import com.villagerdetails.network.codec.ModCodecs;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * 服务端 → 客户端：逐条配置类规则的开关状态和配置内容。
 *
 * <p>让客户端本地能算出和服务端一致的破坏进度，也让「服务器配置项」界面能显示当前配置 ——
 * 界面上那两种列表形态（纯名单 / id → 数字）就是靠每条数据的 {@code kind} 标识决定的，
 * 客户端不写死。
 *
 * <p>每条数据带着自己的 {@code enabled}：规则状态那份同步是按权限过滤的
 * （没 {@code /ec} 权限的玩家收不到），但客户端要拿它做本地预测，所以开关状态跟着这份一起发。
 *
 * <p>包 id：{@code villagerdetails:block_rule_sync}
 */
public final class BlockRuleSyncPayload extends SyncPayload<BlockRuleSyncPayload> {

    /**
     * 一条规则的数据。
     *
     * @param registerName 规则注册名（客户端按它反查规则）
     * @param kind         {@code RuleDataKind} 的名字：列表形态标识（纯名单 / id → 数字）
     * @param data         内容（纯名单是 {@code id,id}；键值是 {@code id=值,id=值}）
     * @param enabled      这条规则开着没（客户端本地预测要用）
     */
    public record RuleData(String registerName, String kind, String data, boolean enabled) {

        /** 内部小结构，缓冲类型用 FriendlyByteBuf 就够（外面的大包再套成 RegistryFriendlyByteBuf） */
        public static final StreamCodec<FriendlyByteBuf, RuleData> CODEC =
                StreamCodec.composite(
                        ModCodecs.STRING, RuleData::registerName,
                        ModCodecs.STRING, RuleData::kind,
                        ModCodecs.STRING, RuleData::data,
                        ModCodecs.BOOL, RuleData::enabled,
                        RuleData::new);
    }

    public static final PayloadId<BlockRuleSyncPayload> ID = PayloadId.of("block_rule_sync");

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockRuleSyncPayload> CODEC =
            StreamCodec.composite(
                    ModCodecs.listOf(RuleData.CODEC), BlockRuleSyncPayload::ruleData,
                    BlockRuleSyncPayload::new);

    private final List<RuleData> ruleData;

    public BlockRuleSyncPayload(List<RuleData> ruleData) {
        super(ID);
        this.ruleData = ruleData;
    }

    public List<RuleData> ruleData() {
        return ruleData;
    }
}
