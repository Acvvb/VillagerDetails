package com.villagerdetails.config.impl.section.item;

import com.villagerdetails.config.impl.section.base.IdFloatConfig;
import com.villagerdetails.config.impl.server.NumberConfigServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;

import static com.villagerdetails.rule.type.RuleType.BLOCK_MINING_RESISTANCE;

public final class BlockMiningResistanceConfig extends IdFloatConfig implements NumberConfigServer {

    private BlockMiningResistanceConfig() {
        super(BLOCK_MINING_RESISTANCE.getRegisterName());
    }

    public static final BlockMiningResistanceConfig INSTANCE = new BlockMiningResistanceConfig();

    @Override
    public String configName() {
        return BLOCK_MINING_RESISTANCE.getRegisterName();
    }

    @Override
    public void reload(MinecraftServer server) {
        loadStorage();
    }

    /**
     * 未配置返回 null。
     */
    public Float get(Block block) {
        return get(BuiltInRegistries.BLOCK.getKey(block));
    }
}