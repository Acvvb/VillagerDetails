package com.villagerdetails.command.c.impl.rule;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.command.c.impl.suggestion.BlockSuggestionData;
import com.villagerdetails.config.impl.BlockMiningResistanceConfig;
import com.villagerdetails.network.BlockRuleSyncHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * /ec c BlockMiningResistance &lt;方块id&gt; &lt;抗性&gt; —— 设置某个方块的破坏抗性。
 * /ec c BlockMiningResistance reset —— 清空所有配置，恢复原版。
 */
public final class BlockMiningResistanceServerImpl extends AbstractBlockResistanceServerImpl {

    public static final BlockMiningResistanceServerImpl INSTANCE = new BlockMiningResistanceServerImpl();

    private BlockMiningResistanceServerImpl() {
    }

    @Override
    protected String commandName() {
        return "BlockMiningResistance";
    }

    @Override
    protected String setKey() {
        return "command.block.mining_resistance.set";
    }

    @Override
    protected String resetKey() {
        return "command.block.mining_resistance.reset";
    }

    @Override
    protected String showHeaderKey() {
        return "command.block.mining_resistance.show.header";
    }

    @Override
    protected String showEmptyKey() {
        return "command.block.mining_resistance.show.empty";
    }

    @Override
    protected String invalidValueKey() {
        return "command.block.resistance.invalid_value";
    }

    @Override
    protected SuggestionProvider<CommandSourceStack> valueSuggester() {
        return BlockSuggestionData.VANILLA_DESTROY_TIME_SUGGESTER;
    }

    @Override
    protected void put(Identifier id, float value) {
        BlockMiningResistanceConfig.put(id, value);
    }

    @Override
    protected void reset() {
        BlockMiningResistanceConfig.reset();
    }

    @Override
    protected Map<Identifier, Float> snapshot() {
        return BlockMiningResistanceConfig.snapshot();
    }

    @Override
    protected void afterChange() {
        BlockRuleSyncHandler.broadcast();
    }
}
