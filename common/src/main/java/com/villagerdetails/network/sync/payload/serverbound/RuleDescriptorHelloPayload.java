package com.villagerdetails.network.sync.payload.serverbound;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerboundPayload;
import com.villagerdetails.network.channel.SyncPayload;
import com.villagerdetails.network.codec.ModCodecs;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 客户端 → 服务端：打招呼 + 声明自己的协议版本和界面语言。
 *
 * <p>三件事合一，省得再开一个「请求同步」的包：
 * <ul>
 *     <li><b>声明协议版本</b>：服务端版本对不上就只记日志不做同步</li>
 *     <li><b>声明界面语言</b>：配置界面里「服务器 / 服务端配置」这两个分类的规则名、描述、按钮文字
 *         都按这个语言翻译好再下发 —— 这两个分类跟客户端语言走，不受服务端「模组语言」规则影响</li>
 *     <li><b>顺带当成一次「把描述符推给我」的请求</b>：进服、打开配置界面、改了界面语言都发一次</li>
 * </ul>
 *
 * <p>包 id：{@code villagerdetails:rule_descriptor_hello}
 */
public final class RuleDescriptorHelloPayload extends SyncPayload<RuleDescriptorHelloPayload> implements ServerboundPayload {

    public static final PayloadId<RuleDescriptorHelloPayload> ID = PayloadId.of("rule_descriptor_hello");

    public static final StreamCodec<RegistryFriendlyByteBuf, RuleDescriptorHelloPayload> CODEC =
            StreamCodec.composite(
                    ModCodecs.VAR_INT, RuleDescriptorHelloPayload::protocol,
                    ModCodecs.STRING, RuleDescriptorHelloPayload::language,
                    RuleDescriptorHelloPayload::new);

    private final int protocol;
    private final String language;

    public RuleDescriptorHelloPayload(int protocol, String language) {
        super(ID);
        this.protocol = protocol;
        this.language = language == null ? "" : language;
    }

    public int protocol() {
        return protocol;
    }

    /** 客户端界面语言代码，例如 {@code zh_cn}；空 = 用服务端当前语言兜底 */
    public String language() {
        return language;
    }
}
