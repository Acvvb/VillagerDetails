package com.villagerdetails.command.c.impl.rule;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.command.c.impl.suggestion.BlockSuggestionData;
import com.villagerdetails.config.impl.section.item.BlockExplosionResistanceConfig;
import com.villagerdetails.network.sync.clientbound.BlockRuleSyncChannel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * /ec c BlockExplosionResistance &lt;方块id&gt; &lt;抗性&gt; —— 设置某个方块的爆炸抗性。
 * /ec c BlockExplosionResistance reset —— 清空所有配置，恢复原版。
 */
public final class BlockExplosionResistanceServerImpl extends AbstractBlockResistanceServerImpl {

    public static final BlockExplosionResistanceServerImpl INSTANCE = new BlockExplosionResistanceServerImpl();

    private BlockExplosionResistanceServerImpl() {
    }

    @Override
    protected String commandName() {
        return "BlockExplosionResistance";
    }

    @Override
    protected String setKey() {
        return "command.block.explosion_resistance.set";
    }

    @Override
    protected String resetKey() {
        return "command.block.explosion_resistance.reset";
    }

    @Override
    protected String showHeaderKey() {
        return "command.block.explosion_resistance.show.header";
    }

    @Override
    protected String showEmptyKey() {
        return "command.block.explosion_resistance.show.empty";
    }

    @Override
    protected String invalidValueKey() {
        return "command.block.resistance.invalid_value";
    }

    @Override
    protected SuggestionProvider<CommandSourceStack> valueSuggester() {
        return BlockSuggestionData.VANILLA_EXPLOSION_RESISTANCE_SUGGESTER;
    }

    @Override
    protected void put(Identifier id, float value) {
        BlockExplosionResistanceConfig.INSTANCE.put(id, value);
    }

    @Override
    protected void reset() {
        BlockExplosionResistanceConfig.INSTANCE.reset();
    }

    @Override
    protected Map<Identifier, Float> snapshot() {
        return BlockExplosionResistanceConfig.INSTANCE.snapshot();
    }

    @Override
    protected void afterChange() {
        BlockRuleSyncChannel.INSTANCE.broadcastAll();
    }
}
