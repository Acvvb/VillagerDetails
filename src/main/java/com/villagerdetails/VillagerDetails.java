package com.villagerdetails;

import com.villagerdetails.command.VillageCommand;
import com.villagerdetails.handler.BedBindingHandler;
import com.villagerdetails.handler.WorkBlockBindingHandler;
import com.villagerdetails.network.VillagerBedPayload;
import com.villagerdetails.network.VillagerTrackingHandler;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VillagerDetails implements ModInitializer {
	public static final String MOD_ID = "villagerdetails";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {

		LOGGER.info("Hello Fabric world!");

		PayloadTypeRegistry.clientboundPlay().register(VillagerBedPayload.TYPE, VillagerBedPayload.CODEC);

		// 注册追踪事件
		VillagerTrackingHandler.register();

		CommandRegistrationCallback.EVENT.register((dispatcher, _, _) -> VillageCommand.register(dispatcher));

		// 注册右键村民事件
		UseEntityCallback.EVENT.register((player, level, hand, entity, _) -> BedBindingHandler.onUseEntity(player, level, hand, entity));

		// 注册右键方块事件
		UseBlockCallback.EVENT.register(BedBindingHandler::onUseBlock);

		// 注册右键实体事件（右键村民）
		UseEntityCallback.EVENT.register(WorkBlockBindingHandler::onUseEntity);

		// 注册右键方块事件（右键工作方块）
		UseBlockCallback.EVENT.register(WorkBlockBindingHandler::onUseBlock);

	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
