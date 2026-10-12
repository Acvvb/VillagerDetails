package com.villagerdetails.network.channel;

import com.villagerdetails.VillagerDetails;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 同步包的「身份证」：包 id + {@link CustomPacketPayload.Type}。
 *
 * <p>每个同步包在自己类里声明一行：
 * {@code public static final PayloadId<XxxPayload> ID = PayloadId.of("xxx");}
 *
 * <p>命名空间统一用模组 id（{@link VillagerDetails#MOD_ID}）——
 * 之前 {@code VillagerBedPayload} 写成了别的命名空间，属于笔误，这里一并纠正。
 */
public final class PayloadId<P extends CustomPacketPayload> {

    private final Identifier id;
    private final CustomPacketPayload.Type<P> type;

    private PayloadId(String path) {
        this.id = Identifier.fromNamespaceAndPath(VillagerDetails.MOD_ID, path);
        this.type = new CustomPacketPayload.Type<>(id);
    }

    public static <P extends CustomPacketPayload> PayloadId<P> of(String path) {
        return new PayloadId<>(path);
    }

    /** 完整包 id，例如 {@code villagerdetails:rule_data} */
    public Identifier id() {
        return id;
    }

    /** 注册 / 收发用的类型（是个 record，equals 按 id 比，两端各自持有也不会错配） */
    public CustomPacketPayload.Type<P> type() {
        return type;
    }
}
