package com.villagerdetails.client;

import com.villagerdetails.client.network.VillagerBedClientReceiver;
import net.fabricmc.api.ClientModInitializer;

public class VillagerDetailsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// This entrypoint is suitable for setting up client-specific logic, such as rendering.
		VillagerBedClientReceiver.register();
	}
}