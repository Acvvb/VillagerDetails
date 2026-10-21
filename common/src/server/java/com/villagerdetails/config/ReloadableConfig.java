package com.villagerdetails.config;

import net.minecraft.server.MinecraftServer;

public interface ReloadableConfig {

    String configName();

    void reload(MinecraftServer server);
}