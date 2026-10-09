package com.villagerdetails;

import com.villagerdetails.command.RuleCommand;
import com.villagerdetails.config.ConfigRegistry;
import com.villagerdetails.event.BBSelectionListener;
import com.villagerdetails.event.EBSectionManager;
import com.villagerdetails.event.ServerLifecycleListener;
import com.villagerdetails.event.ServerStoppingListener;
import com.villagerdetails.handler.monster.noBrainOnPortal.NoBrainOnPortalHandler;
import com.villagerdetails.network.channel.ServerChannels;
import com.villagerdetails.rule.registry.RuleModuleRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;

public class VillagerDetails implements ModInitializer {

    public static final String BAST_COMMAND = "ec";

    public static final String MOD_ID = "villagerdetails";


    @Override
    public void onInitialize() {

        // 所有同步包（包类型注册 + 服务端收包 + 事件）由 ServerChannels 扫 network.sync 的四个子包自动注册，
        // 加新包只要按目录放类，见那个类的表格
        ServerChannels.registerAll();

        ServerLifecycleEvents.SERVER_STARTED.register(ServerLifecycleListener::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(new ServerStoppingListener());

        ServerLifecycleEvents.SERVER_STARTED.register(ConfigRegistry::reloadAll);

        RuleModuleRegistry.initAll();

        // 注册指令
        CommandRegistrationCallback.EVENT.register((dispatcher, _, _) -> RuleCommand.register(dispatcher));

        //实体-方块选择工具
        EBSectionManager.init();

        //方块-实体选择工具
        BBSelectionListener.init();

        //穿过地狱门回调（暂时写死怪物去脑子）
        NoBrainOnPortalHandler.register();
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
