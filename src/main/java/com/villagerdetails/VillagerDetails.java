package com.villagerdetails;

import com.villagerdetails.command.EntityBinderCommand;
import com.villagerdetails.command.register.Command;
import com.villagerdetails.event.BBSelectionListener;
import com.villagerdetails.event.EBSectionManager;
import com.villagerdetails.event.ServerLifecycleListener;
import com.villagerdetails.event.ServerStoppingListener;
import com.villagerdetails.network.VillagerBedPayload;
import com.villagerdetails.network.VillagerTrackingHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VillagerDetails implements ModInitializer {

	public static final String MOD_ID = "entityController";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {

		LOGGER.info("Hello Fabric world!");

		PayloadTypeRegistry.clientboundPlay().register(VillagerBedPayload.TYPE, VillagerBedPayload.CODEC);

		ServerLifecycleEvents.SERVER_STARTED.register(ServerLifecycleListener::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(new ServerStoppingListener());

		BBSelectionListener.init();

		// 注册追踪事件(网络同步)
		VillagerTrackingHandler.register();

		// 注册指令
		CommandRegistrationCallback.EVENT.register((dispatcher, _, _) -> EntityBinderCommand.register(dispatcher));
		CommandRegistrationCallback.EVENT.register((dispatcher, _, _) -> Command.register(dispatcher));

		EBSectionManager.init();

	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
