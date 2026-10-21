package com.villagerdetails.config.impl.section.item;

import com.villagerdetails.config.impl.section.base.IdSetConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;

import static com.villagerdetails.rule.type.RuleType.BLOCK_COLLECTABLE;

public final class BlockCollectableConfig extends IdSetConfig {

    private BlockCollectableConfig() {
        super(BLOCK_COLLECTABLE.getRegisterName());
    }

    public static final BlockCollectableConfig INSTANCE = new BlockCollectableConfig();

    @Override
    public String configName() {
        return BLOCK_COLLECTABLE.getRegisterName();
    }

    @Override
    public void reload(MinecraftServer server) {
        loadStorage();
    }

    public boolean contains(Block block) {
        return containsId(BuiltInRegistries.BLOCK.getKey(block));
    }
}