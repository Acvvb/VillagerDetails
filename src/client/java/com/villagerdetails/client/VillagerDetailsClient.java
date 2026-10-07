package com.villagerdetails.client;

import com.villagerdetails.client.feature.blockrule.BlockRuleSyncClientReceiver;
import com.villagerdetails.client.feature.villagerbed.VillagerBedClientReceiver;
import com.villagerdetails.client.feature.villagerbed.VillagerBedLineRenderer;
import com.villagerdetails.client.setting.config.ClientRenderConfig;
import com.villagerdetails.client.setting.input.VillagerDetailsKeyMappings;
import com.villagerdetails.client.setting.network.ServerRuleClientReceiver;
import net.fabricmc.api.ClientModInitializer;

public class VillagerDetailsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 读取客户端渲染配置（文件不存在时会生成一份默认配置）
        ClientRenderConfig.load();
        VillagerBedClientReceiver.register();
        // 村民 ↔ 床 的连线渲染
        VillagerBedLineRenderer.register();
        // 快捷键（默认 K）打开配置界面
        VillagerDetailsKeyMappings.register();
        BlockRuleSyncClientReceiver.register();
        // 服务器规则状态同步 + 界面里的规则开关
        ServerRuleClientReceiver.register();
    }
}
