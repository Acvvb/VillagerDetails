package com.villagerdetails.command.c.impl.rule.rule;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.command.c.impl.base.AbstractRuleCommand;
import com.villagerdetails.command.c.impl.suggestion.BlockSuggestionData;
import com.villagerdetails.config.impl.section.item.BlockStrongPowerConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * /ec c BlockStrongPower &lt;方块id&gt; true|false —— 设置/取消某个方块的「红石导体」。
 * /ec c BlockStrongPower reset —— 清空所有配置。
 * /ec c BlockStrongPower show  —— 显示当前名单。
 */
public final class BlockStrongPowerServerImpl extends AbstractRuleCommand {

    public static final BlockStrongPowerServerImpl INSTANCE = new BlockStrongPowerServerImpl();

    private BlockStrongPowerServerImpl() {
    }

    private static final String K_SET = "command.block.strong_power.set";
    private static final String K_REMOVE = "command.block.strong_power.remove";
    private static final String K_RESET = "command.block.strong_power.reset";
    private static final String K_SHOW_HEADER = "command.block.strong_power.show.header";
    private static final String K_SHOW_EMPTY = "command.block.strong_power.show.empty";
    private static final String K_INVALID_VALUE = "command.block.strong_power.invalid_value";

    @Override
    protected String literalName() {
        return "BlockStrongPower";
    }

    @Override
    protected String argName() {
        return "block";
    }

    @Override
    protected SuggestionProvider<CommandSourceStack> idSuggester() {
        return BlockSuggestionData.ALL_BLOCK_ID_SUGGESTER;
    }

    @Override
    protected SuggestionProvider<CommandSourceStack> valueSuggester() {
        return (_, builder) -> {
            builder.suggest("true");
            builder.suggest("false");
            return builder.buildFuture();
        };
    }

    @Override
    protected void add(Identifier id) {
        BlockStrongPowerConfig.INSTANCE.add(id);
    }

    @Override
    protected void remove(Identifier id) {
        BlockStrongPowerConfig.INSTANCE.remove(id);
    }

    @Override
    protected void resetConfig() {
        BlockStrongPowerConfig.INSTANCE.reset();
    }

    @Override
    protected List<Identifier> snapshot() {
        return BlockStrongPowerConfig.INSTANCE.snapshot();
    }

    @Override
    protected String kSet() {
        return K_SET;
    }

    @Override
    protected String kRemove() {
        return K_REMOVE;
    }

    @Override
    protected String kReset() {
        return K_RESET;
    }

    @Override
    protected String kShowHeader() {
        return K_SHOW_HEADER;
    }

    @Override
    protected String kShowEmpty() {
        return K_SHOW_EMPTY;
    }

    @Override
    protected String kInvalidValue() {
        return K_INVALID_VALUE;
    }
}