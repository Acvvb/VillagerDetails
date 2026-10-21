package com.villagerdetails.command.c.impl.rule;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.command.c.impl.base.AbstractRuleCommand;
import com.villagerdetails.command.c.impl.suggestion.BlockSuggestionData;
import com.villagerdetails.config.impl.section.item.EndPortalGravityBlockConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * /ec c EndPortalGravityBlock &lt;方块id&gt; true|false —— 设置/取消某个方块的重力特性。
 * /ec c EndPortalGravityBlock reset —— 清空所有配置。
 * /ec c EndPortalGravityBlock show  —— 显示当前名单。
 */
public final class EndPortalGravityBlockServerImpl extends AbstractRuleCommand {

    public static final EndPortalGravityBlockServerImpl INSTANCE = new EndPortalGravityBlockServerImpl();

    private EndPortalGravityBlockServerImpl() {
    }

    private static final String K_SET = "command.block.end_portal_gravity.set";
    private static final String K_REMOVE = "command.block.end_portal_gravity.remove";
    private static final String K_RESET = "command.block.end_portal_gravity.reset";
    private static final String K_SHOW_HEADER = "command.block.end_portal_gravity.show.header";
    private static final String K_SHOW_EMPTY = "command.block.end_portal_gravity.show.empty";
    private static final String K_INVALID_VALUE = "command.block.end_portal_gravity.invalid_value";

    @Override
    protected String literalName() {
        return "EndPortalGravityBlock";
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
        EndPortalGravityBlockConfig.INSTANCE.add(id);
    }

    @Override
    protected void remove(Identifier id) {
        EndPortalGravityBlockConfig.INSTANCE.remove(id);
    }

    @Override
    protected void resetConfig() {
        EndPortalGravityBlockConfig.INSTANCE.reset();
    }

    @Override
    protected List<Identifier> snapshot() {
        return EndPortalGravityBlockConfig.INSTANCE.snapshot();
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