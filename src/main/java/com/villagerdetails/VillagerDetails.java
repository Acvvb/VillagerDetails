package com.villagerdetails;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.command.EntityBinderCommand;
import com.villagerdetails.config.ServerLangConfig;
import com.villagerdetails.event.BBSelectionListener;
import com.villagerdetails.event.EBSectionManager;
import com.villagerdetails.event.ServerLifecycleListener;
import com.villagerdetails.event.ServerStoppingListener;
import com.villagerdetails.handler.villager.trader.refresh.IdTranslation;
import com.villagerdetails.lang.ServerTranslations;
import com.villagerdetails.network.VillagerBedPayload;
import com.villagerdetails.network.VillagerTrackingHandler;
import com.villagerdetails.rule.RuleModuleRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.resources.Identifier;

public class VillagerDetails implements ModInitializer {

	public static final String BAST_COMMAND = "ec";

	public static final String MOD_ID = "villagerdetails";


	@Override
	public void onInitialize() {

		PayloadTypeRegistry.clientboundPlay().register(VillagerBedPayload.TYPE, VillagerBedPayload.CODEC);

		ServerLifecycleEvents.SERVER_STARTED.register(ServerLifecycleListener::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(new ServerStoppingListener());

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			RuleCache.setServer(server);
			ServerLangConfig.loadFromWorld(server);
			ServerTranslations.preloadAll();
			ServerTranslations.switchTo(ServerLangConfig.getDefaultLang());
			IdTranslation.loadFromWorld(server);
		});

		RuleModuleRegistry.initAll();

		// 注册追踪事件(网络同步)
		VillagerTrackingHandler.register();

		// 注册指令
		CommandRegistrationCallback.EVENT.register((dispatcher, _, _) -> EntityBinderCommand.register(dispatcher));

		//实体-方块选择工具
		EBSectionManager.init();

		//方块-实体选择工具
		BBSelectionListener.init();

	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
