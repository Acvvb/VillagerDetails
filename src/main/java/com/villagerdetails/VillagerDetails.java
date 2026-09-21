package com.villagerdetails;

import com.villagerdetails.command.EntityBinderCommand;
import com.villagerdetails.event.*;
import com.villagerdetails.network.VillagerBedPayload;
import com.villagerdetails.network.VillagerTrackingHandler;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
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

		ServerLifecycleEvents.SERVER_STARTED.register(new ServerLifecycleListener());
		ServerLifecycleEvents.SERVER_STOPPING.register(new ServerStoppingListener());

		SelectionEventListener.register();

		// 注册追踪事件(网络同步)
		VillagerTrackingHandler.register();

		// 注册指令
		CommandRegistrationCallback.EVENT.register((dispatcher, _, _) -> EntityBinderCommand.register(dispatcher));

		// 注册右键实体事件
		UseEntityCallback.EVENT.register(UseEntityListener::onUseEntity);

		// 注册右键方块事件
		UseBlockCallback.EVENT.register(UseBlockListener::onUseBlock);

	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
