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
 * 服务端 → 客户端：配置界面「服务器 / 服务端配置」两个分类需要的<b>全部内容</b>。
 *
 * <p>「全量」是字面意思：这个玩家有权限看到的每条规则都带完整描述符 ——
 * 分类顺序、翻译好的名称与描述、状态、快捷开关、逐条配置模板与当前数据。
 * 客户端拿到什么画什么，不再查自己的规则枚举，所以服务端新增/改名/改选项，老客户端也跟得上。
 *
 * <p>为空（{@code rules} 空表）= 这个玩家没有 {@code /ec} 权限，客户端那两个分类整个不显示。
 *
 * <p>包 id：{@code villagerdetails:rule_descriptor_sync}
 */
public final class RuleDescriptorSyncPayload extends SyncPayload<RuleDescriptorSyncPayload> {

    public static final PayloadId<RuleDescriptorSyncPayload> ID = PayloadId.of("rule_descriptor_sync");

    public static final StreamCodec<RegistryFriendlyByteBuf, RuleDescriptorSyncPayload> CODEC =
            StreamCodec.composite(
                    ModCodecs.VAR_INT, RuleDescriptorSyncPayload::protocol,
                    ModCodecs.listOf(DescriptorCodecs.CATEGORY), RuleDescriptorSyncPayload::categories,
                    ModCodecs.listOf(DescriptorCodecs.RULE), RuleDescriptorSyncPayload::rules,
                    RuleDescriptorSyncPayload::new);

    private final int protocol;
    private final List<RuleDescriptor.Category> categories;
    private final List<RuleDescriptor> rules;

    public RuleDescriptorSyncPayload(int protocol,
                                     List<RuleDescriptor.Category> categories,
                                     List<RuleDescriptor> rules) {
        super(ID);
        this.protocol = protocol;
        this.categories = categories;
        this.rules = rules;
    }

    /** 下发时用的协议版本 */
    public int protocol() {
        return protocol;
    }

    /** 二级分类页签（有序；已经剔掉一条规则都没有的空分类） */
    public List<RuleDescriptor.Category> categories() {
        return categories;
    }

    /** 有权限看到的规则（按 {@code RuleType} 声明顺序） */
    public List<RuleDescriptor> rules() {
        return rules;
    }
}
