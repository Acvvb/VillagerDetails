package com.villagerdetails.config.impl.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

public final class ConfigPaths {

    private ConfigPaths() {
    }

    /**
     * 存档根目录下的相对路径。
     */
    public static Path world(MinecraftServer server, String relative) {
        return server.getWorldPath(LevelResource.ROOT).resolve(relative);
    }
}