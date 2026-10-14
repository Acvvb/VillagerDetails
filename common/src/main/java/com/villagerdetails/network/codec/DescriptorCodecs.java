package com.villagerdetails.network.codec;

import com.villagerdetails.rule.descriptor.RuleDescriptor;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * 规则描述符（{@link RuleDescriptor}）的编解码片段。
 *
 * <p>放在这里而不是某个包类里：描述符同时被「全量描述符同步」和「逐条配置补全数据」两个包用到，
 * 编解码只写一份（和 {@link ModCodecs} 同一个理由：包定义只拼结构，不抄 codec）。
 *
 * <p>缓冲类型统一用 {@link FriendlyByteBuf}：它既是 {@code RegistryFriendlyByteBuf} 的父类
 * （能直接塞进 {@code StreamCodec.composite} 当元素），元素参数又收 {@code ? super FriendlyByteBuf}。
 */
public final class DescriptorCodecs {

    private DescriptorCodecs() {
    }

    public static final StreamCodec<FriendlyByteBuf, RuleDescriptor.Switch> SWITCH =
            StreamCodec.composite(
                    ModCodecs.STRING, RuleDescriptor.Switch::value,
                    ModCodecs.STRING, RuleDescriptor.Switch::text,
                    RuleDescriptor.Switch::new);

    public static final StreamCodec<FriendlyByteBuf, RuleDescriptor.DataEntry> DATA_ENTRY =
            StreamCodec.composite(
                    ModCodecs.STRING, RuleDescriptor.DataEntry::id,
                    ModCodecs.STRING, RuleDescriptor.DataEntry::value,
                    RuleDescriptor.DataEntry::new);

    public static final StreamCodec<FriendlyByteBuf, RuleDescriptor.Category> CATEGORY =
            StreamCodec.composite(
                    ModCodecs.STRING, RuleDescriptor.Category::registerName,
                    ModCodecs.STRING, RuleDescriptor.Category::text,
                    RuleDescriptor.Category::new);

    public static final StreamCodec<FriendlyByteBuf, RuleDescriptor.ListTemplate> TEMPLATE =
            StreamCodec.of(
                    (buf, template) -> {
                        ModCodecs.BOOL.encode(buf, template.plain());
                        ModCodecs.STRING.encode(buf, template.valueKind().name());
                        ModCodecs.STRING.encode(buf, template.idSource());
                    },
                    buf -> new RuleDescriptor.ListTemplate(
                            ModCodecs.BOOL.decode(buf),
                            RuleDescriptor.ValueKind.byName(ModCodecs.STRING.decode(buf)),
                            ModCodecs.STRING.decode(buf)));

    /**
     * 一条规则。
     *
     * <p>字段比较多（10 个），{@code StreamCodec.composite} 的定长重载套不下，
     * 所以手写一对读写；顺序必须和上面 record 的组件顺序一致。
     */
    public static final StreamCodec<FriendlyByteBuf, RuleDescriptor> RULE =
            StreamCodec.of(
                    (buf, rule) -> {
                        ModCodecs.STRING.encode(buf, rule.registerName());
                        ModCodecs.STRING.encode(buf, rule.name());
                        ModCodecs.STRING.encode(buf, rule.info());
                        ModCodecs.listOf(ModCodecs.STRING).encode(buf, rule.categories());
                        ModCodecs.STRING.encode(buf, rule.state());
                        ModCodecs.STRING.encode(buf, rule.defaultState());
                        ModCodecs.BOOL.encode(buf, rule.enabled());
                        ModCodecs.listOf(SWITCH).encode(buf, rule.switches());
                        // 模板可能没有（不支持逐条配置）：先写一个布尔，有才写内容
                        boolean hasTemplate = rule.template() != null;
                        ModCodecs.BOOL.encode(buf, hasTemplate);
                        if (hasTemplate) {
                            TEMPLATE.encode(buf, rule.template());
                        }
                        ModCodecs.listOf(DATA_ENTRY).encode(buf, rule.data());
                    },
                    buf -> {
                        String registerName = ModCodecs.STRING.decode(buf);
                        String name = ModCodecs.STRING.decode(buf);
                        String info = ModCodecs.STRING.decode(buf);
                        List<String> categories = ModCodecs.listOf(ModCodecs.STRING).decode(buf);
                        String state = ModCodecs.STRING.decode(buf);
                        String defaultState = ModCodecs.STRING.decode(buf);
                        boolean enabled = ModCodecs.BOOL.decode(buf);
                        List<RuleDescriptor.Switch> switches = ModCodecs.listOf(SWITCH).decode(buf);
                        RuleDescriptor.ListTemplate template =
                                ModCodecs.BOOL.decode(buf) ? TEMPLATE.decode(buf) : null;
                        List<RuleDescriptor.DataEntry> data = ModCodecs.listOf(DATA_ENTRY).decode(buf);
                        return new RuleDescriptor(registerName, name, info, categories, state,
                                defaultState, enabled, switches, template, data);
                    });
}
