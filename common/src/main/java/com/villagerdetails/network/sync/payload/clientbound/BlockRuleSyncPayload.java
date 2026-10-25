package com.villagerdetails.network.sync.payload.clientbound;

import com.villagerdetails.network.channel.ClientboundPayload;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import com.villagerdetails.network.codec.ModCodecs;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * 服务端 → 客户端：逐条配置类规则的<b>内容</b>（客户端玩法预测用的镜像）。
 *
 * <p>这份包和配置界面无关（界面那两类走 {@code rule_descriptor_sync} 的全量描述符），
 * 它存在的唯一理由是：客户端要算出和服务端一致的破坏进度 / 挤压判定，需要本地有一份
 * 方块挖掘抗性、可采集、无挤压这些配置的镜像（见 {@code RuleDataRegistry}）。
 *
 * <p>每条数据带着自己的 {@code enabled}：那份描述符是按权限过滤的（没 {@code /ec} 权限的玩家收不到），
 * 但本地预测对谁都一样要做，所以开关状态跟着这份一起发。
 *
 * <p>包 id：{@code villagerdetails:block_rule_sync}
 */
public final class BlockRuleSyncPayload extends SyncPayload<BlockRuleSyncPayload> implements ClientboundPayload {

    /**
     * 一条规则的数据。
     *
     * @param registerName 规则注册名（客户端按它反查规则）
     * @param data         内容（纯名单是 {@code id,id}；键值是 {@code id=值,id=值}）
     * @param enabled      这条规则开着没（客户端本地预测要用）
     */
    public record RuleData(String registerName, String data, boolean enabled) {

        /** 内部小结构，缓冲类型用 FriendlyByteBuf 就够（外面的大包再套成 RegistryFriendlyByteBuf） */
        public static final StreamCodec<FriendlyByteBuf, RuleData> CODEC =
                StreamCodec.composite(
                        ModCodecs.STRING, RuleData::registerName,
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
