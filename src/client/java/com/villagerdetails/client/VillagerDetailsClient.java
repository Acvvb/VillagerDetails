package com.villagerdetails.client;

import com.villagerdetails.client.cache.ClientSessionStates;
import com.villagerdetails.client.feature.selection.BBSelectionHighlightRenderer;
import com.villagerdetails.client.feature.selection.EBSelectionHighlightRenderer;
import com.villagerdetails.client.feature.selection.PortalViewHighlightRenderer;
import com.villagerdetails.client.feature.villagerbed.VillagerBedLineRenderer;
import com.villagerdetails.client.network.ClientChannels;
import com.villagerdetails.client.setting.config.ClientRenderConfig;
import com.villagerdetails.client.setting.input.VillagerDetailsKeyMappings;
import net.fabricmc.api.ClientModInitializer;

public class VillagerDetailsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 读取客户端渲染配置（文件不存在时会生成一份默认配置）
        ClientRenderConfig.load();
        // 村民 ↔ 床 的连线渲染
        VillagerBedLineRenderer.register();
        // 快捷键（默认 J）打开配置界面
        VillagerDetailsKeyMappings.register();
        // 断线时统一清空所有「只活在本此会话里」的客户端状态（唯一的一处 DISCONNECT 清理）
        ClientSessionStates.registerEvents();
        // 所有同步包的客户端接收器（村民床缓存、逐条配置数据、规则状态）
        ClientChannels.registerAll();

        EBSelectionHighlightRenderer.register();

        BBSelectionHighlightRenderer.register();

        PortalViewHighlightRenderer.register();
    }
}
