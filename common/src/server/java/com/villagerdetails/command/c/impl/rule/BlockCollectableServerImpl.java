package com.villagerdetails.command.c.impl.rule;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.command.c.impl.base.AbstractRuleCommand;
import com.villagerdetails.command.c.impl.suggestion.BlockSuggestionData;
import com.villagerdetails.config.impl.section.item.BlockCollectableConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * /ec c BlockCollectable &lt;方块id&gt; true|false —— 设置/取消某个方块的「可采集」。
 * /ec c BlockCollectable reset —— 清空所有配置，恢复原版。
 * /ec c BlockCollectable show  —— 显示当前名单。
 */
public final class BlockCollectableServerImpl extends AbstractRuleCommand {

    public static final BlockCollectableServerImpl INSTANCE = new BlockCollectableServerImpl();

    private BlockCollectableServerImpl() {
    }

    private static final String K_SET = "command.block.collectable.set";
    private static final String K_REMOVE = "command.block.collectable.remove";
    private static final String K_RESET = "command.block.collectable.reset";
    private static final String K_SHOW_HEADER = "command.block.collectable.show.header";
    private static final String K_SHOW_EMPTY = "command.block.collectable.show.empty";
    private static final String K_INVALID_VALUE = "command.block.collectable.invalid_value";

    @Override
    protected String literalName() {
        return "BlockCollectable";
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
        return BlockSuggestionData.VANILLA_COLLECTABLE_SUGGESTER;
    }

    @Override
    protected void add(Identifier id) {
        BlockCollectableConfig.INSTANCE.add(id);
    }

    @Override
    protected void remove(Identifier id) {
        BlockCollectableConfig.INSTANCE.remove(id);
    }

    @Override
    protected void resetConfig() {
        BlockCollectableConfig.INSTANCE.reset();
    }

    @Override
    protected List<Identifier> snapshot() {
        return BlockCollectableConfig.INSTANCE.snapshot();
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