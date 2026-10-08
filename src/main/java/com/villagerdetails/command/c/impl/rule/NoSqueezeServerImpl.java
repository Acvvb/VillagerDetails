package com.villagerdetails.command.c.impl.rule;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.command.c.impl.base.AbstractRuleCommand;
import com.villagerdetails.command.c.impl.suggestion.EntityIdSuggestionServer;
import com.villagerdetails.config.impl.NoSqueezeConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * /ec c NoSqueeze &lt;实体id&gt; true|false —— 把某种实体加进 / 移出「不会被挤压」名单。
 * /ec c NoSqueeze reset —— 清空所有配置。
 * /ec c NoSqueeze show  —— 显示当前名单。
 */
public final class NoSqueezeServerImpl extends AbstractRuleCommand {

    public static final NoSqueezeServerImpl INSTANCE = new NoSqueezeServerImpl();

    private NoSqueezeServerImpl() {
    }

    private static final String K_SET = "command.no_squeeze.set";
    private static final String K_REMOVE = "command.no_squeeze.remove";
    private static final String K_RESET = "command.no_squeeze.reset";
    private static final String K_SHOW_HEADER = "command.no_squeeze.show.header";
    private static final String K_SHOW_EMPTY = "command.no_squeeze.show.empty";
    private static final String K_INVALID_VALUE = "command.no_squeeze.invalid_value";

    @Override
    protected String literalName() {
        return "NoSqueeze";
    }

    @Override
    protected String argName() {
        return "entity";
    }

    @Override
    protected SuggestionProvider<CommandSourceStack> idSuggester() {
        return EntityIdSuggestionServer.ALL_ENTITY_ID_SUGGESTER;
    }

    @Override
    protected void add(Identifier id) {
        NoSqueezeConfig.add(id);
    }

    @Override
    protected void remove(Identifier id) {
        NoSqueezeConfig.remove(id);
    }

    @Override
    protected void resetConfig() {
        NoSqueezeConfig.reset();
    }

    @Override
    protected List<Identifier> snapshot() {
        return NoSqueezeConfig.snapshot();
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