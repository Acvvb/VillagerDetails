package com.villagerdetails;

import com.villagerdetails.entity.ModEntities;
import com.villagerdetails.network.channel.PayloadTypes;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

/**
 * 两端共用的<b>模组标识与网络契约初始化</b>。
 *
 * <p>三端分离之后这里只做「两端都必须做」的一件事：注册自定义包<b>类型</b>
 * （{@link PayloadTypes}）。Fabric 要求同一条 {@code PayloadTypeRegistry} 登记在物理两端，
 * 否则客户端收到自定义包时无法解码、直接断开。
 *
 * <p>真正的服务端逻辑初始化在 {@code com.villagerdetails.server.VillagerDetailsServer}
 * （server 源集，走 Fabric 的 {@code server} 入口点）；客户端在
 * {@code com.villagerdetails.client.VillagerDetailsClient}（client 入口点）。
 */
public final class VillagerDetails implements ModInitializer {

    /**
     * 命令根字面量（{@code /ec ...}）。
     *
     * <p><b>值不要改</b>：{@code ModActivities} 用它拼出 {@code ec:go_to_destination} 这个 activity 名，
     * 而 activity 名会写进实体存档（brain memories），改了值老存档就对不上了。
     */
    public static final String BASE_COMMAND = "ec";

    public static final String MOD_ID = "villagerdetails";

    @Override
    public void onInitialize() {
        // 包类型必须两端都登记：这是客户端能收到/发出自定义包的前提
        PayloadTypes.registerAll();
        // 自定义实体类型也必须两端登记（客户端要按同一份类型解码生成包），
        // 目前只有「方块座位」一个，见 ModEntities
        ModEntities.register();
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
