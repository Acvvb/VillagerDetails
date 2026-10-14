package com.villagerdetails.network.sync.payload.clientbound;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import com.villagerdetails.network.codec.DescriptorCodecs;
import com.villagerdetails.network.codec.ModCodecs;
import com.villagerdetails.rule.descriptor.RuleDescriptor;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * 服务端 → 客户端：某条逐条配置规则的「可选 id + 原版值」清单。
 *
 * <p>客户端拿它做两件事，两件都只是模板级操作、不需要认识规则语义：
 * <ul>
 *     <li>id 输入框的子串补全</li>
 *     <li>新增一条时的原版值预填（{@code value} 就是服务端算好的原版值）</li>
 * </ul>
 *
 * <p>{@code tooMany=true} 表示这条规则的 id 空间太大（或没有注册表），服务端没发清单：
 * 客户端退化成「手填 + 不预填」，合法性最终由服务端判定。
 *
 * <p>包 id：{@code villagerdetails:rule_data_options}
 */
public final class RuleDataOptionsPayload extends SyncPayload<RuleDataOptionsPayload> {

    public static final PayloadId<RuleDataOptionsPayload> ID = PayloadId.of("rule_data_options");

    public static final StreamCodec<RegistryFriendlyByteBuf, RuleDataOptionsPayload> CODEC =
            StreamCodec.composite(
                    ModCodecs.STRING, RuleDataOptionsPayload::registerName,
                    ModCodecs.BOOL, RuleDataOptionsPayload::tooMany,
                    ModCodecs.listOf(DescriptorCodecs.DATA_ENTRY), RuleDataOptionsPayload::options,
                    RuleDataOptionsPayload::new);

    private final String registerName;
    private final boolean tooMany;
    private final List<RuleDescriptor.DataEntry> options;

    public RuleDataOptionsPayload(String registerName, boolean tooMany,
                                  List<RuleDescriptor.DataEntry> options) {
        super(ID);
        this.registerName = registerName == null ? "" : registerName;
        this.tooMany = tooMany;
        this.options = options;
    }

    public String registerName() {
        return registerName;
    }

    /** id 空间太大 / 没有注册表：客户端别指望补全 */
    public boolean tooMany() {
        return tooMany;
    }

    /** id → 原版值（按 id 排序，保证同一份数据每次下发的字节稳定） */
    public List<RuleDescriptor.DataEntry> options() {
        return options;
    }
}
