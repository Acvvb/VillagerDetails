package com.villagerdetails.config.impl.section.item;

import com.villagerdetails.config.impl.section.base.IdFloatConfig;
import com.villagerdetails.config.impl.server.NumberConfigServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;

import static com.villagerdetails.rule.type.RuleType.BLOCK_EXPLOSION_RESISTANCE;

public final class BlockExplosionResistanceConfig extends IdFloatConfig implements NumberConfigServer {

    private BlockExplosionResistanceConfig() {
        super(BLOCK_EXPLOSION_RESISTANCE.getRegisterName());
    }

    public static final BlockExplosionResistanceConfig INSTANCE = new BlockExplosionResistanceConfig();

    @Override
    public String configName() {
        return BLOCK_EXPLOSION_RESISTANCE.getRegisterName();
    }

    @Override
    public void reload(MinecraftServer server) {
        loadStorage();
    }

    public Float get(Block block) {
        return get(BuiltInRegistries.BLOCK.getKey(block));
    }
}